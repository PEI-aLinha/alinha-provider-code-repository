package pt.alinha.coderepository.internal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import pt.alinha.coderepository.internal.domain.Host

class ConnectInputTest {

    @Test
    fun `trims, deduplicates and keeps the token out of toString`() {
        val valid = ConnectInput("github", " acme ", listOf("acme/api", "ACME/api", " acme/web"), " ghp_x ").validate()

        assertThat(valid.host).isEqualTo(Host.GITHUB)
        assertThat(valid.account).isEqualTo("acme")
        assertThat(valid.repositories).containsExactly("acme/api", "acme/web")
        assertThat(valid.token).isEqualTo("ghp_x")
        assertThat(valid.toString()).doesNotContain("ghp_x")
    }

    @Test
    fun `the raw input never prints the token either`() {
        assertThat(ConnectInput("github", "acme", token = "ghp_secret").toString()).doesNotContain("ghp_secret")
    }

    @Test
    fun `a blank token means keep the stored one`() {
        assertThat(ConnectInput("github", "acme", token = "  ").validate().token).isNull()
    }

    @ParameterizedTest
    @CsvSource(
        "gitlab,acme,acme/api,t,host",
        ",acme,acme/api,t,host",
        "github,,acme/api,t,account",
        "github,acme/x,acme/api,t,account",
        "github,-acme,acme/api,t,account",
        "github,acme,other/api,t,repositories",
        "github,acme,acme,t,repositories",
        "github,acme,acme/a b,t,repositories",
        "github,acme,acme/api,has space,token",
    )
    fun `names the first invalid field`(host: String?, account: String?, repository: String, token: String, field: String) {
        assertThatThrownBy { ConnectInput(host, account, listOf(repository), token).validate() }
            .isInstanceOfSatisfying(InvalidInput::class.java) { assertThat(it.field).isEqualTo(field) }
    }

    @Test
    fun `at most 20 repositories`() {
        assertThatThrownBy { ConnectInput("github", "acme", List(21) { "acme/r$it" }, "t").validate() }
            .isInstanceOf(InvalidInput::class.java)
    }

    @Test
    fun `refuses an oversized token`() {
        assertThatThrownBy { ConnectInput("github", "acme", token = "x".repeat(513)).validate() }
            .isInstanceOf(InvalidInput::class.java)
    }
}
