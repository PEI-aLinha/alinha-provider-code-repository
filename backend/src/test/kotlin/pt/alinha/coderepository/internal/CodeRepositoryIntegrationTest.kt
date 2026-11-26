package pt.alinha.coderepository.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.web.context.request.RequestContextHolder
import pt.alinha.coderepository.api.CodeRepositoryApi
import pt.alinha.core.jobs.JobCompleted
import pt.alinha.core.jobs.JobError
import pt.alinha.core.jobs.JobFailed
import pt.alinha.core.jobs.JobResult
import pt.alinha.core.jobs.JobStatus
import pt.alinha.core.tenant.TenantContext
import pt.alinha.pluginapi.TenantId
import pt.alinha.pluginapi.EvidenceCollected
import pt.alinha.pluginapi.EvidenceOutcome
import pt.alinha.pluginapi.EvidenceStrength
import java.time.Instant
import java.util.UUID

private const val CONNECTION = "/api/code-repository/connection"
private const val COLLECTIONS = "/api/code-repository/collections"
private const val EVIDENCE = "/api/code-repository/evidence"
private const val TOKEN = "github_pat_never_in_the_database"

@SpringBootTest(classes = [CodeRepositoryTestApplication::class])
@AutoConfigureMockMvc
@RecordApplicationEvents
class CodeRepositoryIntegrationTest {
    companion object {
        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) = CodeRepositoryDatabase.register(registry)
    }

    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var secrets: FakeSecretStore
    @Autowired private lateinit var jobs: FakeJobDispatcher
    @Autowired private lateinit var results: FakeJobResults
    @Autowired private lateinit var listener: CollectionResultListener
    @Autowired private lateinit var api: CodeRepositoryApi
    @Autowired private lateinit var tenants: TenantContext
    @Autowired private lateinit var events: ApplicationEvents

    private lateinit var tenant: String

    @BeforeEach
    fun newTenant() {
        tenant = UUID.randomUUID().toString()
        secrets.failOnPut = false
    }

    // -- connect ------------------------------------------------------------------------------------------------

    @Test
    fun `nothing is connected at first`() {
        call("responsible") { mvc.get(CONNECTION) { it() } }.andExpect { status { isNoContent() } }
    }

    @Test
    fun `connecting stores the token in OpenBao only and starts a collection`() {
        connect().andExpect {
            status { isAccepted() }
            jsonPath("$.status") { value("PENDING") }
            jsonPath("$.account") { value("acme") }
            jsonPath("$.token") { doesNotExist() }
        }

        assertThat(secrets.stored[TenantId(UUID.fromString(tenant)) to "code-repository"]).isEqualTo(mapOf("token" to TOKEN))
        val job = lastJob()
        assertThat(job.tenant.value.toString()).isEqualTo(tenant)
        assertThat(job.request.type).isEqualTo("evidence.collect")
        assertThat(job.request.requestedBy).isEqualTo("code-repository")
        assertThat(job.request.payload).isEqualTo(
            mapOf(
                "provider" to "code-repository",
                "connectionRef" to "providers/code-repository",
                "scope" to mapOf("host" to "github", "account" to "acme", "repositories" to listOf("acme/api")),
            ),
        )
        assertThat(audit("code-repository.connected")).singleElement().asString()
            .contains("\"tokenChanged\": true").doesNotContain(TOKEN)
        assertThat(rowsMentioning(TOKEN)).isZero()
    }

    @Test
    fun `the first connection needs a token`() {
        connect(token = null).andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.errors[0].field") { value("token") }
        }
        assertThat(jobs.dispatched.filter { it.tenant.value.toString() == tenant }).isEmpty()
    }

    @Test
    fun `invalid input names the field and never echoes the token`() {
        connect(repositories = listOf("other/api")).andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.errors[0].field") { value("repositories") }
            content { string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(TOKEN))) }
        }
    }

    @Test
    fun `when OpenBao refuses the token nothing is stored`() {
        secrets.failOnPut = true

        runCatching { connect() }

        call("responsible") { mvc.get(CONNECTION) { it() } }.andExpect { status { isNoContent() } }
        assertThat(audit("code-repository.connected")).isEmpty()
    }

    @Test
    fun `reconfiguring keeps the stored token when none is sent`() {
        connect()
        complete(lastJob().id, collectionResult())

        connect(token = null, repositories = listOf("acme/web")).andExpect { status { isAccepted() } }

        assertThat(secrets.stored[TenantId(UUID.fromString(tenant)) to "code-repository"]).isEqualTo(mapOf("token" to TOKEN))
        assertThat(audit("code-repository.reconfigured")).singleElement().asString().contains("\"tokenChanged\": false")
    }

    @Test
    fun `a second collection cannot start while one is running`() {
        connect()

        call("responsible") { mvc.post(COLLECTIONS) { it() } }.andExpect { status { isConflict() } }
        connect().andExpect { status { isConflict() } }
    }

    // -- permissions and tenancy --------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = ["responsible", "analyst", "consultant"])
    fun `the responsible person, the analyst and the consultant can connect (US37)`(role: String) {
        connect(role = role).andExpect { status { isAccepted() } }
    }

    @Test
    fun `a role without the plugin's permissions cannot read or connect`() {
        call("registrant") { mvc.get(CONNECTION) { it() } }.andExpect { status { isForbidden() } }
        connect(role = "registrant").andExpect { status { isForbidden() } }
        call("registrant") { mvc.post(COLLECTIONS) { it() } }.andExpect { status { isForbidden() } }
        call("registrant") { mvc.delete(CONNECTION) { it() } }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `another tenant sees nothing and cannot apply this tenant's job`() {
        connect()
        val jobId = lastJob().id
        val mine = tenant

        tenant = UUID.randomUUID().toString()
        call("responsible") { mvc.get(CONNECTION) { it() } }.andExpect { status { isNoContent() } }
        complete(jobId, collectionResult()) // runs as the other tenant: no connection, no result there

        tenant = mine
        call("responsible") { mvc.get(CONNECTION) { it() } }.andExpect { jsonPath("$.status") { value("PENDING") } }
        call("responsible") { mvc.get(EVIDENCE) { it() } }.andExpect { jsonPath("$.length()") { value(0) } }
    }

    // -- results ------------------------------------------------------------------------------------------------

    @Test
    fun `a completed collection stores evidence and announces the PASS and FAIL ones`() {
        connect()
        val jobId = lastJob().id

        complete(jobId, collectionResult())

        call("responsible") { mvc.get(CONNECTION) { it() } }.andExpect {
            jsonPath("$.status") { value("ACTIVE") }
            jsonPath("$.lastCollectedAt") { exists() }
        }
        call("analyst") { mvc.get(EVIDENCE) { it() } }.andExpect {
            jsonPath("$.length()") { value(3) }
            jsonPath("$[0].check") { value("branch_protection") }
            jsonPath("$[0].outcome") { value("PASS") }
            jsonPath("$[0].controlIds[0]") { value("ID.GA-9-E") }
            jsonPath("$[0].strength") { value("TECHNICALLY_VERIFIED") }
            jsonPath("$[1].details.openCriticalOrHigh") { value(2) }
        }
        val announced = announcedFor(tenant)
        assertThat(announced).hasSize(2)
        assertThat(announced.map { it.outcome })
            .containsExactlyInAnyOrder(EvidenceOutcome.SATISFIED, EvidenceOutcome.NOT_SATISFIED)
        assertThat(announced).allSatisfy {
            assertThat(it.provider).isEqualTo("code-repository")
            assertThat(it.strength).isEqualTo(EvidenceStrength.TECHNICALLY_VERIFIED)
            assertThat(it.controlIds).isNotEmpty()
        }
        val first = outsideRequest { tenants.runAs(announced.first().tenantId) { api.evidence(announced.first().evidenceId) } }
        assertThat(first?.outcome).isIn("PASS", "FAIL")
        assertThat(audit("code-repository.evidence-collected")).singleElement().asString()
            .contains("\"evidence\": 3").contains("\"unknown\": 1")
    }

    @Test
    fun `a replayed result changes nothing`() {
        connect()
        val jobId = lastJob().id
        complete(jobId, collectionResult())

        complete(jobId, collectionResult())

        call("responsible") { mvc.get(EVIDENCE) { it() } }.andExpect { jsonPath("$.length()") { value(3) } }
        assertThat(announcedFor(tenant)).hasSize(2)
        assertThat(audit("code-repository.evidence-collected")).hasSize(1)
    }

    @Test
    fun `a replayed invalid result is audited once`() {
        connect()
        val jobId = lastJob().id

        complete(jobId, collectionResult(repository = "evil/api"))
        complete(jobId, collectionResult(repository = "evil/api"))

        assertThat(audit("code-repository.collection-failed")).hasSize(1)
    }

    @Test
    fun `a collection lost for longer than the deadline can be started again`() {
        connect()
        call("responsible") { mvc.post(COLLECTIONS) { it() } }.andExpect { status { isConflict() } }

        jdbc.update(
            "update coderepository.connection set collecting_since = now() - interval '16 minutes' where tenant_id = ?::uuid",
            tenant,
        )

        call("responsible") { mvc.post(COLLECTIONS) { it() } }.andExpect { status { isAccepted() } }
    }

    @Test
    fun `changing the account hides the old account's evidence`() {
        connect()
        complete(lastJob().id, collectionResult())

        connect(token = null, account = "other", repositories = emptyList()).andExpect { status { isAccepted() } }

        call("responsible") { mvc.get(EVIDENCE) { it() } }.andExpect { jsonPath("$.length()") { value(0) } }
    }

    @Test
    fun `changing the repositories hides the previous collection's evidence, even when the new one fails`() {
        connect(repositories = listOf("acme/api", "acme/web"))
        complete(lastJob().id, collectionResult())

        connect(token = null, repositories = listOf("acme/web")).andExpect { status { isAccepted() } }
        call("responsible") { mvc.get(EVIDENCE) { it() } }.andExpect { jsonPath("$.length()") { value(0) } }

        fail(lastJob().id, "invalid_credentials")
        call("responsible") { mvc.get(EVIDENCE) { it() } }.andExpect { jsonPath("$.length()") { value(0) } }
    }

    @Test
    fun `the same repositories in another order or case keep the evidence`() {
        connect(repositories = listOf("acme/api", "acme/web"))
        complete(lastJob().id, collectionResult())

        connect(token = null, repositories = listOf("Acme/Web", "acme/api")).andExpect { status { isAccepted() } }

        call("responsible") { mvc.get(EVIDENCE) { it() } }.andExpect { jsonPath("$.length()") { value(3) } }
    }

    @Test
    fun `another plugin's job is ignored`() {
        connect()
        val jobId = lastJob().id
        put(jobId, collectionResult())

        outsideRequest { listener.on(completed(jobId, requestedBy = "external-surface")) }

        call("responsible") { mvc.get(CONNECTION) { it() } }.andExpect { jsonPath("$.status") { value("PENDING") } }
    }

    @Test
    fun `a malformed result fails the collection without storing anything`() {
        connect()
        val jobId = lastJob().id

        complete(jobId, collectionResult(repository = "evil/api"))

        call("responsible") { mvc.get(CONNECTION) { it() } }.andExpect {
            jsonPath("$.status") { value("FAILED") }
            jsonPath("$.lastError") { value("invalid_result") }
        }
        assertThat(countEvidence()).isZero()
        assertThat(announcedFor(tenant)).isEmpty()
    }

    @Test
    fun `a failed job keeps the worker's code, or a generic one when it is not a plain code`() {
        connect()
        fail(lastJob().id, "invalid_credentials")
        call("responsible") { mvc.get(CONNECTION) { it() } }.andExpect { jsonPath("$.lastError") { value("invalid_credentials") } }

        call("responsible") { mvc.post(COLLECTIONS) { it() } }.andExpect { status { isAccepted() } }
        fail(lastJob().id, "<script>alert(1)</script>")
        call("responsible") { mvc.get(CONNECTION) { it() } }.andExpect { jsonPath("$.lastError") { value("collector_error") } }
        assertThat(audit("code-repository.collection-failed")).hasSize(2)
    }

    @Test
    fun `disconnecting removes the token and keeps the evidence`() {
        connect()
        complete(lastJob().id, collectionResult())

        call("responsible") { mvc.delete(CONNECTION) { it() } }.andExpect { status { isNoContent() } }

        assertThat(secrets.stored).doesNotContainKey(TenantId(UUID.fromString(tenant)) to "code-repository")
        call("responsible") { mvc.get(CONNECTION) { it() } }.andExpect { status { isNoContent() } }
        assertThat(countEvidence()).isEqualTo(3)
        assertThat(audit("code-repository.disconnected")).hasSize(1)
        call("responsible") { mvc.delete(CONNECTION) { it() } }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `a collection that completes after disconnecting is ignored`() {
        connect()
        val running = lastJob().id

        call("responsible") { mvc.delete(CONNECTION) { it() } }.andExpect { status { isNoContent() } }
        complete(running, collectionResult())

        assertThat(countEvidence()).isZero()
        assertThat(announcedFor(tenant)).isEmpty()
        assertThat(audit("code-repository.evidence-collected")).isEmpty()
        call("responsible") { mvc.get(EVIDENCE) { it() } }.andExpect { jsonPath("$.length()") { value(0) } }
        call("responsible") { mvc.get(CONNECTION) { it() } }.andExpect { status { isNoContent() } }
    }

    // -- helpers ------------------------------------------------------------------------------------------------

    private fun call(role: String, request: ((org.springframework.test.web.servlet.MockHttpServletRequestDsl.() -> Unit)) -> ResultActionsDsl) =
        request {
            header("Authorization", "Bearer $role.${UUID.randomUUID()}.$tenant")
            if (role == "consultant") header("X-Alinha-Tenant", tenant)
        }

    private fun connect(
        role: String = "responsible",
        token: String? = TOKEN,
        repositories: List<String> = listOf("acme/api"),
        account: String = "acme",
    ): ResultActionsDsl = call(role) { auth ->
        mvc.put(CONNECTION) {
            auth()
            contentType = MediaType.APPLICATION_JSON
            content = """{"host": "github", "account": "$account",
                "repositories": [${repositories.joinToString { "\"$it\"" }}]
                ${token?.let { ", \"token\": \"$it\"" } ?: ""}}"""
        }
    }

    private fun lastJob() = jobs.dispatched.last { it.tenant.value.toString() == tenant }

    private fun collectionResult(repository: String = "acme/api"): Map<String, Any?> = mapOf(
        "schema" to "code-repository.result.v1",
        "host" to "github",
        "account" to "acme",
        "truncated" to false,
        "repositories" to listOf(
            mapOf(
                "repository" to repository,
                "defaultBranch" to "main",
                "checks" to listOf(
                    mapOf("check" to "branch_protection", "outcome" to "PASS", "details" to mapOf("classic" to true)),
                    mapOf("check" to "dependency_alerts", "outcome" to "FAIL",
                        "details" to mapOf("enabled" to true, "openCriticalOrHigh" to 2)),
                    mapOf("check" to "secret_scanning", "outcome" to "UNKNOWN", "details" to mapOf("reason" to "no_permission")),
                ),
            ),
        ),
    )

    private fun tenantId() = TenantId(UUID.fromString(tenant))

    private fun put(jobId: UUID, result: Map<String, Any?>) {
        results.results[tenantId() to jobId] =
            JobResult(jobId, "evidence.collect", JobStatus.COMPLETED, 1, result, null, Instant.now())
    }

    private fun completed(jobId: UUID, requestedBy: String = "code-repository") =
        JobCompleted(tenantId(), UUID.randomUUID(), Instant.now(), jobId, "evidence.collect", requestedBy, 1)

    private fun complete(jobId: UUID, result: Map<String, Any?>) {
        put(jobId, result)
        outsideRequest { listener.on(completed(jobId)) }
    }

    /** In the app the listener is @Async, off any request; MockMvc leaves the last request bound to this thread. */
    private fun <T> outsideRequest(block: () -> T): T {
        RequestContextHolder.resetRequestAttributes()
        return block()
    }

    private fun fail(jobId: UUID, code: String) = outsideRequest { listener.on(
        JobFailed(tenantId(), UUID.randomUUID(), Instant.now(), jobId, "evidence.collect", "code-repository", 1,
            JobError(code, "untrusted text")),
    ) }

    private fun announcedFor(tenant: String) =
        events.stream(EvidenceCollected::class.java).filter { it.tenantId.value.toString() == tenant }.toList()

    private fun audit(action: String): List<String> = jdbc.queryForList(
        "select details::text from audit.audit_event where tenant_id = ?::uuid and action = ?", String::class.java, tenant, action,
    )

    private fun countEvidence(): Int = jdbc.queryForObject(
        "select count(*) from coderepository.evidence where tenant_id = ?::uuid", Int::class.java, tenant,
    )!!

    private fun rowsMentioning(text: String): Int = jdbc.queryForObject(
        """select (select count(*) from coderepository.connection c where c::text like ?)
                + (select count(*) from coderepository.evidence e where e::text like ?)
                + (select count(*) from audit.audit_event a where a::text like ?)""",
        Int::class.java, "%$text%", "%$text%", "%$text%",
    )!!
}
