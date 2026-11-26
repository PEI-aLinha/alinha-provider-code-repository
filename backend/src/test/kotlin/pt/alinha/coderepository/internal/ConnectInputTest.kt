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
        "bitbucket,acme,acme/api,t,host",
        "gitlab,acme/../x,acme/x/api,t,account",
        "gitlab,acme,other/api,t,repositories",
        "gitlab,acme/team,acme/api,t,repositories",
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

    @Test
    fun `a GitLab group may have subgroups and its projects may sit deeper`() {
        val valid = ConnectInput("gitlab", "acme/platform", listOf("acme/platform/web", "Acme/Platform/infra/db"), "glpat").validate()

        assertThat(valid.host).isEqualTo(Host.GITLAB)
        assertThat(valid.baseUrl).isNull()
        assertThat(valid.repositories).hasSize(2)
    }

    @ParameterizedTest
    @CsvSource(
        "https://git.acme.pt,https://git.acme.pt",
        "HTTPS://Git.Acme.PT/,https://git.acme.pt",
        "https://git.acme.pt:443,https://git.acme.pt",
        "https://git.acme.pt:8443,https://git.acme.pt:8443",
    )
    fun `normalises a self-managed GitLab address`(address: String, expected: String) {
        assertThat(ConnectInput("gitlab", "acme", baseUrl = address, token = "t").validate().baseUrl).isEqualTo(expected)
    }

    @Test
    fun `gitlab dot com is the cloud service, with no address`() {
        assertThat(ConnectInput("gitlab", "acme", baseUrl = "https://gitlab.com", token = "t").validate().baseUrl).isNull()
    }

    @ParameterizedTest
    @CsvSource(
        "gitlab,http://git.acme.pt",
        "gitlab,https://10.0.0.1",
        "gitlab,https://localhost",
        "gitlab,https://user@git.acme.pt",
        "gitlab,https://git.acme.pt/api",
        "gitlab,https://git.acme.pt:70000",
        "github,https://ghe.acme.pt",
    )
    fun `refuses any other server address`(host: String, address: String) {
        assertThatThrownBy { ConnectInput(host, "acme", baseUrl = address, token = "t").validate() }
            .isInstanceOfSatisfying(InvalidInput::class.java) { assertThat(it.field).isEqualTo("baseUrl") }
    }

    @ParameterizedTest
    @CsvSource("ana@acme.pt,ana@acme.pt", "' Ana.Silva+code@Acme.PT ',ana.silva+code@acme.pt")
    fun `accepts and normalises an email`(input: String, expected: String) {
        assertThat(AssignInput(input).validate()).isEqualTo(expected)
    }

    @ParameterizedTest
    @CsvSource("ana", "ana@", "@acme.pt", "ana@acme", "'ana silva@acme.pt'", "ana@-acme.pt")
    fun `refuses an invalid email`(input: String) {
        assertThatThrownBy { AssignInput(input).validate() }.isInstanceOf(InvalidInput::class.java)
    }

    @Test
    fun `the App's state and code never print`() {
        assertThat(AppInstallInput(1, "state-secret", "code-secret").toString()).doesNotContain("secret")
    }
}
