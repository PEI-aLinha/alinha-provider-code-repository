package pt.alinha.coderepository.internal.web

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pt.alinha.coderepository.internal.AppInstallInput
import pt.alinha.coderepository.internal.AppInstallStart
import pt.alinha.coderepository.internal.AssignInput
import pt.alinha.coderepository.internal.AssignmentService
import pt.alinha.coderepository.internal.AssignmentView
import pt.alinha.coderepository.internal.ConnectInput
import pt.alinha.coderepository.internal.ConnectionService
import pt.alinha.coderepository.internal.ConnectionView
import pt.alinha.coderepository.internal.EvidenceView
import pt.alinha.coderepository.internal.SetupView
import pt.alinha.coderepository.internal.github.GitHubAppService
import pt.alinha.core.access.RequiresPermission

private const val RESOURCE = "code-repository"

/** The tenant always comes from the token (TenantContext), never from these requests. */
@RestController
@RequestMapping("/api/code-repository")
class CodeRepositoryController(
    private val service: ConnectionService,
    private val assignments: AssignmentService,
    private val githubApp: GitHubAppService,
) {

    /** Whether the GitHub App can be offered, and who was asked to connect. */
    @RequiresPermission(RESOURCE, "read")
    @GetMapping("/setup")
    fun setup(): SetupView = assignments.setup()

    /** 204 when nothing is connected yet. */
    @RequiresPermission(RESOURCE, "read")
    @GetMapping("/connection")
    fun connection(): ResponseEntity<ConnectionView> =
        service.connection()?.let { ResponseEntity.ok(it) } ?: ResponseEntity.noContent().build()

    @RequiresPermission(RESOURCE, "connect")
    @PutMapping("/connection")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun connect(@RequestBody body: ConnectInput): ConnectionView = service.connect(body)

    @RequiresPermission(RESOURCE, "connect")
    @DeleteMapping("/connection")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun disconnect() = service.disconnect()

    @RequiresPermission(RESOURCE, "connect")
    @PostMapping("/collections")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun collect(): ConnectionView = service.collectNow()

    /** Asks someone, by email, to connect the repository. */
    @RequiresPermission(RESOURCE, "assign")
    @PutMapping("/assignment")
    fun assign(@RequestBody body: AssignInput): AssignmentView = assignments.assign(body)

    @RequiresPermission(RESOURCE, "assign")
    @DeleteMapping("/assignment")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun unassign() = assignments.unassign()

    /** Starts installing the aLinha GitHub App: the page sends the person to [AppInstallStart.installUrl]. */
    @RequiresPermission(RESOURCE, "connect")
    @PostMapping("/github-app/installations")
    @ResponseStatus(HttpStatus.CREATED)
    fun startAppInstall(): AppInstallStart = githubApp.start()

    /** What GitHub sent back after the installation; connects and starts a collection. */
    @RequiresPermission(RESOURCE, "connect")
    @PutMapping("/github-app/installation")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun completeAppInstall(@RequestBody body: AppInstallInput): ConnectionView = githubApp.complete(body)

    /** The evidence of the last completed collection. */
    @RequiresPermission(RESOURCE, "read")
    @GetMapping("/evidence")
    fun evidence(): List<EvidenceView> = service.latestEvidence()
}
