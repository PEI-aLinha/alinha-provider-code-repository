package pt.alinha.coderepository

import org.springframework.stereotype.Component
import pt.alinha.pluginapi.AlinhaPlugin
import pt.alinha.pluginapi.PluginKind
import pt.alinha.pluginapi.PluginManifest

/** Registers the plugin in the core's Module Registry. Keep in sync with plugin.yaml. */
@Component
class CodeRepositoryPlugin : AlinhaPlugin {
    override val manifest = PluginManifest(
        id = "code-repository",
        name = "Code Repository",
        kind = PluginKind.PROVIDER,
        version = "0.1.0",
        publishes = setOf(),
        consumes = setOf(),
        permissions = setOf(),
    )
}
