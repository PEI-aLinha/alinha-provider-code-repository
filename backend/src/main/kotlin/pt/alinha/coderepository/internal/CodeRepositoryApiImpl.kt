package pt.alinha.coderepository.internal

import org.springframework.stereotype.Component
import pt.alinha.coderepository.api.CodeEvidence
import pt.alinha.coderepository.api.CodeRepositoryApi
import java.util.UUID

@Component
class CodeRepositoryApiImpl(private val service: ConnectionService) : CodeRepositoryApi {
    override fun evidence(evidenceId: UUID): CodeEvidence? = service.evidence(evidenceId)?.let {
        CodeEvidence(
            id = it.id,
            repository = it.repository,
            check = it.check.id,
            outcome = it.outcome.name,
            controlIds = it.controlIds,
            strength = it.strength,
            mappingVersion = it.mappingVersion,
            collectedAt = it.collectedAt,
        )
    }
}
