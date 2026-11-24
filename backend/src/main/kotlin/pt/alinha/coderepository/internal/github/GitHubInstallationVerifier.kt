package pt.alinha.coderepository.internal.github

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import pt.alinha.coderepository.internal.GitHubUnavailable
import pt.alinha.coderepository.internal.InstallationNotVerified

/**
 * Checks that the person who came back from installing the App can see [installationId] on GitHub, and returns the
 * account it is installed on. GitHub's Setup URL carries `installation_id` as a plain query parameter that anyone can
 * change, so it is never trusted alone: the `code` from "Request user authorization during installation" is exchanged
 * for a user token, and that user's installations must include it. The user token is used once and not kept.
 */
fun interface GitHubInstallationVerifier {
    /** @throws InstallationNotVerified when the code is refused or the user cannot see the installation. */
    fun accountOf(installationId: Long, code: String): String
}

@Component
class GitHubOAuthInstallationVerifier internal constructor(
    private val settings: GitHubAppSettings,
    builder: RestClient.Builder,
) : GitHubInstallationVerifier {

    @Autowired
    constructor(settings: GitHubAppSettings) : this(
        settings,
        RestClient.builder().requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(CONNECT_TIMEOUT_MS)
                setReadTimeout(READ_TIMEOUT_MS)
            },
        ),
    )

    private val client = builder
        .defaultHeader("User-Agent", "alinha-code-repository")
        .defaultStatusHandler(HttpStatusCode::is5xxServerError) { _, response ->
            throw GitHubUnavailable(RestClientException("GitHub answered ${response.statusCode}"))
        }
        .build()

    override fun accountOf(installationId: Long, code: String): String {
        val token = userToken(code)
        for (page in 1..MAX_PAGES) {
            val installations = call {
                client.get()
                    .uri("$API/user/installations?per_page=$PAGE&page=$page")
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .retrieve()
                    .body(Map::class.java)
            }?.get("installations") as? List<*> ?: throw InstallationNotVerified()
            installations.filterIsInstance<Map<*, *>>()
                .firstOrNull { (it["id"] as? Number)?.toLong() == installationId }
                ?.let { found -> return ((found["account"] as? Map<*, *>)?.get("login") as? String) ?: throw InstallationNotVerified() }
            if (installations.size < PAGE) break
        }
        throw InstallationNotVerified()
    }

    private fun userToken(code: String): String {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("client_id", settings.clientId)
            add("client_secret", settings.clientSecret)
            add("code", code)
        }
        val answer = call {
            client.post()
                .uri(TOKEN_URL)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(form)
                .retrieve()
                .body(Map::class.java)
        }
        // GitHub answers 200 with {"error": "bad_verification_code"} for a used, expired or forged code.
        return answer?.get("access_token") as? String ?: throw InstallationNotVerified()
    }

    private fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: GitHubUnavailable) {
        throw e
    } catch (_: RestClientResponseException) {
        throw InstallationNotVerified() // 4xx: refused
    } catch (e: RestClientException) {
        throw GitHubUnavailable(e) // timeout, connection refused, unreadable body
    }

    private companion object {
        const val API = "https://api.github.com"
        const val TOKEN_URL = "https://github.com/login/oauth/access_token"
        const val PAGE = 100
        const val MAX_PAGES = 5
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 10_000
    }
}
