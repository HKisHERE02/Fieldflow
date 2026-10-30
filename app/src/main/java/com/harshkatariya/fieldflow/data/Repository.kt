package com.harshkatariya.fieldflow.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import com.harshkatariya.fieldflow.core.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class LocalState(val assets: List<Asset> = emptyList(), val work: List<WorkOrder> = emptyList(), val templates: List<Template> = emptyList(),
    val inspections: List<Inspection> = emptyList(), val sync: Map<String, String> = emptyMap(), val operations: List<OperationRow> = emptyList(),
    val audits: List<AuditRow> = emptyList(), val photos: List<AttachmentRow> = emptyList())

@Singleton class DatabaseProvider @Inject constructor(@ApplicationContext private val context: Context) {
    private val databases = mutableMapOf<Boolean, FieldDatabase>()
    @Synchronized fun get(demo: Boolean): FieldDatabase = databases.getOrPut(demo) {
        Room.databaseBuilder(context, FieldDatabase::class.java, if (demo) "fieldflow-demo.db" else "fieldflow-connected.db")
            .addMigrations(FieldDatabase.MIGRATION_1_2, FieldDatabase.MIGRATION_2_3).build()
    }
}

@Singleton class FieldRepository @Inject constructor(private val databases: DatabaseProvider, private val sessions: SessionStore) {
    fun database(session: Session) = databases.get(session.demo)
    private fun active(): Session = requireNotNull(sessions.session.value).also { require(Rules.validSession(it, now())) { "Session expired. Sign in again." } }
    fun observe(session: Session): Flow<LocalState> {
        val dao = database(session).dao()
        val base = combine(dao.assets(), dao.work(session.technician.id), dao.templates(), dao.inspections(session.technician.id)) { assets, work, templates, inspections ->
            val assignedAssets = work.map { it.assetId }.toSet()
            LocalState(assets.map { Wire.decodeFromString<Asset>(it.payload) }.filter { session.demo || it.id in assignedAssets },
                work.map { Wire.decodeFromString<WorkOrder>(it.payload) }.sortedBy { it.dueAt }, templates.map { Wire.decodeFromString<Template>(it.payload) },
                inspections.map { Wire.decodeFromString<Inspection>(it.payload) }.sortedByDescending { it.startedAt }, inspections.associate { it.id to it.syncStatus })
        }
        return combine(base, dao.operations(session.technician.id), dao.audits(session.technician.id), dao.attachments()) { state, ops, audits, photos ->
            state.copy(operations = ops, audits = audits, photos = photos.filter { p -> state.inspections.any { it.id == p.inspectionId } })
        }
    }
    suspend fun seedDemo() = seed(database(Session(Seed.technicians.first(), expiresAt = Long.MAX_VALUE, demo = true)))
    suspend fun seed(db: FieldDatabase) = db.withTransaction {
        val dao = db.dao()
        if (dao.metadata("seed-v1") != null) return@withTransaction
        dao.technicians(Seed.technicians.map { TechnicianRow(it.id, Wire.encodeToString(it)) })
        dao.assets(Seed.assets.map { AssetRow(it.id, Wire.encodeToString(it)) })
        dao.templates(Seed.templates.map { TemplateRow(it.id, Wire.encodeToString(it)) })
        dao.work(Seed.workOrders.map { WorkRow(it.id, it.assetId, it.templateId, it.technicianId, Wire.encodeToString(it)) })
        Seed.history.forEach { dao.inspection(it.row("SYNCED")) }
        dao.metadata(MetadataRow("seed-v1", "complete"))
    }
    suspend fun start(workId: String): String {
        val session = active(); val db = database(session); val dao = db.dao()
        return db.withTransaction {
            val work = Wire.decodeFromString<WorkOrder>(requireNotNull(dao.workById(workId)).payload)
            require(work.technicianId == session.technician.id) { "This work is assigned to another technician" }
            dao.forWork(workId, session.technician.id).map { Wire.decodeFromString<Inspection>(it.payload) }
                .firstOrNull { it.status == InspectionStatus.DRAFT }?.let { return@withTransaction it.id }
            val inspection = Inspection(UUID.randomUUID().toString(), work.id, work.assetId, session.technician.id, work.templateId, startedAt = now())
            dao.inspection(inspection.row("PENDING")); audit(dao, inspection, "Inspection started")
            inspection.id
        }
    }
    private suspend fun edit(id: String, message: String, transform: (Inspection) -> Inspection) {
        val session = active(); val db = database(session)
        db.withTransaction {
            val original = owned(db.dao(), id, session)
            require(original.status == InspectionStatus.DRAFT) { "Submitted inspections are immutable" }
            val next = transform(original)
            db.dao().inspection(next.row("PENDING")); audit(db.dao(), next, message)
        }
    }
    suspend fun answer(id: String, answer: Answer) = edit(id, "Draft saved · ${answer.questionId}") {
        val existing = it.answers.find { a -> a.questionId == answer.questionId }
        it.copy(answers = it.answers.filterNot { a -> a.questionId == answer.questionId } + answer.copy(comment = existing?.comment ?: answer.comment))
    }
    suspend fun comment(id: String, questionId: String, comment: String) = edit(id, "Draft saved · $questionId comment") {
        val existing = it.answers.find { a -> a.questionId == questionId } ?: Answer(questionId, "")
        it.copy(answers = it.answers.filterNot { a -> a.questionId == questionId } + existing.copy(comment = comment))
    }
    suspend fun defect(id: String, description: String, severity: Severity): String {
        require(description.trim().length in 5..4000) { "Enter a description of 5–4000 characters" }
        val defect = Defect(UUID.randomUUID().toString(), description.trim(), severity, now())
        edit(id, "Defect created · ${severity.name}") { it.copy(defects = it.defects + defect) }
        return defect.id
    }
    suspend fun location(id: String, lat: Double, lon: Double) = edit(id, "Location attached by technician") { it.copy(latitude = lat, longitude = lon) }
    suspend fun photo(id: String, photo: Photo, path: String) {
        val session = active(); val db = database(session)
        db.withTransaction {
            val original = owned(db.dao(), id, session)
            require(original.status == InspectionStatus.DRAFT)
            db.dao().attachment(AttachmentRow(photo.id, id, path))
            db.dao().inspection(original.copy(photos = original.photos + photo).row("PENDING"))
            audit(db.dao(), original, "Photo evidence saved")
        }
    }
    suspend fun submit(id: String) {
        val session = active(); val db = database(session); val dao = db.dao()
        val snapshot = db.withTransaction {
            val original = owned(dao, id, session)
            if (original.status == InspectionStatus.SUBMITTED && dao.operationFor(id) != null) null
            else {
                val template = Wire.decodeFromString<Template>(requireNotNull(dao.template(original.templateId)).payload)
                Rules.submit(original, template, now())
                val attachments = dao.photos(id).filter { row -> original.photos.any { it.id == row.id } }
                require(attachments.map { it.id }.toSet().containsAll(original.photos.map { it.id })) { "Photo evidence is missing from device storage. Recapture it before submitting." }
                Triple(original, template, attachments)
            }
        } ?: return
        snapshot.third.forEach { PhotoEvidence.read(it) }
        db.withTransaction {
            require(active() == session) { "Session changed. Sign in and review the draft again." }
            val original = owned(dao, id, session)
            if (original.status == InspectionStatus.SUBMITTED && dao.operationFor(id) != null) return@withTransaction
            val template = Wire.decodeFromString<Template>(requireNotNull(dao.template(original.templateId)).payload)
            val attachments = dao.photos(id).filter { row -> original.photos.any { it.id == row.id } }
            require(original == snapshot.first && template == snapshot.second && attachments.toSet() == snapshot.third.toSet()) {
                "The draft changed while checking evidence. Review it and submit again."
            }
            val next = Rules.submit(original, template, now())
            val op = Operation(UUID.randomUUID().toString(), next, next.version, now())
            dao.inspection(next.row("PENDING"))
            dao.operation(OperationRow(op.id, id, session.technician.id, Wire.encodeToString(op), createdAt = op.createdAt))
            audit(dao, next, "Inspection submitted · queued locally")
        }
    }
    suspend fun merge(session: Session, changes: Changes) {
        val db = database(session); val dao = db.dao()
        db.withTransaction {
            dao.technicians(listOf(TechnicianRow(session.technician.id, Wire.encodeToString(session.technician))))
            dao.assets(changes.assets.map { AssetRow(it.id, Wire.encodeToString(it)) })
            dao.templates(changes.templates.map { TemplateRow(it.id, Wire.encodeToString(it)) })
            dao.work(changes.workOrders.filter { it.technicianId == session.technician.id }.map { WorkRow(it.id, it.assetId, it.templateId, it.technicianId, Wire.encodeToString(it)) })
            changes.inspections.filter { it.technicianId == session.technician.id }.forEach { incoming ->
                val existing = dao.inspection(incoming.id)
                // Never replace drafts, pending, failed, or conflicted local work with a server snapshot.
                if (existing == null || existing.syncStatus == "SYNCED") dao.inspection(incoming.row("SYNCED"))
            }
            changes.audits.filter { it.technicianId == session.technician.id }.forEach { dao.audit(AuditRow(it.id, it.technicianId, it.inspectionId, it.message, it.createdAt)) }
        }
    }
    suspend fun retryFailed(afterReauthentication: Boolean = false) {
        val s = active(); val db = database(s); val dao = db.dao()
        val kinds = if (afterReauthentication) listOf("AUTH") else listOf("TRANSIENT", "EVIDENCE", "UNKNOWN", "")
        db.withTransaction {
            dao.retryableFailures(s.technician.id, kinds).forEach { row ->
                dao.operation(row.copy(status = "PENDING", attempts = 0, error = "", failureKind = ""))
                val inspection = requireNotNull(dao.inspection(row.inspectionId))
                dao.inspection(inspection.copy(syncStatus = "PENDING"))
                audit(dao, Wire.decodeFromString(inspection.payload), if (afterReauthentication) "Sync resumed after sign-in" else "Manual retry · attempt budget reset")
            }
        }
    }
    suspend fun resolveAsNew(operationId: String): String {
        val s = active(); val db = database(s); val dao = db.dao()
        return db.withTransaction {
            val row = requireNotNull(dao.operation(operationId)); require(row.owner == s.technician.id && row.status == "CONFLICT")
            val old = Wire.decodeFromString<Operation>(row.payload).inspection
            val copy = old.copy(id = UUID.randomUUID().toString(), status = InspectionStatus.DRAFT, version = 0, submittedAt = null, startedAt = now(), photos = emptyList())
            dao.inspection(copy.row("PENDING"))
            // The original conflict and evidence remain intact; a new record requires explicit review and fresh evidence.
            audit(dao, copy, "Conflict resolution · copied ${old.id} to a new draft")
            copy.id
        }
    }
    private suspend fun owned(dao: FieldDao, id: String, session: Session): Inspection {
        val row = requireNotNull(dao.inspection(id)); require(row.owner == session.technician.id)
        return Wire.decodeFromString(row.payload)
    }
    suspend fun audit(dao: FieldDao, inspection: Inspection, message: String) { dao.audit(AuditRow(UUID.randomUUID().toString(), inspection.technicianId, inspection.id, message, now())) }
}
fun Inspection.row(sync: String) = InspectionRow(id, workOrderId, technicianId, Wire.encodeToString(this), sync)
