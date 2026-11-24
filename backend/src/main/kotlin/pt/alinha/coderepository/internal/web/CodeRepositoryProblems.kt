package pt.alinha.coderepository.internal.web

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import pt.alinha.coderepository.internal.CollectionRunning
import pt.alinha.coderepository.internal.GitHubAppUnavailable
import pt.alinha.coderepository.internal.GitHubUnavailable
import pt.alinha.coderepository.internal.InstallationNotVerified
import pt.alinha.coderepository.internal.InvalidInput
import pt.alinha.coderepository.internal.InvalidInstallState
import pt.alinha.coderepository.internal.NotConnected
import pt.alinha.coderepository.internal.SecretsUnavailable
import pt.alinha.coderepository.internal.TokenRequired
import pt.alinha.coderepository.internal.TooManyInvites
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity

private val FIELD_MESSAGES = mapOf(
    "host" to "Escolha a plataforma onde está o código.",
    "account" to "Indique o nome da organização ou do utilizador, tal como aparece no endereço.",
    "repositories" to "Indique até 20 repositórios no formato organização/repositório, todos da mesma organização.",
    "token" to "O token não é válido. Copie-o de novo, sem espaços.",
    "baseUrl" to "Indique o endereço do servidor GitLab, por exemplo https://gitlab.empresa.pt.",
    "email" to "Indique um endereço de email válido.",
)

/** RFC 9457 Problem Details with `code` and `errors` (ADR 0003). `detail` is shown to the user, in Portuguese. */
@RestControllerAdvice(basePackageClasses = [CodeRepositoryController::class])
class CodeRepositoryProblems {

    @ExceptionHandler
    fun invalid(e: InvalidInput) = validation(e.field, FIELD_MESSAGES.getValue(e.field))

    @ExceptionHandler
    fun tokenRequired(e: TokenRequired) = validation("token", "Para ligar o repositório, cole um token de acesso.")

    /** Never echoes the body: it may hold the token. */
    @ExceptionHandler
    fun unreadable(e: HttpMessageNotReadableException) =
        problem(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", "Não foi possível ler os dados enviados.")

    @ExceptionHandler
    fun notConnected(e: NotConnected) =
        problem(HttpStatus.NOT_FOUND, "NOT_CONNECTED", "Ainda não há nenhum repositório ligado.")

    @ExceptionHandler
    fun running(e: CollectionRunning) =
        problem(HttpStatus.CONFLICT, "COLLECTION_RUNNING", "Já está a decorrer uma recolha. Aguarde que termine.")

    @ExceptionHandler
    fun noSecrets(e: SecretsUnavailable) = problem(
        HttpStatus.SERVICE_UNAVAILABLE, "SECRETS_UNAVAILABLE",
        "Não é possível guardar o token neste momento. Tente novamente mais tarde.",
    )

    @ExceptionHandler
    fun installState(e: InvalidInstallState) = problem(
        HttpStatus.UNPROCESSABLE_CONTENT, "APP_INSTALL_EXPIRED",
        "A instalação da aplicação expirou ou não foi iniciada nesta página. Comece de novo.",
    )

    @ExceptionHandler
    fun notVerified(e: InstallationNotVerified) = problem(
        HttpStatus.UNPROCESSABLE_CONTENT, "APP_INSTALL_NOT_VERIFIED",
        "Não foi possível confirmar que foi a sua conta a instalar a aplicação agora. Instale-a (ou altere-a) de novo a partir desta página.",
    )

    @ExceptionHandler
    fun appUnavailable(e: GitHubAppUnavailable) = problem(
        HttpStatus.SERVICE_UNAVAILABLE, "APP_UNAVAILABLE",
        "A ligação pela aplicação do GitHub não está disponível. Use um token de acesso.",
    )

    @ExceptionHandler
    fun githubUnavailable(e: GitHubUnavailable) = problem(
        HttpStatus.SERVICE_UNAVAILABLE, "GITHUB_UNAVAILABLE", "O GitHub não respondeu. Tente novamente dentro de alguns minutos.",
    )

    @ExceptionHandler
    fun tooManyInvites(e: TooManyInvites): ResponseEntity<ProblemDetail> = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header(HttpHeaders.RETRY_AFTER, e.retryAfter.seconds.toString())
        .body(problem(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_INVITES", "Acabou de enviar um pedido. Aguarde um minuto antes de enviar outro."))

    /** Two requests for the same organisation at once (one row per tenant). */
    @ExceptionHandler
    fun duplicate(e: DataIntegrityViolationException) =
        problem(HttpStatus.CONFLICT, "CONCURRENT_CHANGE", "A ligação foi alterada noutro lado. Atualize a página.")

    @ExceptionHandler
    fun concurrent(e: ObjectOptimisticLockingFailureException) =
        problem(HttpStatus.CONFLICT, "CONCURRENT_CHANGE", "A ligação foi alterada noutro lado. Atualize a página.")

    private fun validation(field: String, message: String) =
        problem(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", "Há dados por corrigir.").apply {
            setProperty("errors", listOf(mapOf("field" to field, "message" to message)))
        }

    private fun problem(status: HttpStatus, code: String, detail: String) =
        ProblemDetail.forStatusAndDetail(status, detail).apply { setProperty("code", code) }
}
