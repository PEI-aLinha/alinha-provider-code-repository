package pt.alinha.coderepository.internal

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.core.task.TaskExecutor
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.testcontainers.postgresql.PostgreSQLContainer
import pt.alinha.coderepository.CodeRepositoryPlugin
import pt.alinha.coderepository.internal.github.GitHubAppService
import pt.alinha.coderepository.internal.github.GitHubAppSettings
import pt.alinha.coderepository.internal.github.GitHubInstallationVerifier
import pt.alinha.coderepository.internal.persistence.ConnectionRepository
import pt.alinha.coderepository.internal.web.CodeRepositoryController
import pt.alinha.coderepository.internal.web.CodeRepositoryProblems
import pt.alinha.core.access.internal.AccessWebConfig
import pt.alinha.core.access.internal.RequiresPermissionInterceptor
import pt.alinha.core.access.internal.RolePermissionRegistry
import pt.alinha.core.access.internal.SecurityConfig
import pt.alinha.core.audit.internal.AuditActor
import pt.alinha.core.audit.internal.JpaAuditTrail
import pt.alinha.core.jobs.JobDispatcher
import pt.alinha.core.jobs.JobRequest
import pt.alinha.core.jobs.JobResult
import pt.alinha.core.jobs.JobResults
import pt.alinha.core.registry.PluginRegistry
import pt.alinha.core.tenant.TenantContext
import pt.alinha.core.tenant.internal.CorrelationIdFilter
import pt.alinha.core.tenant.internal.HibernateTenantResolver
import pt.alinha.core.tenant.internal.JwtTenantContext
import pt.alinha.core.tenant.internal.ScopedCorrelationContext
import pt.alinha.core.tenant.internal.TenantResolutionInterceptor
import pt.alinha.core.tenant.internal.TenantWebConfig
import pt.alinha.notifications.api.EmailService
import pt.alinha.pluginapi.TenantId
import pt.alinha.pluginapi.TenantSecretStore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** OpenBao stand-in, keyed like the real store: (tenant from TenantContext, provider). */
class FakeSecretStore(private val tenants: TenantContext) : TenantSecretStore {
    val stored = ConcurrentHashMap<Pair<TenantId, String>, Map<String, String>>()
    @Volatile var failOnPut = false

    override fun put(provider: String, secret: Map<String, String>) {
        if (failOnPut) error("OpenBao refused")
        stored[tenants.current() to provider] = secret
    }

    override fun get(provider: String) = stored[tenants.current() to provider]

    override fun delete(provider: String) {
        stored.remove(tenants.current() to provider)
    }
}

/** Records jobs instead of writing the outbox; like the real one, refuses to run outside a transaction. */
class FakeJobDispatcher(private val tenants: TenantContext) : JobDispatcher {
    data class Dispatched(val id: UUID, val tenant: TenantId, val request: JobRequest)

    val dispatched: MutableList<Dispatched> = java.util.Collections.synchronizedList(mutableListOf())

    override fun dispatch(request: JobRequest): UUID {
        check(TransactionSynchronizationManager.isActualTransactionActive()) { "dispatch needs a transaction" }
        return UUID.randomUUID().also { dispatched += Dispatched(it, tenants.current(), request) }
    }
}

/** Results the test puts in, per (tenant, job), like the real tenant-filtered store. */
class FakeJobResults(private val tenants: TenantContext) : JobResults {
    val results = ConcurrentHashMap<Pair<TenantId, UUID>, JobResult>()

    override fun find(jobId: UUID): JobResult? = results[tenants.current() to jobId]
}

/** Records emails instead of queueing them; like the real one, needs a transaction and refuses a bad address. */
class FakeEmailService(private val tenants: TenantContext) : EmailService {
    data class Sent(val tenant: TenantId, val to: String, val template: String, val params: Map<String, String>)

    val sent: MutableList<Sent> = java.util.Collections.synchronizedList(mutableListOf())

