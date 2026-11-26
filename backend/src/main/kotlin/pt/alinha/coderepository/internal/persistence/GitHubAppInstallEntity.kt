package pt.alinha.coderepository.internal.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
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

    /**
     * Uses the attempt up in one statement: 1 when this call deleted it, 0 when it is gone, expired or another call
     * claimed it first (the row lock makes a concurrent delete wait, then find nothing).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from GitHubAppInstallEntity i where i.id = :id and i.stateHash = :stateHash and i.expiresAt > :now")
    fun claim(id: UUID, stateHash: String, now: Instant): Int
}
