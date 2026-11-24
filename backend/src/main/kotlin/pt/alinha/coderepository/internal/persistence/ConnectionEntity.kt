package pt.alinha.coderepository.internal.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import pt.alinha.coderepository.internal.domain.AuthMethod
import pt.alinha.coderepository.internal.domain.ConnectionStatus
import pt.alinha.coderepository.internal.domain.Host
import pt.alinha.core.tenant.TenantOwnedEntity
import java.time.Instant
import java.util.UUID

/** The tenant's single connection to a repository host. The credential lives in OpenBao, never here. */
@Entity
@Table(schema = "coderepository", name = "connection")
class ConnectionEntity(
    @Id val id: UUID,
    target: Target,
    connectedAt: Instant,
) : TenantOwnedEntity() {

    /** What is connected and how the worker gets in. [installationId] is set exactly when [auth] is APP. */
    data class Target(
        val host: Host,
        val baseUrl: String?,
        val account: String,
        val repositories: List<String>,
        val auth: AuthMethod,
        val installationId: Long? = null,
    ) {
        init {
            require((auth == AuthMethod.APP) == (installationId != null)) { "an App connection needs its installation" }
            require(auth == AuthMethod.TOKEN || (host == Host.GITHUB && baseUrl == null)) { "the App is for github.com" }
        }
    }

    @Version
    var version: Long = 0
        protected set

    @Column(name = "host", nullable = false)
    private var hostId: String = target.host.id

    val host: Host get() = requireNotNull(Host.of(hostId)) { "unknown host $hostId" }

    @Column(name = "base_url")
    var baseUrl: String? = target.baseUrl
        protected set

    @Column(name = "auth", nullable = false)
    private var authId: String = target.auth.id

    val auth: AuthMethod get() = AuthMethod.entries.first { it.id == authId }

    @Column(name = "installation_id")
    var installationId: Long? = target.installationId
        protected set

    /** When the connection was last configured. Who did it is in the audit trail. */
    @Column(name = "connected_at", nullable = false)
    var connectedAt: Instant = connectedAt
        protected set

    @Column(nullable = false)
    var account: String = target.account
        protected set

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "repositories", columnDefinition = "text[]", nullable = false)
    private var repositoryArray: Array<String> = target.repositories.toTypedArray()

    val repositories: List<String> get() = repositoryArray.toList()

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: ConnectionStatus = ConnectionStatus.PENDING
        protected set

    @Column(name = "last_job_id")
    var lastJobId: UUID? = null
        protected set

    /** When the current collection was queued; a PENDING older than the deadline may be replaced. */
    @Column(name = "collecting_since")
    var collectingSince: Instant? = null
        protected set

    /** The current collection was started by the daily schedule. */
    @Column(name = "collecting_scheduled", nullable = false)
    var collectingScheduled: Boolean = false
        protected set

    /** The job whose evidence is the current picture (the last one that completed). */
    @Column(name = "collected_job_id")
    var collectedJobId: UUID? = null
        protected set

    @Column(name = "last_error")
    var lastError: String? = null
        protected set

    @Column(name = "last_collected_at")
    var lastCollectedAt: Instant? = null
        protected set

    @Column(nullable = false)
    var truncated: Boolean = false
        protected set

    val target: Target get() = Target(host, baseUrl, account, repositories, auth, installationId)

    /** The same host and server: a token stored for one is still meant for the other. */
    fun sameServer(other: Target): Boolean = other.host == host && other.baseUrl == baseUrl

    fun sameAccount(other: Target): Boolean = sameServer(other) && other.account.equals(account, ignoreCase = true)

    fun reconfigure(target: Target, at: Instant) {
        // Evidence of another account is not this connection's picture any more.
        if (!sameAccount(target)) {
            collectedJobId = null
            lastCollectedAt = null
        }
        hostId = target.host.id
        baseUrl = target.baseUrl
        account = target.account
        repositoryArray = target.repositories.toTypedArray()
        authId = target.auth.id
        installationId = target.installationId
        connectedAt = at
    }

    fun collecting(jobId: UUID, at: Instant, scheduled: Boolean) {
        status = ConnectionStatus.PENDING
        collectingSince = at
        collectingScheduled = scheduled
        lastJobId = jobId
        lastError = null
    }

    fun collected(jobId: UUID, at: Instant, truncated: Boolean) {
        status = ConnectionStatus.ACTIVE
        collectedJobId = jobId
        lastCollectedAt = at
        lastError = null
        this.truncated = truncated
    }

    /** A collection is running and is not yet past [deadline]: a new one would race it. */
    fun isCollecting(now: Instant, deadline: java.time.Duration): Boolean =
        status == ConnectionStatus.PENDING && collectingSince?.plus(deadline)?.isAfter(now) == true

    fun failed(errorCode: String) {
        status = ConnectionStatus.FAILED
        lastError = errorCode
    }
}
