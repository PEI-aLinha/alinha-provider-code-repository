package pt.alinha.coderepository.internal.web

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import pt.alinha.coderepository.internal.CollectionRunning
import pt.alinha.coderepository.internal.InvalidInput
import pt.alinha.coderepository.internal.NotConnected
import pt.alinha.coderepository.internal.SecretsUnavailable
import pt.alinha.coderepository.internal.TokenRequired

private val FIELD_MESSAGES = mapOf(
    "host" to "Escolha a plataforma onde está o código.",
    "account" to "Indique o nome da organização ou do utilizador, tal como aparece no endereço.",
    "repositories" to "Indique até 20 repositórios no formato organização/repositório, todos da mesma organização.",
    "token" to "O token não é válido. Copie-o de novo, sem espaços.",
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
    fun concurrent(e: ObjectOptimisticLockingFailureException) =
        problem(HttpStatus.CONFLICT, "CONCURRENT_CHANGE", "A ligação foi alterada noutro lado. Atualize a página.")

    private fun validation(field: String, message: String) =
        problem(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", "Há dados por corrigir.").apply {
            setProperty("errors", listOf(mapOf("field" to field, "message" to message)))
        }

    private fun problem(status: HttpStatus, code: String, detail: String) =
        ProblemDetail.forStatusAndDetail(status, detail).apply { setProperty("code", code) }
}
