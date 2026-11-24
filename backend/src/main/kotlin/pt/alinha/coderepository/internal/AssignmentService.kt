package pt.alinha.coderepository.internal

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pt.alinha.coderepository.internal.github.GitHubAppSettings
import pt.alinha.coderepository.internal.persistence.AssignmentEntity
import pt.alinha.coderepository.internal.persistence.AssignmentRepository
import pt.alinha.core.audit.AuditTrail
import pt.alinha.notifications.api.EmailService
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * The responsible person asks someone (usually the analyst who looks after the code) to connect the repository: the
 * page shows "Atribuído a …" and that person gets an email with a link to the platform. Connecting still needs the
 * `code-repository:connect` permission: the email grants nothing.
 */
@Service
class AssignmentService(
    private val assignments: AssignmentRepository,
    private val emails: EmailService,
    private val audit: AuditTrail,
    private val app: GitHubAppSettings,
    @Value("\${ALINHA_PUBLIC_URL:http://localhost:5173}") private val publicUrl: String,
    private val clock: Clock = Clock.systemUTC(),
) {

    @Transactional(readOnly = true)
    fun setup(): SetupView = SetupView(githubApp = app.available, assignment = assignment())

    @Transactional(readOnly = true)
    fun assignment(): AssignmentView? = assignments.findFirstBy()?.let { AssignmentView(it.email, it.assignedAt) }

    /** Saves who was asked and queues the email in the same transaction (the outbox sends it after commit). */
    @Transactional
    fun assign(input: AssignInput): AssignmentView {
        val email = input.validate()
        val now = Instant.now(clock)
        // save() may merge (assigned id) and return another instance: keep working on the managed one.
        val assignment = assignments.findFirstBy()?.apply { reassign(email, now) }
            ?: assignments.save(AssignmentEntity(UUID.randomUUID(), email, now))
        // The address is personal data: it stays in the assignment row, not in the audit trail.
        audit.record("code-repository.assigned", ENTITY_TYPE, assignment.id.toString())
        try {
            emails.send(email, TEMPLATE, mapOf("link" to publicUrl.trimEnd('/') + "/"))
        } catch (_: IllegalArgumentException) {
            throw InvalidInput("email") // an address the mail library refuses
        }
        return AssignmentView(assignment.email, assignment.assignedAt)
    }

    @Transactional
    fun unassign() {
        val assignment = assignments.findFirstBy() ?: return
        assignments.delete(assignment)
        audit.record("code-repository.unassigned", ENTITY_TYPE, assignment.id.toString())
    }

    private companion object {
        const val ENTITY_TYPE = "code_repository_assignment"
        const val TEMPLATE = "code-repository-assigned"
    }
}
