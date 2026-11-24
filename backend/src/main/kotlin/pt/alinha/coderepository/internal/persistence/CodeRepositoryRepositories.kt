package pt.alinha.coderepository.internal.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/** Tenant-filtered by Hibernate (TenantOwnedEntity): at most one row per tenant. */
interface ConnectionRepository : JpaRepository<ConnectionEntity, UUID> {
    fun findFirstBy(): ConnectionEntity?
}

interface EvidenceRepository : JpaRepository<EvidenceEntity, UUID> {
    fun existsByJobId(jobId: UUID): Boolean

    fun findByJobIdOrderByRepositoryAscCheckNameAsc(jobId: UUID): List<EvidenceEntity>
}
