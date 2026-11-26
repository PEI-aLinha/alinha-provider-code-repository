package pt.alinha.coderepository.internal

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.support.TransactionOperations
import pt.alinha.coderepository.CodeRepositoryPlugin
import pt.alinha.core.jobs.JobCompleted
import pt.alinha.core.jobs.JobFailed
import pt.alinha.core.jobs.JobResults
import pt.alinha.core.jobs.JobStatus
import pt.alinha.core.tenant.TenantContext

/**
 * Applies the outcome of this plugin's `evidence.collect` jobs (docs/jobs.md, "Reacting to the result").
 * Idempotent: a replayed event finds the evidence already stored, or a job that is no longer current, and does nothing.
 */
private val log = LoggerFactory.getLogger(CollectionResultListener::class.java)

@Component
class CollectionResultListener(
    private val service: ConnectionService,
    private val results: JobResults,
    private val tenants: TenantContext,
    private val tx: TransactionOperations,
) {

    @Async
    @TransactionalEventListener
    fun on(event: JobCompleted) {
        if (!isOurs(event.requestedBy, event.type)) return
        // No request tenant here: runAs first, then the transaction (docs/tenancy.md, rule 4).
        try {
            tenants.runAs(event) {
                tx.executeWithoutResult {
                    val job = results.find(event.jobId) ?: return@executeWithoutResult
                    if (job.status == JobStatus.COMPLETED) service.applyResult(event.jobId, job.result)
                }
            }
        } catch (e: RuntimeException) {
            // An async listener's exception is only logged by the executor: mark the collection failed instead of
            // leaving it PENDING. The log has the job id, never the result (untrusted, may be large).
            log.error("could not apply the result of job {}", event.jobId, e)
            markFailed(event)
        }
    }

    @Async
    @TransactionalEventListener
    fun on(event: JobFailed) {
        if (!isOurs(event.requestedBy, event.type)) return
        tenants.runAs(event) {
            tx.executeWithoutResult { service.applyFailure(event.jobId, event.error.code) }
        }
    }

    /**
     * The fallback can fail too (the database is down, a concurrent connect changed the row): log it and stop. The
     * collection then stays PENDING until COLLECTION_DEADLINE, after which a new one may start.
     */
    private fun markFailed(event: JobCompleted) {
        try {
            tenants.runAs(event) { tx.executeWithoutResult { service.applyFailure(event.jobId, ConnectionService.GENERIC_ERROR) } }
        } catch (e: RuntimeException) {
            log.error("could not mark job {} as failed either", event.jobId, e)
        }
    }

    private fun isOurs(requestedBy: String, type: String) =
        requestedBy == CodeRepositoryPlugin.ID && type == ConnectionService.JOB_TYPE
}
