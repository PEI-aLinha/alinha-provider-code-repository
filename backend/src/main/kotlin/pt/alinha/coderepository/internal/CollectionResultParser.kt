package pt.alinha.coderepository.internal

import pt.alinha.coderepository.internal.domain.CodeCheck
import pt.alinha.coderepository.internal.domain.Host
import pt.alinha.coderepository.internal.domain.Names
import pt.alinha.coderepository.internal.domain.Outcome

/** One validated (repository, check) outcome from the worker. [details] holds only small scalars. */
data class CheckOutcome(
    val repository: String,
    val check: CodeCheck,
    val outcome: Outcome,
    val details: Map<String, Any>,
)

data class CollectionResult(val host: Host, val account: String, val outcomes: List<CheckOutcome>, val truncated: Boolean)

class InvalidCollectionResult(message: String) : RuntimeException(message)

/**
 * Reads the worker's `code-repository.result.v1` (worker/src/alinha_coderepository/collector.py). The result is
 * untrusted data (docs/jobs.md): anything outside the expected shape is refused as a whole, and only known keys,
 * names that match [Names] and small scalars are kept. Nothing in it is ever interpreted as an instruction.
 */
object CollectionResultParser {
    const val SCHEMA = "code-repository.result.v1"
    private const val MAX_DETAILS = 8
    private val DETAIL_KEY = Regex("^[a-zA-Z]{1,40}$")
    private val DETAIL_TEXT = Regex("^[a-z_]{1,40}$")

    fun parse(result: Map<String, Any?>?, expectedRepositories: List<String>): CollectionResult {
        if (result == null) fail("no result")
        if (result["schema"] != SCHEMA) fail("unknown result schema")
        val host = (result["host"] as? String)?.let(Host::of) ?: fail("unknown host")
        val account = (result["account"] as? String)?.takeIf { Names.isAccount(host, it) } ?: fail("invalid account")
        val truncated = result["truncated"] as? Boolean ?: fail("truncated is not a boolean")
        val repositories = result["repositories"] as? List<*> ?: fail("repositories is not a list")
        if (repositories.size > Names.MAX_REPOSITORIES) fail("too many repositories")

        val outcomes = repositories.flatMap { repository(it, host, account, expectedRepositories) }
        if (outcomes.distinctBy { it.repository.lowercase() to it.check }.size != outcomes.size) {
            fail("a repository or check is repeated")
        }
        return CollectionResult(host, account, outcomes, truncated)
    }

    private fun repository(value: Any?, host: Host, account: String, expected: List<String>): List<CheckOutcome> {
        val entry = value as? Map<*, *> ?: fail("repository entry is not an object")
        val name = (entry["repository"] as? String)?.takeIf { Names.isRepository(host, it) } ?: fail("invalid repository")
        // A repository the job did not ask for (or, with no list, outside the connected account) is not this tenant's.
        val allowed = if (expected.isEmpty()) {
            Names.isRepositoryOf(host, name, account)
        } else {
            expected.any { it.equals(name, ignoreCase = true) }
        }
        if (!allowed) fail("repository outside the connection's scope")
        val checks = entry["checks"] as? List<*> ?: fail("checks is not a list")
        if (checks.size > CodeCheck.entries.size) fail("too many checks")
        return checks.map { check(it, name) }
    }

    private fun check(value: Any?, repository: String): CheckOutcome {
        val entry = value as? Map<*, *> ?: fail("check is not an object")
        val check = (entry["check"] as? String)?.let(CodeCheck::of) ?: fail("unknown check")
        val outcome = (entry["outcome"] as? String)?.let { runCatching { Outcome.valueOf(it) }.getOrNull() }
            ?: fail("unknown outcome")
        val details = entry["details"] as? Map<*, *> ?: emptyMap<Any, Any>()
        if (details.size > MAX_DETAILS) fail("too many details")
        return CheckOutcome(repository, check, outcome, details.entries.associate { (key, detail) -> detail(key, detail) })
    }

    private fun detail(key: Any?, value: Any?): Pair<String, Any> {
        val name = (key as? String)?.takeIf { DETAIL_KEY.matches(it) } ?: fail("invalid detail key")
        val clean: Any = when (value) {
            is Boolean -> value
            is Int -> value.takeIf { it in 0..100_000 } ?: fail("detail out of range")
            is Long -> value.takeIf { it in 0..100_000 }?.toInt() ?: fail("detail out of range")
            is String -> value.takeIf { DETAIL_TEXT.matches(it) } ?: fail("invalid detail text")
            else -> fail("invalid detail value")
        }
        return name to clean
    }

    private fun fail(reason: String): Nothing = throw InvalidCollectionResult(reason)
}
