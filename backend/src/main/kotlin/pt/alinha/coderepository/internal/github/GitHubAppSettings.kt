package pt.alinha.coderepository.internal.github

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * The aLinha GitHub App, registered by the team (DEPLOYMENT.md, "GitHub App"). [slug] and [clientId] are public;
 * [clientSecret] comes from OpenBao (`secret/alinha/app`, key `code-repository.github-app.client-secret`).
 * The App's private key is never here: only the worker reads it, from `secret/providers/code-repository`.
 * When anything is missing the App is not offered and clients connect with a token.
 */
@Component
class GitHubAppSettings(
    @Value("\${GITHUB_APP_SLUG:}") val slug: String,
    @Value("\${GITHUB_APP_CLIENT_ID:}") val clientId: String,
    @Value("\${code-repository.github-app.client-secret:}") val clientSecret: String,
) {
    val available: Boolean get() = SLUG.matches(slug) && clientId.isNotBlank() && clientSecret.isNotBlank()

    fun installUrl(state: String) = "https://github.com/apps/$slug/installations/new?state=$state"

    override fun toString() = "GitHubAppSettings(slug=$slug, clientId=$clientId, clientSecret=***)"

    private companion object {
        val SLUG = Regex("^[a-z0-9](?:[a-z0-9-]{0,98}[a-z0-9])?$")
    }
}
