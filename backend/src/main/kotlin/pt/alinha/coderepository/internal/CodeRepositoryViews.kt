package pt.alinha.coderepository.internal

import pt.alinha.coderepository.internal.domain.ConnectionStatus
import pt.alinha.coderepository.internal.domain.Host
import pt.alinha.coderepository.internal.domain.Names
import pt.alinha.coderepository.internal.domain.Outcome
import pt.alinha.pluginapi.EvidenceStrength
import java.time.Instant
import java.util.UUID

/** What the page shows about the connection. Never the token. */
data class ConnectionView(
    val host: String,
    /** Self-managed server (`https://host[:port]`); null for the cloud service. */
    val baseUrl: String?,
    /** `token` or `app` (the aLinha GitHub App). */
    val auth: String,
    val account: String,
    val repositories: List<String>,
    val status: ConnectionStatus,
    /** A job error code (`invalid_credentials`, ...); the page turns it into a sentence. */
    val lastError: String?,
    val lastCollectedAt: Instant?,
    /** When the current (or last) collection was queued. PENDING older than 15 minutes may be started again. */
    val collectingSince: Instant?,
    /** The account has more repositories than one collection inspects; only the first ones were checked. */
    val truncated: Boolean,
    val connectedAt: Instant,
)

data class EvidenceView(
    val id: UUID,
    val repository: String,
    val check: String,
    val outcome: Outcome,
    val details: Map<String, Any?>,
    val controlIds: List<String>,
    val strength: EvidenceStrength,
    val collectedAt: Instant,
)

/**
 * `PUT /api/code-repository/connection`, with a read-only access token. [token] is write-only: required for the first
 * connection and whenever the server changes, optional afterwards (null keeps the stored one). [baseUrl] is only for a
 * self-managed GitLab.
 */
data class ConnectInput(
    val host: String?,
    val account: String?,
    val repositories: List<String>? = null,
    val token: String? = null,
    val baseUrl: String? = null,
) {
    /** The validated parts, or the name of the first invalid field. */
    fun validate(): ValidConnectInput {
        val host = host?.let(Host::of) ?: throw InvalidInput("host")
        val baseUrl = baseUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { address ->
            if (host != Host.GITLAB) throw InvalidInput("baseUrl")
            (Names.serverUrl(address) ?: throw InvalidInput("baseUrl")).takeIf { it != GITLAB_CLOUD }
        }
        val account = account?.trim()?.takeIf { Names.isAccount(host, it) } ?: throw InvalidInput("account")
        val repositories = (repositories ?: emptyList()).map { it.trim() }.distinctBy { it.lowercase() }
        if (repositories.size > Names.MAX_REPOSITORIES) throw InvalidInput("repositories")
        if (repositories.any { !Names.isRepositoryOf(host, it, account) }) throw InvalidInput("repositories")
        val token = token?.trim()?.takeIf { it.isNotEmpty() }
        if (token != null && (token.length > MAX_TOKEN || token.any { it.isWhitespace() || it.isISOControl() })) {
            throw InvalidInput("token")
        }
        return ValidConnectInput(host, baseUrl, account, repositories, token)
    }

    override fun toString() =
        "ConnectInput(host=$host, baseUrl=$baseUrl, account=$account, repositories=$repositories, " +
            "token=${if (token == null) "null" else "***"})"

    companion object {
        private const val MAX_TOKEN = 512
        private const val GITLAB_CLOUD = "https://gitlab.com"
    }
}

data class ValidConnectInput(
    val host: Host,
    val baseUrl: String?,
    val account: String,
    val repositories: List<String>,
    val token: String?,
) {
    override fun toString() =
        "ValidConnectInput(host=$host, baseUrl=$baseUrl, account=$account, repositories=$repositories, token=***)"
}

/** What the page needs before anything is connected. */
data class SetupView(
    /** The aLinha GitHub App is configured on this platform, so the client can install it instead of pasting a token. */
    val githubApp: Boolean,
    /** Who was asked to connect the repository, if anyone. */
    val assignment: AssignmentView?,
)

data class AssignmentView(val email: String, val assignedAt: Instant)

/** `PUT /api/code-repository/assignment`. */
data class AssignInput(val email: String?) {
    fun validate(): String {
        val email = email?.trim()?.lowercase() ?: throw InvalidInput("email")
        if (email.length !in 3..MAX_EMAIL || !EMAIL.matches(email)) throw InvalidInput("email")
        return email
    }

    private companion object {
        const val MAX_EMAIL = 254
        val EMAIL = Regex("^[^@\\s\\p{Cntrl}]{1,64}@[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$")
    }
}

/** `POST /api/code-repository/github-app/installations`: where to send the person to install the App. */
data class AppInstallStart(val installUrl: String)

/** `PUT /api/code-repository/github-app/installation`: what GitHub sent back to the Setup URL. */
data class AppInstallInput(val installationId: Long?, val state: String?, val code: String?) {
    override fun toString() = "AppInstallInput(installationId=$installationId, state=***, code=***)"
}

class InvalidInput(val field: String) : RuntimeException("invalid $field")

class TokenRequired : RuntimeException("a token is required for the first connection")

class NotConnected : RuntimeException("no repository is connected")

class CollectionRunning : RuntimeException("a collection is already running")

class SecretsUnavailable : RuntimeException("no secret store is configured")

/** The App's state is missing, expired, already used or not this tenant's. */
class InvalidInstallState : RuntimeException("the GitHub App installation state is not valid")

/** GitHub does not show this installation to the person who came back from installing it. */
class InstallationNotVerified : RuntimeException("the installation is not the user's")

/** The GitHub App is not configured on this platform. */
class GitHubAppUnavailable : RuntimeException("the GitHub App is not configured")

/** GitHub did not answer; trying again later may work. */
class GitHubUnavailable(cause: Throwable? = null) : RuntimeException("GitHub did not answer", cause)
