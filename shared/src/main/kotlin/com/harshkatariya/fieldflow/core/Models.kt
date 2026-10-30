package com.harshkatariya.fieldflow.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

val Wire = Json { ignoreUnknownKeys = true; encodeDefaults = true }
fun now(): Long = Instant.now().toEpochMilli()
@Serializable enum class QuestionType { PASS_FAIL_NA, YES_NO, TEXT, NUMBER }
@Serializable enum class Severity { LOW, MEDIUM, HIGH, CRITICAL }
@Serializable enum class SyncStatus { PENDING, SYNCING, SYNCED, FAILED, CONFLICT }
@Serializable enum class InspectionStatus { DRAFT, SUBMITTED }
@Serializable data class Technician(val id: String, val name: String, val email: String)
@Serializable data class Asset(val id: String, val code: String, val name: String, val category: String, val location: String,
    val model: String, val status: String, val lastInspected: Long, val nextInspection: Long, val version: Int = 1)
@Serializable data class WorkOrder(val id: String, val assetId: String, val technicianId: String, val templateId: String,
    val title: String, val priority: Severity, val dueAt: Long, val status: String = "ASSIGNED", val version: Int = 1)
@Serializable data class Question(val id: String, val label: String, val type: QuestionType, val required: Boolean = true,
    val photoRequired: Boolean = false, val unit: String = "", val min: Double? = null, val max: Double? = null)
@Serializable data class Template(val id: String, val name: String, val questions: List<Question>, val version: Int = 1)
@Serializable data class Answer(val questionId: String, val value: String, val comment: String = "")
@Serializable data class Defect(val id: String, val description: String, val severity: Severity, val createdAt: Long)
@Serializable data class Photo(val id: String, val questionId: String? = null, val defectId: String? = null)
@Serializable data class Inspection(val id: String, val workOrderId: String, val assetId: String, val technicianId: String,
    val templateId: String, val answers: List<Answer> = emptyList(), val defects: List<Defect> = emptyList(),
    val photos: List<Photo> = emptyList(), val latitude: Double? = null, val longitude: Double? = null,
    val status: InspectionStatus = InspectionStatus.DRAFT, val startedAt: Long, val submittedAt: Long? = null, val version: Int = 0)
@Serializable data class Operation(val id: String, val inspection: Inspection, val expectedVersion: Int, val createdAt: Long)
@Serializable data class SyncReply(val status: SyncStatus, val version: Int, val message: String, val server: Inspection? = null)
@Serializable data class Audit(val id: String, val technicianId: String, val inspectionId: String?, val message: String, val createdAt: Long)
@Serializable data class Changes(val assets: List<Asset>, val workOrders: List<WorkOrder>, val templates: List<Template>,
    val inspections: List<Inspection>, val audits: List<Audit>, val serverTime: Long)
@Serializable data class LoginRequest(val email: String, val password: String)
@Serializable data class Session(val technician: Technician, val token: String = "", val expiresAt: Long, val demo: Boolean = false)
@Serializable data class ApiError(val message: String)
@Serializable data class Health(val status: String)
@Serializable data class AttachmentUpload(val id: String, val inspectionId: String, val workOrderId: String, val base64: String)
@Serializable data class AttachmentReceipt(val id: String)

object Rules {
    fun validate(inspection: Inspection, template: Template): List<String> = buildList {
        if (inspection.templateId != template.id) add("Template does not match")
        if (inspection.answers.map { it.questionId }.distinct().size != inspection.answers.size) add("Duplicate answers")
        if (inspection.answers.any { a -> template.questions.none { it.id == a.questionId } }) add("Unknown question")
        template.questions.forEach { q ->
            val value = inspection.answers.find { it.questionId == q.id }?.value.orEmpty().trim()
            if (q.required && value.isEmpty()) add("${q.label}: response required")
            if (value.isNotEmpty()) when (q.type) {
                QuestionType.PASS_FAIL_NA -> if (value !in listOf("PASS", "FAIL", "N/A")) add("${q.label}: invalid choice")
                QuestionType.YES_NO -> if (value !in listOf("YES", "NO")) add("${q.label}: invalid choice")
                QuestionType.NUMBER -> {
                    val n = value.toDoubleOrNull()
                    if (n == null || !n.isFinite() || (q.min != null && n < q.min) || (q.max != null && n > q.max)) add("${q.label}: number outside allowed range")
                }
                QuestionType.TEXT -> if (value.length > 4000) add("${q.label}: maximum 4000 characters")
            }
            if (q.photoRequired && inspection.photos.none { it.questionId == q.id }) add("${q.label}: photo required")
        }
        if (inspection.answers.any { it.value == "FAIL" } && inspection.defects.isEmpty()) add("A failed check requires a defect report")
        if (inspection.defects.any { it.description.trim().length !in 5..4000 }) add("Defect description must contain 5–4000 characters")
        if (inspection.photos.any { p -> p.questionId != null && template.questions.none { it.id == p.questionId } }) add("Photo references unknown question")
        if (inspection.photos.any { p -> p.defectId != null && inspection.defects.none { it.id == p.defectId } }) add("Photo references unknown defect")
        if (inspection.photos.map { it.id }.distinct().size != inspection.photos.size) add("Duplicate photos")
        if ((inspection.latitude == null) != (inspection.longitude == null)) add("Incomplete location")
        if (inspection.latitude?.let { !it.isFinite() || it !in -90.0..90.0 } == true ||
            inspection.longitude?.let { !it.isFinite() || it !in -180.0..180.0 } == true) add("Invalid location")
    }
    fun submit(inspection: Inspection, template: Template, timestamp: Long): Inspection {
        require(inspection.status == InspectionStatus.DRAFT) { "Inspection already submitted" }
        val errors = validate(inspection, template)
        require(errors.isEmpty()) { errors.joinToString("\n") }
        return inspection.copy(status = InspectionStatus.SUBMITTED, submittedAt = timestamp)
    }
    fun retryable(code: Int): Boolean = code == 408 || code == 429 || code >= 500
    fun matches(asset: Asset, query: String): Boolean = listOf(asset.code, asset.name, asset.location, asset.category).any { it.contains(query.trim(), true) }
    fun validSession(session: Session, timestamp: Long): Boolean = timestamp < session.expiresAt
}
