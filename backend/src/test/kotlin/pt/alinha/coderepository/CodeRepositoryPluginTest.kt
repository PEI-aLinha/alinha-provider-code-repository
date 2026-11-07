package pt.alinha.coderepository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CodeRepositoryPluginTest {
    @Test
    fun `manifest tem o id do plugin`() {
        assertEquals("code-repository", CodeRepositoryPlugin().manifest.id)
    }
}