    override fun send(to: String, template: String, params: Map<String, String>) {
        check(TransactionSynchronizationManager.isActualTransactionActive()) { "send needs a transaction" }
        require('@' in to) { "Invalid email address" }
        sent += Sent(tenants.current(), to, template, params)
    }
}

/** GitHub stand-in: [installations] maps (installation id, code) to the account the user can see it on. */
class FakeInstallationVerifier : GitHubInstallationVerifier {
    val installations = ConcurrentHashMap<Pair<Long, String>, String>()
    @Volatile var down = false

    override fun accountOf(installationId: Long, code: String): String {
        if (down) throw GitHubUnavailable()
        return installations[installationId to code] ?: throw InstallationNotVerified()
    }
}

/**
 * The plugin with the core's real RBAC, tenant resolution and audit trail on PostgreSQL. OpenBao, the outbox and
 * the job results are fakes. Token value: `<role>.<sub>.<tenant>`; a consultant's tenant goes in `tenants`.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan(basePackages = ["pt.alinha.coderepository", "pt.alinha.core.audit"])
@EnableJpaRepositories(basePackageClasses = [ConnectionRepository::class])
@Import(
    SecurityConfig::class, AccessWebConfig::class, RequiresPermissionInterceptor::class,
    RolePermissionRegistry::class, PluginRegistry::class,
    TenantWebConfig::class, TenantResolutionInterceptor::class, JwtTenantContext::class,
    HibernateTenantResolver::class, CorrelationIdFilter::class, ScopedCorrelationContext::class,
    AuditActor::class, JpaAuditTrail::class,
    CodeRepositoryPlugin::class, ConnectionService::class, CollectionResultListener::class, CollectionScheduler::class,
    AssignmentService::class, GitHubAppService::class,
    CodeRepositoryApiImpl::class, CodeRepositoryController::class, CodeRepositoryProblems::class,
)
class CodeRepositoryTestApplication {
    /** @Async listeners run on the caller's thread, so a test sees their effect as soon as the call returns. */
    @Bean(name = ["taskExecutor", "applicationTaskExecutor"])
    fun taskExecutor(): TaskExecutor = SyncTaskExecutor()

    @Bean fun secretStore(tenants: TenantContext) = FakeSecretStore(tenants)

    @Bean fun jobDispatcher(tenants: TenantContext) = FakeJobDispatcher(tenants)

    @Bean fun jobResults(tenants: TenantContext) = FakeJobResults(tenants)

    @Bean fun emailService(tenants: TenantContext) = FakeEmailService(tenants)

    @Bean fun installationVerifier() = FakeInstallationVerifier()

    @Bean fun gitHubAppSettings() = GitHubAppSettings("alinha-test", "Iv1.client", "client-secret")

    @Bean
    @Primary
    fun testJwtDecoder(): JwtDecoder = JwtDecoder { token ->
        val (role, sub, tenant) = (token.split(".") + listOf("", "")).take(3)
        val builder = Jwt.withTokenValue(token).header("alg", "none").subject(sub).claim("email_verified", true)
            .claim("realm_access", mapOf("roles" to listOf(role)))
        when {
            tenant.isEmpty() -> Unit
            role == "consultant" -> builder.claim("tenants", listOf(tenant))
            else -> builder.claim("tenant_id", tenant)
        }
        builder.build()
    }
}

object CodeRepositoryDatabase {
    val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17").apply { start() }

    fun register(registry: DynamicPropertyRegistry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl)
        registry.add("spring.datasource.username", postgres::getUsername)
        registry.add("spring.datasource.password", postgres::getPassword)
        registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
        registry.add("spring.jpa.open-in-view") { "false" }
        registry.add("spring.flyway.locations") { "classpath:db/migration" }
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri") { "https://keycloak.test/realms/alinha" }
        registry.add("spring.security.oauth2.resourceserver.jwt.audiences") { "alinha-api" }
        // The tests call CollectionScheduler.runDue() themselves.
        registry.add("alinha.code-repository.schedule.poll-interval-ms") { "86400000" }
        registry.add("ALINHA_PUBLIC_URL") { "https://alinha.test" }
    }
}
