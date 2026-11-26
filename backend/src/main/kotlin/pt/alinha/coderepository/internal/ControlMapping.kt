package pt.alinha.coderepository.internal

import pt.alinha.coderepository.internal.domain.CodeCheck

/**
 * Which NIS2/QNRCS v1 controls (frameworks/nis2-qnrcs/v1/catalogue.yaml) each check is technical evidence for.
 * Deterministic and versioned (RNF05): every stored evidence row keeps the [VERSION] it was mapped with. Change
 * the mapping by bumping [VERSION], never by editing it in place. Moves into the catalogue with alinha#29.
 */
object ControlMapping {
    const val VERSION = 1

    val CONTROLS: Map<CodeCheck, Set<String>> = mapOf(
        // Changes reach the default branch only through a controlled path.
        CodeCheck.BRANCH_PROTECTION to setOf("PR.SP-4-S", "ID.GA-9-E"),
        // Every change is reviewed by someone else before it is merged.
        CodeCheck.REQUIRED_REVIEWS to setOf("PR.SP-4-S", "PR.SP-4-E"),
        // Vulnerable dependencies are identified and the serious ones are handled.
        CodeCheck.DEPENDENCY_ALERTS to setOf("ID.AR-1-B", "ID.AR-7-B", "ID.AR-7-E"),
        // Credentials are kept out of the code.
        CodeCheck.SECRET_SCANNING to setOf("PR.SP-4-S", "PR.SP-4-E"),
    )

    fun controlsFor(check: CodeCheck): Set<String> = CONTROLS.getValue(check)
}
