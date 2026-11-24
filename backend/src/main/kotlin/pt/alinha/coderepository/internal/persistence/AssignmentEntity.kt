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

/** The person the responsible asked, by email, to connect the repository. Who asked is in the audit trail. */
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

    @Column(nullable = false)
    var email: String = email
        protected set

    @Column(name = "assigned_at", nullable = false)
    var assignedAt: Instant = assignedAt
        protected set

    fun reassign(email: String, at: Instant) {
        this.email = email
        assignedAt = at
    }
}

interface AssignmentRepository : JpaRepository<AssignmentEntity, UUID> {
    fun findFirstBy(): AssignmentEntity?
}
