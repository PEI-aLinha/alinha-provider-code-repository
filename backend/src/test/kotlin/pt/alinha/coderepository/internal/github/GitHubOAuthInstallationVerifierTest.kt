package pt.alinha.coderepository.internal.github

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withException
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import pt.alinha.coderepository.internal.GitHubUnavailable
import pt.alinha.coderepository.internal.InstallationNotVerified
import java.io.IOException

class GitHubOAuthInstallationVerifierTest {
    private val settings = GitHubAppSettings("alinha", "Iv1.client", "s3cret")
    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val verifier = GitHubOAuthInstallationVerifier(settings, builder)

    private fun tokenAnswer(body: String = """{"access_token": "ghu_user", "token_type": "bearer"}""") =
        server.expect(requestTo("https://github.com/login/oauth/access_token"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().formDataContains(mapOf("client_id" to "Iv1.client", "client_secret" to "s3cret", "code" to "abc")))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON))

    private fun installations(page: Int, body: String) =
        server.expect(requestTo("https://api.github.com/user/installations?per_page=100&page=$page"))
            .andExpect(header("Authorization", "Bearer ghu_user"))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON))

    @Test
    fun `returns the account of an installation the user can see`() {
        tokenAnswer()
        installations(1, """{"installations": [{"id": 1, "account": {"login": "other"}}, {"id": 42, "account": {"login": "acme"}}]}""")

        assertThat(verifier.accountOf(42, "abc")).isEqualTo("acme")
        server.verify()
    }

    @Test
    fun `looks through further pages`() {
        tokenAnswer()
        installations(1, """{"installations": [${(1..100).joinToString { """{"id": $it, "account": {"login": "o$it"}}""" }}]}""")
        installations(2, """{"installations": [{"id": 4242, "account": {"login": "acme"}}]}""")

        assertThat(verifier.accountOf(4242, "abc")).isEqualTo("acme")
    }

    @Test
    fun `an installation the user cannot see is not verified`() {
        tokenAnswer()
        installations(1, """{"installations": [{"id": 1, "account": {"login": "other"}}]}""")

        assertThatThrownBy { verifier.accountOf(42, "abc") }.isInstanceOf(InstallationNotVerified::class.java)
    }

    @Test
    fun `a used or forged code is not verified`() {
        tokenAnswer("""{"error": "bad_verification_code"}""")

        assertThatThrownBy { verifier.accountOf(42, "abc") }.isInstanceOf(InstallationNotVerified::class.java)
    }

    @Test
    fun `a refused user token is not verified`() {
        tokenAnswer()
        server.expect(requestTo("https://api.github.com/user/installations?per_page=100&page=1"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED))

        assertThatThrownBy { verifier.accountOf(42, "abc") }.isInstanceOf(InstallationNotVerified::class.java)
    }

    @Test
    fun `GitHub down or unreachable is unavailable, not a refusal`() {
        server.expect(ExpectedCount.once(), requestTo("https://github.com/login/oauth/access_token"))
            .andRespond(withStatus(HttpStatus.BAD_GATEWAY))
        assertThatThrownBy { verifier.accountOf(42, "abc") }.isInstanceOf(GitHubUnavailable::class.java)

        server.reset()
        server.expect(requestTo("https://github.com/login/oauth/access_token")).andRespond(withException(IOException("reset")))
        assertThatThrownBy { verifier.accountOf(42, "abc") }.isInstanceOf(GitHubUnavailable::class.java)
    }

    @Test
    fun `settings are only available when complete, and never print the secret`() {
        assertThat(settings.available).isTrue()
        assertThat(settings.toString()).doesNotContain("s3cret")
        assertThat(GitHubAppSettings("", "id", "secret").available).isFalse()
        assertThat(GitHubAppSettings("Bad Slug", "id", "secret").available).isFalse()
        assertThat(GitHubAppSettings("alinha", "id", "").available).isFalse()
        assertThat(settings.installUrl("s")).isEqualTo("https://github.com/apps/alinha/installations/new?state=s")
    }
}
