package pt.alinha.coderepository

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CodeRepositoryPluginTest {
    private val manifest = CodeRepositoryPlugin().manifest

    @Test
    fun `manifest has the plugin id`() {
        assertThat(manifest.id).isEqualTo("code-repository")
    }

    @Test
    fun `the responsible person, the analyst and the consultant can connect (US37)`() {
        listOf("responsible", "analyst", "consultant").forEach { role ->
            assertThat(manifest.rolePermissions[role]).contains(CodeRepositoryPlugin.READ, CodeRepositoryPlugin.CONNECT)
        }
        assertThat(manifest.rolePermissions).doesNotContainKey("registrant")
    }

    @Test
    fun `only the responsible person and the consultant ask someone else to connect`() {
        assertThat(manifest.rolePermissions.filterValues { CodeRepositoryPlugin.ASSIGN in it }.keys)
            .containsExactlyInAnyOrder("responsible", "consultant")
        assertThat(manifest.permissions).contains(CodeRepositoryPlugin.ASSIGN)
    }
}
