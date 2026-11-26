package pt.alinha.coderepository.internal.github

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
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
import java.time.Instant

/**
 * Checks that the person who came back from installing the App installed (or changed) [installationId] themselves,
 * and returns the account it is installed on. GitHub's Callback URL carries `installation_id` as a plain query
 * parameter that anyone can change, so it is never trusted alone:
 * - the `code` from "Request user authorization during installation" is exchanged for a user token, and that user's
 *   installations must include it. That list also holds installations of organisations where the user is only a
 *   member or a collaborator, so:
 * - the installation must have been created or updated after [since] (when this tenant started installing). A member
 *   cannot make an organisation's owner install or change the App inside someone else's 15-minute window.
 *   `updated_at` counts so that an App already installed can be connected again by changing it on GitHub (after a
 *   disconnect, or from another tenant). Residual risk, accepted for the MVP: `updated_at` also moves for changes the
 *   member did not make (an owner editing repository access, a suspension, new permissions). A member who sees the
 *   installation and returns with its id inside such a change's window can bind it to their tenant. The repositories
 *   it then collects are read-only and ones that member can already see on GitHub.
 * The user token is used once, then revoked.
 */
fun interface GitHubInstallationVerifier {
    /** @throws InstallationNotVerified when the code is refused, or the user did not just install or change it. */
    fun accountOf(installationId: Long, code: String, since: Instant): String
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

    override fun accountOf(installationId: Long, code: String, since: Instant): String {
        val token = userToken(code)
        try {
            return find(installationId, token, since)
        } finally {
            revoke(token)
        }
    }

    private fun find(installationId: Long, token: String, since: Instant): String {
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
                ?.let { found ->
                    if (!changedSince(found, since)) throw InstallationNotVerified()
                    return ((found["account"] as? Map<*, *>)?.get("login") as? String) ?: throw InstallationNotVerified()
                }
            if (installations.size < PAGE) break
        }
        throw InstallationNotVerified()
    }

    /** Created or updated after [since], less a minute of clock drift between GitHub and here. */
    private fun changedSince(installation: Map<*, *>, since: Instant): Boolean =
        listOf("created_at", "updated_at").mapNotNull { key ->
            (installation[key] as? String)?.let { runCatching { Instant.parse(it) }.getOrNull() }
        }.any { !it.isBefore(since.minus(CLOCK_DRIFT)) }

    /** Best effort: the token expires anyway (the App must use expiring user tokens, DEPLOYMENT.md). */
    private fun revoke(token: String) {
        try {
            client.method(org.springframework.http.HttpMethod.DELETE)
                .uri("$API/applications/${settings.clientId}/token")
                .headers { it.setBasicAuth(settings.clientId, settings.clientSecret) }
                .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("access_token" to token))
                .retrieve()
                .toBodilessEntity()
        } catch (_: RuntimeException) {
            // Nothing to do: the user token is not stored anywhere and expires on its own.
        }
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
        val CLOCK_DRIFT: java.time.Duration = java.time.Duration.ofMinutes(1)
    }
}
