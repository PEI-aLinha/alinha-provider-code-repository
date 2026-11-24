package pt.alinha.coderepository.internal.domain

/** Repository hosting platforms the worker can read. GitLab and Azure DevOps come later behind the same model. */
enum class Host(val id: String) {
    GITHUB("github"),
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
    val ACCOUNT = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$")

    /** `owner/name`, as GitHub allows them. */
    val REPOSITORY = Regex("^[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}$")

    /** A job error code (contracts: `[a-z][a-z0-9_]*`, at most 100). Anything else is stored as `collector_error`. */
    val ERROR_CODE = Regex("^[a-z][a-z0-9_]{0,99}$")

    const val MAX_REPOSITORIES = 20
}
