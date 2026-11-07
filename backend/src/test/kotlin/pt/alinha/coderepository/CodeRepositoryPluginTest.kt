package pt.alinha.coderepository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CodeRepositoryPluginTest {
    @Test
    fun `manifest has the plugin id`() {
        assertEquals("code-repository", CodeRepositoryPlugin().manifest.id)
    }
}
