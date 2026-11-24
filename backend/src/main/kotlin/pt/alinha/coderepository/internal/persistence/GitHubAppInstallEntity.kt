package pt.alinha.coderepository.internal.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.springframework.data.jpa.repository.JpaRepository
import pt.alinha.core.tenant.TenantOwnedEntity
import java.time.Instant
import java.util.UUID

/** A GitHub App installation this tenant started: only the SHA-256 of the state sent to GitHub, and its expiry. */
@Entity
@Table(schema = "coderepository", name = "github_app_install")
class GitHubAppInstallEntity(
    @Id val id: UUID,
    stateHash: String,
    expiresAt: Instant,
) : TenantOwnedEntity() {
    @Version
    var version: Long = 0
        protected set

    @Column(name = "state_hash", nullable = false)
    var stateHash: String = stateHash
        protected set

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant = expiresAt
        protected set

    /** A new installation attempt replaces the previous one (one row per tenant). */
    fun renew(stateHash: String, expiresAt: Instant) {
        this.stateHash = stateHash
        this.expiresAt = expiresAt
    }
}

interface GitHubAppInstallRepository : JpaRepository<GitHubAppInstallEntity, UUID> {
    fun findFirstBy(): GitHubAppInstallEntity?
}
