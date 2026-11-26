package pt.alinha.coderepository.internal.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import pt.alinha.coderepository.internal.domain.CodeCheck
import pt.alinha.coderepository.internal.domain.Outcome
import pt.alinha.core.tenant.TenantOwnedEntity
import pt.alinha.pluginapi.EvidenceStrength
import java.time.Instant
import java.util.UUID

/** One check on one repository, from one collection. Insert-only. */
@Entity
@Table(schema = "coderepository", name = "evidence")
class EvidenceEntity(
    @Id val id: UUID,
    @Column(name = "connection_id", updatable = false) val connectionId: UUID?,
    @Column(name = "job_id", nullable = false, updatable = false) val jobId: UUID,
    @Column(nullable = false, updatable = false) val repository: String,
    check: CodeCheck,
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false) val outcome: Outcome,
    /** Counts and flags from the worker, already validated (CollectionResultParser). JSON object. */
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, updatable = false) val details: String,
    controlIds: Set<String>,
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false) val strength: EvidenceStrength,
    @Column(name = "mapping_version", nullable = false, updatable = false) val mappingVersion: Int,
    @Column(name = "collected_at", nullable = false, updatable = false) val collectedAt: Instant,
) : TenantOwnedEntity() {

    @Column(name = "check_name", nullable = false, updatable = false)
    private val checkName: String = check.id

    val check: CodeCheck get() = requireNotNull(CodeCheck.of(checkName)) { "unknown check $checkName" }

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "control_ids", columnDefinition = "text[]", nullable = false, updatable = false)
    private val controlIdArray: Array<String> = controlIds.sorted().toTypedArray()

    val controlIds: Set<String> get() = controlIdArray.toSet()
}
