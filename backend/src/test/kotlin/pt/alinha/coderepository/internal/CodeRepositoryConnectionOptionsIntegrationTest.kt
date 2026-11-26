package pt.alinha.coderepository.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.web.context.request.RequestContextHolder
import pt.alinha.core.jobs.JobCompleted
import pt.alinha.core.jobs.JobResult
import pt.alinha.core.jobs.JobStatus
import pt.alinha.pluginapi.EvidenceCollected
import pt.alinha.pluginapi.EvidenceStrength
import pt.alinha.pluginapi.TenantId
import java.time.Instant
import java.util.UUID

private const val BASE = "/api/code-repository"

/** GitLab, the daily schedule, the GitHub App and the email invite, against the real core on PostgreSQL. */
@SpringBootTest(classes = [CodeRepositoryTestApplication::class])
@AutoConfigureMockMvc
@RecordApplicationEvents
class CodeRepositoryConnectionOptionsIntegrationTest {
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
    @Autowired private lateinit var emails: FakeEmailService
    @Autowired private lateinit var verifier: FakeInstallationVerifier
    @Autowired private lateinit var listener: CollectionResultListener
    @Autowired private lateinit var scheduler: CollectionScheduler
    @Autowired private lateinit var events: ApplicationEvents

    private lateinit var tenant: String

    @BeforeEach
    fun newTenant() {
        tenant = UUID.randomUUID().toString()
        verifier.down = false
        verifier.whileAsking = null
    }

    // -- GitLab -------------------------------------------------------------------------------------------------

    @Test
    fun `a self-managed GitLab group is collected at its own address`() {
        connect(host = "gitlab", account = "acme/platform", repositories = listOf("acme/platform/web"),
            baseUrl = "https://Git.Acme.pt/").andExpect {
            status { isAccepted() }
            jsonPath("$.host") { value("gitlab") }
            jsonPath("$.baseUrl") { value("https://git.acme.pt") }
            jsonPath("$.auth") { value("token") }
        }

        assertThat(lastJob().request.payload["scope"]).isEqualTo(
            mapOf("host" to "gitlab", "baseUrl" to "https://git.acme.pt", "account" to "acme/platform",
                "repositories" to listOf("acme/platform/web")),
        )
    }

