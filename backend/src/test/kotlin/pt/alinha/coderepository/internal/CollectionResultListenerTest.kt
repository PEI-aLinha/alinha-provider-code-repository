package pt.alinha.coderepository.internal

import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import pt.alinha.coderepository.CodeRepositoryPlugin
import pt.alinha.core.jobs.JobCompleted
import pt.alinha.core.jobs.JobResult
import pt.alinha.core.jobs.JobResults
import pt.alinha.core.tenant.TenantContext
import pt.alinha.pluginapi.DomainEvent
import pt.alinha.pluginapi.TenantId
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** The listener's error paths, which the integration test cannot reach with a healthy database. */
class CollectionResultListenerTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val event = JobCompleted(
        tenant, UUID.randomUUID(), Instant.now(), UUID.randomUUID(), ConnectionService.JOB_TYPE, CodeRepositoryPlugin.ID, 1,
    )
    private val service = mock(ConnectionService::class.java)
    private val results = object : JobResults {
        override fun find(jobId: UUID): JobResult? = error("not reached: the transaction fails first")
    }

    private val tenants = object : TenantContext {
        override fun current() = tenant
        override fun <T> runAs(tenantId: TenantId, block: () -> T): T = block()
        override fun <T> runAs(event: DomainEvent, block: () -> T): T = block()
    }

    /** Fails the first [failures] transactions (the database is down), then runs the rest without one. */
    private fun transactions(failures: Int) = object : TransactionOperations {
        private val calls = AtomicInteger()

        override fun <T> execute(action: TransactionCallback<T>): T {
            if (calls.incrementAndGet() <= failures) throw IllegalStateException("database down")
            return TransactionOperations.withoutTransaction().execute(action)
        }
    }

    @Test
    fun `a result that cannot be applied marks the collection failed`() {
        CollectionResultListener(service, results, tenants, transactions(failures = 1)).on(event)

        verify(service).applyFailure(event.jobId, ConnectionService.GENERIC_ERROR)
    }

    @Test
    fun `a fallback that fails too is logged, not thrown`() {
        val listener = CollectionResultListener(service, results, tenants, transactions(failures = 2))

        assertThatCode { listener.on(event) }.doesNotThrowAnyException()
        verifyNoInteractions(service)
    }

}
