package pt.alinha.coderepository.internal.github

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionOperations
import pt.alinha.coderepository.internal.AppInstallInput
import pt.alinha.coderepository.internal.AppInstallStart
import pt.alinha.coderepository.internal.ConnectionService
import pt.alinha.coderepository.internal.ConnectionView
import pt.alinha.coderepository.internal.GitHubAppUnavailable
import pt.alinha.coderepository.internal.InstallationNotVerified
import pt.alinha.coderepository.internal.InvalidInstallState
import pt.alinha.coderepository.internal.persistence.GitHubAppInstallEntity
import pt.alinha.coderepository.internal.persistence.GitHubAppInstallRepository
import pt.alinha.core.audit.AuditTrail
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

private val log = LoggerFactory.getLogger(GitHubAppService::class.java)

/**
 * Connecting through the aLinha GitHub App:
 * 1. [start] keeps the hash of a random `state` for this tenant and returns GitHub's install page with it.
 * 2. GitHub sends the person back to `/resume` (the App's Callback URL) with `installation_id`, `state` and `code`; the
 *    shell returns them to the plugin page, which sends them to [complete].
 * 3. [complete] uses the state up atomically before asking GitHub (it binds the return to the tenant that started, and
 *    a replay finds it gone), checks with the code that the person can see the installation (never trusting the id
 *    alone), then connects. When GitHub does not answer or connecting fails, the attempt is put back.
 * Not one transaction: the GitHub call sits between two short ones, so no database connection waits on GitHub.
 */
@Service
class GitHubAppService(
    private val settings: GitHubAppSettings,
    private val installs: GitHubAppInstallRepository,
    private val verifier: GitHubInstallationVerifier,
    private val connections: ConnectionService,
    private val audit: AuditTrail,
    private val tx: TransactionOperations,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val random = SecureRandom()

    fun start(): AppInstallStart {
        if (!settings.available) throw GitHubAppUnavailable()
        val state = ByteArray(STATE_BYTES).also(random::nextBytes).let(Base64.getUrlEncoder().withoutPadding()::encodeToString)
        val expiresAt = Instant.now(clock).plus(STATE_TTL)
        tx.executeWithoutResult {
            val install = installs.findFirstBy()?.apply { renew(hash(state), expiresAt) }
                ?: installs.save(GitHubAppInstallEntity(UUID.randomUUID(), hash(state), expiresAt))
            audit.record("code-repository.app-install-started", ENTITY_TYPE, install.id.toString())
        }
        return AppInstallStart(settings.installUrl(state))
    }

    fun complete(input: AppInstallInput): ConnectionView {
        if (!settings.available) throw GitHubAppUnavailable()
        val installationId = input.installationId?.takeIf { it > 0 } ?: throw InvalidInstallState()
        val state = input.state?.takeIf { STATE.matches(it) } ?: throw InvalidInstallState()
        val code = input.code?.takeIf { CODE.matches(it) } ?: throw InvalidInstallState()
        val claim = claim(state)
        val account = try {
            verifier.accountOf(installationId, code, claim.startedAt)
        } catch (e: InstallationNotVerified) {
            // This attempt is over: the state stays used up.
            tx.executeWithoutResult { audit.record(REFUSED, ENTITY_TYPE, claim.id.toString()) }
            throw e
        } catch (e: RuntimeException) {
            restore(claim) // GitHub did not answer: the person may try again with the same return
            throw e
        }
        return try {
            tx.execute { connections.connectApp(installationId, account) } ?: error("no connection")
        } catch (e: RuntimeException) {
            restore(claim) // nothing was connected (a collection running, OpenBao refusing): keep the attempt open
            throw e
        }
    }

    /** An attempt this call used up, kept in memory so it can be put back when connecting did not happen. */
    private class Claim(val id: UUID, val stateHash: String, val expiresAt: Instant) {
        val startedAt: Instant get() = expiresAt.minus(STATE_TTL)
    }

    /**
     * Uses the tenant's unexpired state up, atomically and before GitHub is asked, so a replayed or concurrent return
     * with the same state is refused. A wrong or expired one ends the attempt (another tenant's state never matches),
     * and the refusal stays in the audit trail.
     */
    private fun claim(state: String): Claim {
        val claimed = tx.execute {
            val install = installs.findFirstBy() ?: return@execute null
            val now = Instant.now(clock)
            when {
                !matches(install, state) || !install.expiresAt.isAfter(now) -> refuse(install).let { null }
                // Another call claimed it first: that one goes on, this one is a replay.
                installs.claim(install.id, install.stateHash, now) != 1 -> null
                else -> Claim(install.id, install.stateHash, install.expiresAt)
            }
        }
        return claimed ?: throw InvalidInstallState()
    }

    /** Puts a claimed attempt back, unless a new one was started meanwhile (that one stands). */
    private fun restore(claim: Claim) {
        try {
            tx.executeWithoutResult {
                if (installs.findFirstBy() == null) installs.save(GitHubAppInstallEntity(claim.id, claim.stateHash, claim.expiresAt))
            }
        } catch (e: RuntimeException) {
            log.warn("could not keep the GitHub App installation attempt {} open", claim.id, e)
        }
    }

    private fun matches(install: GitHubAppInstallEntity, state: String) =
        MessageDigest.isEqual(install.stateHash.toByteArray(), hash(state).toByteArray())

    private fun refuse(install: GitHubAppInstallEntity) {
        installs.delete(install)
        audit.record(REFUSED, ENTITY_TYPE, install.id.toString())
    }

    private fun hash(state: String): String =
        MessageDigest.getInstance("SHA-256").digest(state.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val ENTITY_TYPE = "code_repository_connection"
        private const val REFUSED = "code-repository.app-install-refused"
        private const val STATE_BYTES = 32
        val STATE_TTL: Duration = Duration.ofMinutes(15)
        private val STATE = Regex("^[A-Za-z0-9_-]{43}$")
        private val CODE = Regex("^[A-Za-z0-9_-]{1,100}$")
    }
}
