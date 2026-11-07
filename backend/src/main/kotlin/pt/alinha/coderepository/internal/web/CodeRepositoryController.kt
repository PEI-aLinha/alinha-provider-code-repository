package pt.alinha.coderepository.internal.web

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/code-repository")
class CodeRepositoryController {

    /** Walking skeleton: proves the plugin is wired end to end. */
    @GetMapping("/ping")
    fun ping() = mapOf("plugin" to "code-repository", "status" to "ok")
}
