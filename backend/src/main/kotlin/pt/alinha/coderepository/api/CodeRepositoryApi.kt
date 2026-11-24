package pt.alinha.coderepository.api

import pt.alinha.pluginapi.EvidenceStrength
import java.time.Instant
import java.util.UUID

/**
 * This plugin's «api» contract: the ONLY synchronous way for other plugins
 * to ask it for data. Everything under pt.alinha.coderepository.internal is private.
 */
interface CodeRepositoryApi {
    /** The evidence an `EvidenceCollected` event points to, in the current tenant; null if there is none. */
    fun evidence(evidenceId: UUID): CodeEvidence?
}

/**
 * One check on one repository. [outcome] is `PASS` or `FAIL` for every evidence announced by an event.
 * [check] is one of `branch_protection`, `required_reviews`, `dependency_alerts`, `secret_scanning`.
 */
data class CodeEvidence(
    val id: UUID,
    val repository: String,
    val check: String,
    val outcome: String,
    val controlIds: Set<String>,
    val strength: EvidenceStrength,
    val mappingVersion: Int,
    val collectedAt: Instant,
)
