package pt.alinha.coderepository

import org.springframework.stereotype.Component
import pt.alinha.pluginapi.AlinhaPlugin
import pt.alinha.pluginapi.NavArea
import pt.alinha.pluginapi.NavEntry
import pt.alinha.pluginapi.PluginKind
import pt.alinha.pluginapi.PluginManifest

/** Registers the plugin in the core's Module Registry. Keep in sync with plugin.yaml. */
@Component
class CodeRepositoryPlugin : AlinhaPlugin {
    override val manifest = PluginManifest(
        id = ID,
        name = "Code Repository",
        kind = PluginKind.PROVIDER,
        version = "0.1.0",
        publishes = setOf("EvidenceCollected"),
        consumes = setOf("JobCompleted", "JobFailed"),
        permissions = setOf(READ, CONNECT, ASSIGN),
        rolePermissions = mapOf(
            // US37: the responsible person connects the repository, or asks the analyst to (email invite).
            "responsible" to setOf(READ, CONNECT, ASSIGN),
            "analyst" to setOf(READ, CONNECT),
            "consultant" to setOf(READ, CONNECT, ASSIGN),
        ),
        nav = listOf(
            NavEntry("Repositório de código", "integrations/code-repository", NavArea.CLIENT, resource = RESOURCE, order = 70),
        ),
    )

    companion object {
        const val ID = "code-repository"
        const val RESOURCE = "code-repository"
        const val READ = "code-repository:read"
        const val CONNECT = "code-repository:connect"
        const val ASSIGN = "code-repository:assign"
    }
}
