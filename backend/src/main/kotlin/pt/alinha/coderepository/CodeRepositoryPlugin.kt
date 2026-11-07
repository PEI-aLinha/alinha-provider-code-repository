package pt.alinha.coderepository

import org.springframework.stereotype.Component
import pt.alinha.pluginapi.AlinhaPlugin
import pt.alinha.pluginapi.PluginKind
import pt.alinha.pluginapi.PluginManifest

/** Registo do plugin no Module Registry do Core. Manter igual ao plugin.yaml. */
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
