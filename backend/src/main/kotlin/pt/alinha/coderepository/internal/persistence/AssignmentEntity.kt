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

/**
 * The person the responsible asked, by email, to connect the repository. Who asked is in the audit trail. Unassigning
 * clears [email] but keeps the row, so [assignedAt] (the last invite) still limits how often an email can be sent.
 */
@Entity
@Table(schema = "coderepository", name = "assignment")
class AssignmentEntity(
    @Id val id: UUID,
    email: String,
    assignedAt: Instant,
) : TenantOwnedEntity() {
    @Version
    var version: Long = 0
        protected set

    /** Null once unassigned. */
    @Column
    var email: String? = email
        protected set

    /** When the last invite was sent. */
    @Column(name = "assigned_at", nullable = false)
    var assignedAt: Instant = assignedAt
        protected set

    val isAssigned: Boolean get() = email != null

    fun reassign(email: String, at: Instant) {
        this.email = email
        assignedAt = at
    }

    /** Forgets the address (personal data) and keeps when the last invite was sent. */
    fun unassign() {
        email = null
    }
}

interface AssignmentRepository : JpaRepository<AssignmentEntity, UUID> {
    fun findFirstBy(): AssignmentEntity?
}
