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
 * `PUT /api/code-repository/connection`. [token] is write-only: required for the first connection, optional
 * afterwards (null keeps the stored one).
 */
data class ConnectInput(
    val host: String?,
    val account: String?,
    val repositories: List<String>? = null,
    val token: String? = null,
) {
    /** The validated parts, or the name of the first invalid field. */
    fun validate(): ValidConnectInput {
        val host = host?.let(Host::of) ?: throw InvalidInput("host")
        val account = account?.trim()?.takeIf { Names.ACCOUNT.matches(it) } ?: throw InvalidInput("account")
        val repositories = (repositories ?: emptyList()).map { it.trim() }.distinctBy { it.lowercase() }
        if (repositories.size > Names.MAX_REPOSITORIES) throw InvalidInput("repositories")
        if (repositories.any { !Names.REPOSITORY.matches(it) || !it.substringBefore('/').equals(account, true) }) {
            throw InvalidInput("repositories")
        }
        val token = token?.trim()?.takeIf { it.isNotEmpty() }
        if (token != null && (token.length > MAX_TOKEN || token.any { it.isWhitespace() || it.isISOControl() })) {
            throw InvalidInput("token")
        }
        return ValidConnectInput(host, account, repositories, token)
    }

    override fun toString() =
        "ConnectInput(host=$host, account=$account, repositories=$repositories, token=${if (token == null) "null" else "***"})"

    companion object {
        private const val MAX_TOKEN = 512
    }
}

data class ValidConnectInput(val host: Host, val account: String, val repositories: List<String>, val token: String?) {
    override fun toString() = "ValidConnectInput(host=$host, account=$account, repositories=$repositories, token=***)"
}

class InvalidInput(val field: String) : RuntimeException("invalid $field")

class TokenRequired : RuntimeException("a token is required for the first connection")

class NotConnected : RuntimeException("no repository is connected")

class CollectionRunning : RuntimeException("a collection is already running")

class SecretsUnavailable : RuntimeException("no secret store is configured")
