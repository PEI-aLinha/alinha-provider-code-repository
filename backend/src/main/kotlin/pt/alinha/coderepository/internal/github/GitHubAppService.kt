package pt.alinha.coderepository.internal.github

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

/**
 * Connecting through the aLinha GitHub App:
 * 1. [start] keeps the hash of a random `state` for this tenant and returns GitHub's install page with it.
 * 2. GitHub sends the person back to `/resume` (the App's Callback URL) with `installation_id`, `state` and `code`; the
 *    shell returns them to the plugin page, which sends them to [complete].
 * 3. [complete] uses the state once (it binds the return to the tenant that started), checks with the code that the
 *    person can see the installation (never trusting the id alone), then connects.
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
        val startedAt = check(state)
        val account = try {
            verifier.accountOf(installationId, code, startedAt)
        } catch (e: InstallationNotVerified) {
            tx.executeWithoutResult { installs.findFirstBy()?.let(::refuse) } // this attempt is over
            throw e
        }
        // The state is used up only together with the connection: if this fails, nothing is half done.
        return tx.execute {
            val install = installs.findFirstBy()?.takeIf { matches(it, state) } ?: throw InvalidInstallState()
            installs.delete(install)
            connections.connectApp(installationId, account)
        } ?: error("no connection")
    }

    /**
     * The tenant's state, unexpired: returns when the attempt started. A wrong or expired one ends the attempt (used
     * once: a second return with it, or another tenant's, is refused), and the refusal stays in the audit trail.
     */
    private fun check(state: String): Instant {
        val startedAt = tx.execute {
            val install = installs.findFirstBy() ?: return@execute null
            if (matches(install, state) && install.expiresAt.isAfter(Instant.now(clock))) {
                install.expiresAt.minus(STATE_TTL)
            } else {
                refuse(install)
                null
            }
        }
        return startedAt ?: throw InvalidInstallState()
    }

    private fun matches(install: GitHubAppInstallEntity, state: String) =
        MessageDigest.isEqual(install.stateHash.toByteArray(), hash(state).toByteArray())

    private fun refuse(install: GitHubAppInstallEntity) {
        installs.delete(install)
        audit.record("code-repository.app-install-refused", ENTITY_TYPE, install.id.toString())
    }

    private fun hash(state: String): String =
        MessageDigest.getInstance("SHA-256").digest(state.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val ENTITY_TYPE = "code_repository_connection"
        private const val STATE_BYTES = 32
        val STATE_TTL: Duration = Duration.ofMinutes(15)
        private val STATE = Regex("^[A-Za-z0-9_-]{43}$")
        private val CODE = Regex("^[A-Za-z0-9_-]{1,100}$")
    }
}
