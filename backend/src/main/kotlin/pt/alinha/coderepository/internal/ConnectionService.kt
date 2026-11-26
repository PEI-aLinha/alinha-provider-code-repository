package pt.alinha.coderepository.internal

import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pt.alinha.coderepository.CodeRepositoryPlugin
import pt.alinha.coderepository.internal.domain.AuthMethod
import pt.alinha.coderepository.internal.domain.ConnectionStatus
import pt.alinha.coderepository.internal.domain.Host
import pt.alinha.coderepository.internal.domain.Names
import pt.alinha.coderepository.internal.domain.Outcome
import pt.alinha.coderepository.internal.persistence.AssignmentRepository
import pt.alinha.coderepository.internal.persistence.CollectionScheduleEntity
import pt.alinha.coderepository.internal.persistence.CollectionScheduleRepository
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
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Connects the tenant's repository host, starts collections (on request and daily) and stores what they find. */
@Service
class ConnectionService(
    private val connections: ConnectionRepository,
    private val evidence: EvidenceRepository,
    private val schedules: CollectionScheduleRepository,
    private val assignments: AssignmentRepository,
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

    /**
     * Creates or changes a token connection and starts a collection. The token goes to OpenBao, never to the
     * database. A new token is needed for the first connection, for another server and after an App connection.
     */
    @Transactional
    fun connect(input: ConnectInput): ConnectionView {
        val valid = input.validate()
        val target = ConnectionEntity.Target(valid.host, valid.baseUrl, valid.account, valid.repositories, AuthMethod.TOKEN)
        val existing = connections.findFirstBy()
        val keepsToken = existing != null && existing.auth == AuthMethod.TOKEN && existing.sameServer(target)
        if (valid.token == null && !keepsToken) throw TokenRequired()
        val secret = valid.token?.let { mapOf("token" to it) }
        return save(existing, target, secret, mapOf("host" to valid.host.id, "selfManaged" to (valid.baseUrl != null),
            "tokenChanged" to (valid.token != null)))
    }

    /**
     * Connects through the aLinha GitHub App, once [GitHubAppService] checked that the person who installed it can see
     * [installationId]. The account is the one GitHub reports for the installation; the repositories are the ones the
     * client chose on GitHub.
     */
    @Transactional
    fun connectApp(installationId: Long, account: String): ConnectionView {
        if (!Names.isAccount(Host.GITHUB, account)) throw InstallationNotVerified()
        val target = ConnectionEntity.Target(Host.GITHUB, null, account, emptyList(), AuthMethod.APP, installationId)
        val secret = mapOf("auth" to AuthMethod.APP.id, "installationId" to installationId.toString())
        return save(connections.findFirstBy(), target, secret, mapOf("host" to Host.GITHUB.id, "auth" to AuthMethod.APP.id))
    }

    @Transactional
    fun collectNow(): ConnectionView {
        val connection = connections.findFirstBy() ?: throw NotConnected()
        val now = Instant.now(clock)
        if (connection.isCollecting(now, COLLECTION_DEADLINE)) throw CollectionRunning()
        audit.record("code-repository.collection-requested", ENTITY_TYPE, connection.id.toString())
        startCollection(connection, now, scheduled = false)
        return connection.toView()
    }

    /**
     * The daily collection, run by [CollectionScheduler] inside `runAs`. Skips a tenant whose collection is still
     * running, and forgets the schedule of one that is no longer connected.
     */
    @Transactional
    fun scheduledCollection() {
        val connection = connections.findFirstBy()
        if (connection == null) {
            schedules.findById(tenants.current().value).ifPresent(schedules::delete)
            return
        }
        val now = Instant.now(clock)
        if (connection.isCollecting(now, COLLECTION_DEADLINE)) {
            // Someone started one by hand: try again soon instead of a day later (the 26-hour window).
            schedules.findById(tenants.current().value).ifPresent { it.postpone(now.plus(SCHEDULE_RETRY)) }
            return
        }
        audit.record("code-repository.collection-scheduled", ENTITY_TYPE, connection.id.toString())
        startCollection(connection, now, scheduled = true)
    }

    /** Removes the connection, its schedule and the stored credential. Evidence already collected stays. */
    @Transactional
    fun disconnect() {
        val connection = connections.findFirstBy() ?: throw NotConnected()
        connections.delete(connection)
        schedules.findById(tenants.current().value).ifPresent(schedules::delete)
        audit.record("code-repository.disconnected", ENTITY_TYPE, connection.id.toString())
        clearAssignment()
        secrets().delete(CodeRepositoryPlugin.ID)
    }

    private fun save(
        existing: ConnectionEntity?,
        target: ConnectionEntity.Target,
        secret: Map<String, String>?,
        details: Map<String, Any>,
    ): ConnectionView {
        val now = Instant.now(clock)
        if (existing?.isCollecting(now, COLLECTION_DEADLINE) == true) throw CollectionRunning()
        if (secret != null) secrets() // fail before writing anything when OpenBao is not configured

        // save() may merge (assigned id) and return another instance: keep working on the managed one.
        val connection = existing?.apply { reconfigure(target, now) }
            ?: connections.save(ConnectionEntity(UUID.randomUUID(), target, now))
        audit.record(
            if (existing == null) "code-repository.connected" else "code-repository.reconfigured",
            ENTITY_TYPE,
            connection.id.toString(),
            details + mapOf("account" to target.account, "repositories" to target.repositories.size),
        )
        // Inside a request: only ever the current tenant's row (CollectionScheduleEntity).
        clearAssignment() // the request is done
        val tenantId = tenants.current().value
        if (!schedules.existsById(tenantId)) schedules.save(CollectionScheduleEntity(tenantId, now.plus(SCHEDULE_INTERVAL)))
        startCollection(connection, now, scheduled = false)
        // A concurrent connect fails here (unique tenant row, @Version), before it can overwrite the stored credential.
        connections.flush()
        // Last: if OpenBao refuses, the exception rolls the connection, the audit and the job back.
        secret?.let { secrets().put(CodeRepositoryPlugin.ID, it) }
        return connection.toView()
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
        // Continuously verified: the daily schedule collected it, and the previous collection succeeded within a day.
        val previous = connection.lastCollectedAt
        val strength = if (connection.collectingScheduled && previous != null &&
            !previous.plus(CONTINUOUS_WINDOW).isBefore(now)
        ) {
            EvidenceStrength.CONTINUOUSLY_VERIFIED
        } else {
            EvidenceStrength.TECHNICALLY_VERIFIED
        }
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
                    strength = strength,
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
                "strength" to strength.name,
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

    private fun clearAssignment() {
        val assignment = assignments.findFirstBy()?.takeIf { it.isAssigned } ?: return
        assignment.unassign() // keeps the time of the last invite (AssignmentService's limit)
        audit.record("code-repository.unassigned", "code_repository_assignment", assignment.id.toString())
    }

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

    private fun startCollection(connection: ConnectionEntity, now: Instant, scheduled: Boolean) {
        val scope = buildMap {
            put("host", connection.host.id)
            connection.baseUrl?.let { put("baseUrl", it) }
            put("account", connection.account)
            put("repositories", connection.repositories)
        }
        val jobId = jobs.dispatch(
            JobRequest(
                type = JOB_TYPE,
                payload = mapOf(
                    "provider" to CodeRepositoryPlugin.ID,
                    "connectionRef" to CONNECTION_REF,
                    "scope" to scope,
                ),
                requestedBy = CodeRepositoryPlugin.ID,
            ),
        )
        connection.collecting(jobId, now, scheduled)
    }

    private fun ConnectionEntity.toView() = ConnectionView(
        host = host.id,
        baseUrl = baseUrl,
        auth = auth.id,
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

        /** How often the schedule collects each connection. */
        val SCHEDULE_INTERVAL: Duration = Duration.ofHours(24)

        /** When the daily collection found one already running. */
        val SCHEDULE_RETRY: Duration = Duration.ofMinutes(15)

        /** A scheduled collection whose previous success is at most this old is CONTINUOUSLY_VERIFIED. */
        val CONTINUOUS_WINDOW: Duration = Duration.ofHours(26)

        /**
         * A collection still PENDING after this is treated as lost (a result that never came back, alinha#86), so a
         * new one may start. Well above the worker's deadline (WORKER_COLLECTOR_TIMEOUT_S × retries + backoff).
         */
        val COLLECTION_DEADLINE: Duration = Duration.ofMinutes(15)
    }
}
