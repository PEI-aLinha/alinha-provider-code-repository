package pt.alinha.coderepository.internal

import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pt.alinha.coderepository.CodeRepositoryPlugin
import pt.alinha.coderepository.internal.domain.ConnectionStatus
import pt.alinha.coderepository.internal.domain.Names
import pt.alinha.coderepository.internal.domain.Outcome
import pt.alinha.coderepository.internal.persistence.ConnectionEntity
import pt.alinha.coderepository.internal.persistence.ConnectionRepository
import pt.alinha.coderepository.internal.persistence.EvidenceEntity
import pt.alinha.coderepository.internal.persistence.EvidenceRepository
import pt.alinha.core.audit.AuditTrail
import pt.alinha.core.jobs.JobDispatcher
import pt.alinha.core.jobs.JobRequest
import pt.alinha.core.tenant.CorrelationContext
import pt.alinha.core.tenant.TenantContext
import pt.alinha.pluginapi.TenantSecretStore
import pt.alinha.pluginapi.EvidenceCollected
import pt.alinha.pluginapi.EvidenceOutcome
import pt.alinha.pluginapi.EvidenceStrength
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Connects the tenant's repository host, starts collections and stores what they find. */
@Service
class ConnectionService(
    private val connections: ConnectionRepository,
    private val evidence: EvidenceRepository,
    /** Only present when OpenBao is configured (spring.cloud.vault.enabled); without it, connecting answers 503. */
    private val secretStore: ObjectProvider<TenantSecretStore>,
    private val jobs: JobDispatcher,
    private val audit: AuditTrail,
    private val events: ApplicationEventPublisher,
    private val tenants: TenantContext,
    private val correlation: CorrelationContext,
    private val json: JsonMapper,
    private val clock: Clock = Clock.systemUTC(),
) {

    @Transactional(readOnly = true)
    fun connection(): ConnectionView? = connections.findFirstBy()?.toView()

    @Transactional(readOnly = true)
    fun latestEvidence(): List<EvidenceView> {
        val jobId = connections.findFirstBy()?.collectedJobId ?: return emptyList()
        return evidence.findByJobIdOrderByRepositoryAscCheckNameAsc(jobId).map { it.toView() }
    }

    /** Creates or changes the connection and starts a collection. The token goes to OpenBao, never to the database. */
    @Transactional
    fun connect(input: ConnectInput): ConnectionView {
        val valid = input.validate()
        val now = Instant.now(clock)
        val existing = connections.findFirstBy()
        if (existing == null && valid.token == null) throw TokenRequired()
        if (existing?.isCollecting(now, COLLECTION_DEADLINE) == true) throw CollectionRunning()
        if (valid.token != null) secrets() // fail before writing anything when OpenBao is not configured

        // save() may merge (assigned id) and return another instance: keep working on the managed one.
        val connection = existing?.apply { reconfigure(valid.host, valid.account, valid.repositories, now) }
            ?: connections.save(ConnectionEntity(UUID.randomUUID(), valid.host, valid.account, valid.repositories, now))
        audit.record(
            if (existing == null) "code-repository.connected" else "code-repository.reconfigured",
            ENTITY_TYPE,
            connection.id.toString(),
            mapOf(
                "host" to valid.host.id,
                "account" to valid.account,
                "repositories" to valid.repositories.size,
                "tokenChanged" to (valid.token != null),
            ),
        )
        startCollection(connection, now)
        // A concurrent connect fails here (unique tenant row, @Version), before it can overwrite the stored token.
        connections.flush()
        // Last: if OpenBao refuses, the exception rolls the connection, the audit and the job back.
        valid.token?.let { secrets().put(CodeRepositoryPlugin.ID, mapOf("token" to it)) }
        return connection.toView()
    }

    @Transactional
    fun collectNow(): ConnectionView {
        val connection = connections.findFirstBy() ?: throw NotConnected()
        val now = Instant.now(clock)
        if (connection.isCollecting(now, COLLECTION_DEADLINE)) throw CollectionRunning()
        audit.record("code-repository.collection-requested", ENTITY_TYPE, connection.id.toString())
        startCollection(connection, now)
        return connection.toView()
    }

    /** Removes the connection and the stored token. Evidence already collected stays. */
    @Transactional
    fun disconnect() {
        val connection = connections.findFirstBy() ?: throw NotConnected()
        connections.delete(connection)
        audit.record("code-repository.disconnected", ENTITY_TYPE, connection.id.toString())
        secrets().delete(CodeRepositoryPlugin.ID)
    }

    /**
     * Stores the evidence of a completed job. Called by [CollectionResultListener] inside `runAs` and a transaction.
     * Ignores jobs that are not the connection's current one, and jobs already applied.
     */
    @Transactional
    fun applyResult(jobId: UUID, result: Map<String, Any?>?) {
        val connection = connections.findFirstBy()?.takeIf { it.lastJobId == jobId } ?: return
        // Already applied (stored, or failed as invalid): a replayed event changes nothing.
        if (connection.status != ConnectionStatus.PENDING || evidence.existsByJobId(jobId)) return
        val parsed = try {
            CollectionResultParser.parse(result, connection.repositories)
                .also { if (!it.account.equals(connection.account, true) || it.host != connection.host) throw InvalidCollectionResult("scope") }
        } catch (invalid: InvalidCollectionResult) {
            fail(connection, jobId, INVALID_RESULT)
            return
        }
        val now = Instant.now(clock)
        val stored = parsed.outcomes.map { outcome ->
            evidence.save(
                EvidenceEntity(
                    id = UUID.randomUUID(),
                    connectionId = connection.id,
                    jobId = jobId,
                    repository = outcome.repository,
                    check = outcome.check,
                    outcome = outcome.outcome,
                    details = json.writeValueAsString(outcome.details),
                    controlIds = ControlMapping.controlsFor(outcome.check),
                    strength = STRENGTH,
                    mappingVersion = ControlMapping.VERSION,
                    collectedAt = now,
                ),
            )
        }
        connection.collected(jobId, now, parsed.truncated)
        audit.record(
            "code-repository.evidence-collected",
            ENTITY_TYPE,
            connection.id.toString(),
            mapOf(
                "jobId" to jobId.toString(),
                "evidence" to stored.size,
                "pass" to stored.count { it.outcome == Outcome.PASS },
                "fail" to stored.count { it.outcome == Outcome.FAIL },
                "unknown" to stored.count { it.outcome == Outcome.UNKNOWN },
                "mappingVersion" to ControlMapping.VERSION,
            ),
        )
        // UNKNOWN is not evidence of anything: the token could not see it.
        stored.filter { it.outcome != Outcome.UNKNOWN }.forEach {
            events.publishEvent(
                EvidenceCollected(
                    tenantId = tenants.current(),
                    correlationId = correlation.current(),
                    occurredAt = now,
                    provider = CodeRepositoryPlugin.ID,
                    evidenceId = it.id,
                    controlIds = it.controlIds,
                    outcome = if (it.outcome == Outcome.PASS) EvidenceOutcome.SATISFIED else EvidenceOutcome.NOT_SATISFIED,
                    strength = it.strength,
                ),
            )
        }
    }

    /** Records why the connection's current job failed. [errorCode] comes from the worker: only its shape is kept. */
    @Transactional
    fun applyFailure(jobId: UUID, errorCode: String) {
        val connection = connections.findFirstBy()?.takeIf { it.lastJobId == jobId } ?: return
        if (connection.status != ConnectionStatus.PENDING) return
        fail(connection, jobId, errorCode.takeIf { Names.ERROR_CODE.matches(it) } ?: GENERIC_ERROR)
    }

    @Transactional(readOnly = true)
    fun evidence(evidenceId: UUID): EvidenceEntity? = evidence.findById(evidenceId).orElse(null)

    private fun secrets(): TenantSecretStore = secretStore.ifAvailable ?: throw SecretsUnavailable()

    private fun fail(connection: ConnectionEntity, jobId: UUID, code: String) {
        connection.failed(code)
        audit.record(
            "code-repository.collection-failed",
            ENTITY_TYPE,
            connection.id.toString(),
            mapOf("jobId" to jobId.toString(), "error" to code),
        )
    }

    private fun startCollection(connection: ConnectionEntity, now: Instant) {
        val jobId = jobs.dispatch(
            JobRequest(
                type = JOB_TYPE,
                payload = mapOf(
                    "provider" to CodeRepositoryPlugin.ID,
                    "connectionRef" to CONNECTION_REF,
                    "scope" to mapOf(
                        "host" to connection.host.id,
                        "account" to connection.account,
                        "repositories" to connection.repositories,
                    ),
                ),
                requestedBy = CodeRepositoryPlugin.ID,
            ),
        )
        connection.collecting(jobId, now)
    }

    private fun ConnectionEntity.toView() = ConnectionView(
        host = host.id,
        account = account,
        repositories = repositories,
        status = status,
        lastError = lastError,
        lastCollectedAt = lastCollectedAt,
        collectingSince = collectingSince,
        truncated = truncated,
        connectedAt = connectedAt,
    )

    @Suppress("UNCHECKED_CAST")
    private fun EvidenceEntity.toView() = EvidenceView(
        id = id,
        repository = repository,
        check = check.id,
        outcome = outcome,
        details = json.readValue(details, Map::class.java) as Map<String, Any?>,
        controlIds = controlIds.sorted(),
        strength = strength,
        collectedAt = collectedAt,
    )

    companion object {
        const val JOB_TYPE = "evidence.collect"
        const val ENTITY_TYPE = "code_repository_connection"
        /** docs/jobs.md, "Credentials for a collector": the worker builds the OpenBao path itself. */
        const val CONNECTION_REF = "providers/code-repository"
        const val INVALID_RESULT = "invalid_result"
        const val GENERIC_ERROR = "collector_error"
        val STRENGTH = EvidenceStrength.TECHNICALLY_VERIFIED

        /**
         * A collection still PENDING after this is treated as lost (a result that never came back, alinha#86), so a
         * new one may start. Well above the worker's deadline (WORKER_COLLECTOR_TIMEOUT_S × retries + backoff).
         */
        val COLLECTION_DEADLINE: java.time.Duration = java.time.Duration.ofMinutes(15)
    }
}
