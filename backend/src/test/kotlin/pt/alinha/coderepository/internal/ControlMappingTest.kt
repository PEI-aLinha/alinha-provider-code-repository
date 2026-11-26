package pt.alinha.coderepository.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.support.TransactionOperations
import pt.alinha.coderepository.internal.domain.CodeCheck
import pt.alinha.core.compliance.CatalogueRegistry
import java.nio.file.Path
import kotlin.io.path.exists

class ControlMappingTest {
    private val catalogue = CatalogueRegistry(
        repoRoot().resolve("frameworks").toString(),
        ApplicationEventPublisher { },
        TransactionOperations.withoutTransaction(),
    ).apply { onStartup() }.get("nis2-qnrcs", 1)!!

    @Test
    fun `every check maps to at least one control`() {
        CodeCheck.entries.forEach { assertThat(ControlMapping.controlsFor(it)).describedAs(it.id).isNotEmpty() }
    }

    @Test
    fun `every mapped control exists in NIS2 QNRCS v1 and is technical`() {
        val controls = catalogue.controls.associateBy { it.id }
        ControlMapping.CONTROLS.values.flatten().toSet().forEach { id ->
            assertThat(controls).describedAs("control $id").containsKey(id)
            assertThat(controls.getValue(id).technical).describedAs("control $id is technical").isTrue()
        }
    }

    private fun repoRoot(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { it.resolve("settings.gradle.kts").exists() }
}