    @Test
    fun `another server needs a new token`() {
        connect()

        connect(host = "gitlab", token = null).andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.errors[0].field") { value("token") }
        }
    }

    // -- daily schedule -----------------------------------------------------------------------------------------

    @Test
    fun `the schedule collects again a day later and that evidence is continuously verified`() {
        connect()
        complete(lastJob().id, result())
        assertThat(strengths()).containsOnly(EvidenceStrength.TECHNICALLY_VERIFIED)

        makeDue()
        outsideRequest { scheduler.runDue() }

        val scheduled = lastJob()
        assertThat(audit("code-repository.collection-scheduled")).hasSize(1)
        assertThat(nextRunAt()).isAfter(Instant.now().plusSeconds(23 * 3600))
        complete(scheduled.id, result())
        assertThat(strengths().last()).isEqualTo(EvidenceStrength.CONTINUOUSLY_VERIFIED)
        call("responsible") { mvc.get("$BASE/evidence") { it() } }.andExpect {
            jsonPath("$[0].strength") { value("CONTINUOUSLY_VERIFIED") }
        }
    }

    @Test
    fun `a scheduled collection after a long gap is only technically verified`() {
        connect()
        complete(lastJob().id, result())
        jdbc.update(
            "update coderepository.connection set last_collected_at = now() - interval '3 days' where tenant_id = ?::uuid",
            tenant,
        )
        makeDue()
        outsideRequest { scheduler.runDue() }

        complete(lastJob().id, result())

        assertThat(strengths().last()).isEqualTo(EvidenceStrength.TECHNICALLY_VERIFIED)
    }

    @Test
    fun `the schedule tries again soon when a collection is running`() {
        connect()
        val running = lastJob()
        makeDue()

        outsideRequest { scheduler.runDue() }

        assertThat(lastJob()).isEqualTo(running)
        assertThat(nextRunAt()).isBetween(Instant.now().plusSeconds(10 * 60), Instant.now().plusSeconds(20 * 60))
    }

    @Test
    fun `a schedule left without a connection is forgotten, and disconnecting removes it`() {
        val orphan = UUID.randomUUID()
        jdbc.update("insert into coderepository.collection_schedule (tenant_id, next_run_at) values (?, now() - interval '1 minute')", orphan)
        outsideRequest { scheduler.runDue() }
        assertThat(scheduleCount(orphan.toString())).isZero()

        connect()
        assertThat(scheduleCount(tenant)).isOne()
        complete(lastJob().id, result())
        call("responsible") { mvc.delete("$BASE/connection") { it() } }.andExpect { status { isNoContent() } }
        assertThat(scheduleCount(tenant)).isZero()
    }

    // -- GitHub App ---------------------------------------------------------------------------------------------

    @Test
    fun `installing the App connects the account GitHub confirms, without a token`() {
        val state = startInstall()
        verifier.installations[42L to "code-1"] = "acme"

        completeInstall(42, state, "code-1").andExpect {
            status { isAccepted() }
            jsonPath("$.auth") { value("app") }
            jsonPath("$.account") { value("acme") }
            jsonPath("$.repositories.length()") { value(0) }
        }

        assertThat(secrets.stored[tenantId() to "code-repository"]).isEqualTo(mapOf("auth" to "app", "installationId" to "42"))
        assertThat(lastJob().request.payload["scope"]).isEqualTo(
            mapOf("host" to "github", "account" to "acme", "repositories" to emptyList<String>()),
        )
        assertThat(audit("code-repository.connected")).singleElement().asString().contains("\"auth\": \"app\"")
        assertThat(rowsMentioning(state)).isZero()
    }

    @Test
    fun `the state works once, only for the tenant that started, and only with GitHub's confirmation`() {
        val state = startInstall()
        verifier.installations[42L to "code-1"] = "acme"
        val mine = tenant

        tenant = UUID.randomUUID().toString()
        startInstall()
        completeInstall(42, state, "code-1").andExpect { jsonPath("$.code") { value("APP_INSTALL_EXPIRED") } }

        tenant = mine
        completeInstall(42, state, "forged").andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.code") { value("APP_INSTALL_NOT_VERIFIED") }
        }
        // GitHub is only asked about installations made or changed since this attempt started.
        assertThat(verifier.lastSince).isBetween(Instant.now().minusSeconds(60), Instant.now())
        completeInstall(42, state, "code-1").andExpect { jsonPath("$.code") { value("APP_INSTALL_EXPIRED") } }
        call("responsible") { mvc.get("$BASE/connection") { it() } }.andExpect { status { isNoContent() } }
        assertThat(audit("code-repository.app-install-refused")).hasSize(1)
    }

    @Test
    fun `a return replayed while the first is still with GitHub is refused, and only one connects`() {
        val state = startInstall()
        verifier.installations[42L to "code-1"] = "acme"
        var replay = ""
        verifier.whileAsking = {
            // Another thread: like a second request, it waits on nothing the first one holds.
            val second = Thread { replay = completeInstall(42, state, "code-1").andReturn().response.contentAsString }
            second.start()
            second.join(10_000)
        }

        completeInstall(42, state, "code-1").andExpect { status { isAccepted() } }

        assertThat(replay).contains("APP_INSTALL_EXPIRED")
        assertThat(audit("code-repository.connected")).hasSize(1)
        assertThat(jobs.dispatched.count { it.tenant == tenantId() }).isOne()
    }

    @Test
    fun `a wrong state is refused and audited`() {
        startInstall()

        completeInstall(42, "x".repeat(43), "code-1").andExpect { jsonPath("$.code") { value("APP_INSTALL_EXPIRED") } }

        assertThat(audit("code-repository.app-install-refused")).hasSize(1)
    }

    @Test
    fun `GitHub not answering is a 503 and keeps the attempt open`() {
        val state = startInstall()
        verifier.down = true

        completeInstall(42, state, "code-1").andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.code") { value("GITHUB_UNAVAILABLE") }
        }

        verifier.down = false
        verifier.installations[42L to "code-2"] = "acme"
        completeInstall(42, state, "code-2").andExpect { status { isAccepted() } }
    }

    @Test
    fun `a collection running keeps the attempt open`() {
        connect()
        val state = startInstall()
        verifier.installations[42L to "c"] = "acme"

        completeInstall(42, state, "c").andExpect { status { isConflict() } }

        complete(lastJob().id, result())
        completeInstall(42, state, "c").andExpect { status { isAccepted() } }
    }

    @Test
    fun `going back to a token after the App needs a token`() {
        val state = startInstall()
        verifier.installations[7L to "c"] = "acme"
        completeInstall(7, state, "c")
        complete(lastJob().id, result())

        connect(token = null).andExpect { jsonPath("$.errors[0].field") { value("token") } }
        connect().andExpect { jsonPath("$.auth") { value("token") } }
        assertThat(secrets.stored[tenantId() to "code-repository"]).isEqualTo(mapOf("token" to "glpat_or_ghp"))
    }

    // -- email invite -------------------------------------------------------------------------------------------

    @Test
    fun `the responsible person asks someone by email, and the page shows it`() {
        call("responsible") { assign("Ana@Acme.pt", it) }.andExpect {
            status { isOk() }
            jsonPath("$.email") { value("ana@acme.pt") }
        }

        assertThat(emails.sent.last { it.tenant == tenantId() }).isEqualTo(
            FakeEmailService.Sent(tenantId(), "ana@acme.pt", "code-repository-assigned", mapOf("link" to "https://alinha.test/")),
        )
        call("analyst") { mvc.get("$BASE/setup") { it() } }.andExpect {
            jsonPath("$.githubApp") { value(true) }
            jsonPath("$.assignment.email") { value("ana@acme.pt") }
        }
        assertThat(audit("code-repository.assigned")).singleElement().asString().doesNotContain("ana@")

        call("responsible") { mvc.delete("$BASE/assignment") { it() } }.andExpect { status { isNoContent() } }
        call("responsible") { mvc.get("$BASE/setup") { it() } }.andExpect { jsonPath("$.assignment") { doesNotExist() } }
    }

    @Test
    fun `one invite a minute per organisation, and connecting ends the request`() {
        call("responsible") { assign("ana@acme.pt", it) }.andExpect { status { isOk() } }
        call("responsible") { assign("rui@acme.pt", it) }.andExpect {
            status { isTooManyRequests() }
            header { string("Retry-After", "60") }
        }
        assertThat(emails.sent.filter { it.tenant == tenantId() }).hasSize(1)

        connect()
        call("responsible") { mvc.get("$BASE/setup") { it() } }.andExpect { jsonPath("$.assignment") { doesNotExist() } }
        assertThat(audit("code-repository.unassigned")).hasSize(1)
    }

    @Test
    fun `an address that could add recipients is refused`() {
        call("responsible") { assign("ana,rui@acme.pt", it) }.andExpect { status { isUnprocessableContent() } }
    }

    @Test
    fun `only the responsible person and the consultant can assign, with a valid address`() {
        call("analyst") { assign("ana@acme.pt", it) }.andExpect { status { isForbidden() } }
        call("responsible") { assign("not an email", it) }.andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.errors[0].field") { value("email") }
        }
        call("consultant") { assign("ana@acme.pt", it) }.andExpect { status { isOk() } }
    }

    // -- helpers ------------------------------------------------------------------------------------------------

    private fun call(role: String, request: ((MockHttpServletRequestDsl.() -> Unit)) -> ResultActionsDsl) =
        request {
            header("Authorization", "Bearer $role.${UUID.randomUUID()}.$tenant")
            if (role == "consultant") header("X-Alinha-Tenant", tenant)
        }

    private fun connect(
        host: String = "github",
        account: String = "acme",
        repositories: List<String> = listOf("acme/api"),
        token: String? = "glpat_or_ghp",
        baseUrl: String? = null,
    ): ResultActionsDsl = call("responsible") { auth ->
        mvc.put("$BASE/connection") {
            auth()
            contentType = MediaType.APPLICATION_JSON
            content = """{"host": "$host", "account": "$account",
                "repositories": [${repositories.joinToString { "\"$it\"" }}]
                ${token?.let { ", \"token\": \"$it\"" } ?: ""}
                ${baseUrl?.let { ", \"baseUrl\": \"$it\"" } ?: ""}}"""
        }
    }

    private fun assign(email: String, auth: MockHttpServletRequestDsl.() -> Unit) = mvc.put("$BASE/assignment") {
        auth()
        contentType = MediaType.APPLICATION_JSON
        content = """{"email": "$email"}"""
    }

    private fun startInstall(): String {
        val body = call("responsible") { mvc.post("$BASE/github-app/installations") { it() } }
            .andExpect { status { isCreated() } }
            .andReturn().response.contentAsString
        assertThat(body).contains("https://github.com/apps/alinha-test/installations/new?state=")
        return Regex("state=([A-Za-z0-9_-]+)").find(body)!!.groupValues[1]
    }

    private fun completeInstall(installationId: Long, state: String, code: String) = call("responsible") { auth ->
        mvc.put("$BASE/github-app/installation") {
            auth()
            contentType = MediaType.APPLICATION_JSON
            content = """{"installationId": $installationId, "state": "$state", "code": "$code"}"""
        }
    }

    private fun lastJob() = jobs.dispatched.last { it.tenant.value.toString() == tenant }

    private fun tenantId() = TenantId(UUID.fromString(tenant))

    private fun result(): Map<String, Any?> = mapOf(
        "schema" to "code-repository.result.v1", "host" to "github", "account" to "acme", "truncated" to false,
        "repositories" to listOf(
            mapOf("repository" to "acme/api", "defaultBranch" to "main", "checks" to listOf(
                mapOf("check" to "branch_protection", "outcome" to "PASS", "details" to emptyMap<String, Any>()),
            )),
        ),
    )

    private fun complete(jobId: UUID, result: Map<String, Any?>) {
        results.results[tenantId() to jobId] = JobResult(jobId, "evidence.collect", JobStatus.COMPLETED, 1, result, null, Instant.now())
        outsideRequest {
            listener.on(JobCompleted(tenantId(), UUID.randomUUID(), Instant.now(), jobId, "evidence.collect", "code-repository", 1))
        }
    }

    /** The listener and the scheduler run off any request; MockMvc leaves the last request bound to this thread. */
    private fun <T> outsideRequest(block: () -> T): T {
        RequestContextHolder.resetRequestAttributes()
        return block()
    }

    private fun strengths() = events.stream(EvidenceCollected::class.java)
        .filter { it.tenantId == tenantId() }.map { it.strength }.toList()

    private fun makeDue() = jdbc.update(
        "update coderepository.collection_schedule set next_run_at = now() - interval '1 minute' where tenant_id = ?::uuid",
        tenant,
    )

    private fun nextRunAt(): Instant = jdbc.queryForObject(
        "select next_run_at from coderepository.collection_schedule where tenant_id = ?::uuid", java.sql.Timestamp::class.java, tenant,
    )!!.toInstant()

    private fun scheduleCount(tenant: String): Int = jdbc.queryForObject(
        "select count(*) from coderepository.collection_schedule where tenant_id = ?::uuid", Int::class.java, tenant,
    )!!

    private fun audit(action: String): List<String> = jdbc.queryForList(
        "select details::text from audit.audit_event where tenant_id = ?::uuid and action = ?", String::class.java, tenant, action,
    )

    private fun rowsMentioning(text: String): Int = jdbc.queryForObject(
        """select (select count(*) from coderepository.github_app_install i where i::text like ?)
                + (select count(*) from audit.audit_event a where a::text like ?)""",
        Int::class.java, "%$text%", "%$text%",
    )!!
}
