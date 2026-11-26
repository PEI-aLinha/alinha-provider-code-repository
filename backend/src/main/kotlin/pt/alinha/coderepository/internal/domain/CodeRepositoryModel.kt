package pt.alinha.coderepository.internal.domain

/** Repository hosting platforms the worker can read. Azure DevOps can come later behind the same model. */
enum class Host(val id: String) {
    GITHUB("github"),
    GITLAB("gitlab"),
    ;

    companion object {
        fun of(id: String): Host? = entries.firstOrNull { it.id == id }
    }
}

/** The checks the worker runs on each repository (worker/src/alinha_coderepository/checks.py). */
enum class CodeCheck(val id: String) {
    BRANCH_PROTECTION("branch_protection"),
    REQUIRED_REVIEWS("required_reviews"),
    DEPENDENCY_ALERTS("dependency_alerts"),
    SECRET_SCANNING("secret_scanning"),
    ;

    companion object {
        fun of(id: String): CodeCheck? = entries.firstOrNull { it.id == id }
    }
}

/** UNKNOWN: the token could not see it. It is stored, but it is not evidence for any control. */
enum class Outcome { PASS, FAIL, UNKNOWN }

/** How the worker reaches the host: a pasted read-only token, or the aLinha GitHub App the client installed. */
enum class AuthMethod(val id: String) {
    TOKEN("token"),
    APP("app"),
}

enum class ConnectionStatus {
    /** A collection is queued or running. */
    PENDING,

    /** The last collection finished and its evidence is stored. */
    ACTIVE,

    /** The last collection failed; [pt.alinha.coderepository.internal.persistence.ConnectionEntity.lastError] says why. */
    FAILED,
}

object Names {
    /** GitHub user or organisation login. */
    private val GITHUB_ACCOUNT = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$")

    /** `owner/name`, as GitHub allows them. */
    private val GITHUB_REPOSITORY = Regex("^[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}$")

    /** A GitLab group (with subgroups) or user path; a project adds one more segment. Same rule as the worker. */
    private val GITLAB_PATH = Regex("^[A-Za-z0-9_][A-Za-z0-9_.-]{0,99}(?:/[A-Za-z0-9_][A-Za-z0-9_.-]{0,99}){0,19}$")

    private const val MAX_PATH = 255

    fun isAccount(host: Host, value: String): Boolean = when (host) {
        Host.GITHUB -> GITHUB_ACCOUNT.matches(value)
        Host.GITLAB -> value.length <= MAX_PATH && GITLAB_PATH.matches(value) && ".." !in value
    }

    fun isRepository(host: Host, value: String): Boolean = when (host) {
        Host.GITHUB -> GITHUB_REPOSITORY.matches(value)
        Host.GITLAB -> value.length <= MAX_PATH && '/' in value && GITLAB_PATH.matches(value) && ".." !in value
    }

    /** [repository] is a well-formed repository directly or (GitLab) in a subgroup of [account]. */
    fun isRepositoryOf(host: Host, repository: String, account: String): Boolean =
        isRepository(host, repository) && when (host) {
            Host.GITHUB -> repository.substringBefore('/').equals(account, ignoreCase = true)
            Host.GITLAB -> repository.lowercase().startsWith(account.lowercase() + "/")
        }

    /** `https://host[:port]` of a self-managed server: a DNS name, never an IP address or a single label. */
    private val SERVER = Regex("^https://((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63})(?::([0-9]{1,5}))?$")

    /** The normalised server address, or null when [value] is not one. The worker checks again that it is public. */
    fun serverUrl(value: String): String? {
        val match = SERVER.matchEntire(value.trim().trimEnd('/').lowercase()) ?: return null
        val port = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt()
        if (port != null && port !in 1..65535) return null
        return "https://${match.groupValues[1]}" + if (port == null || port == 443) "" else ":$port"
    }

    /** A job error code (contracts: `[a-z][a-z0-9_]*`, at most 100). Anything else is stored as `collector_error`. */
    val ERROR_CODE = Regex("^[a-z][a-z0-9_]{0,99}$")

    const val MAX_REPOSITORIES = 20
}
