package com.harshkatariya.fieldflow.server

import com.harshkatariya.fieldflow.core.*
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import kotlinx.serialization.encodeToString
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

object Technicians : Table("technicians") { val id = varchar("id", 80); val email = varchar("email", 200); val name = varchar("name", 200); val hash = varchar("password_hash", 100); override val primaryKey = PrimaryKey(id) }
object Assets : Table("assets") { val id = varchar("id", 80); val payload = text("payload"); override val primaryKey = PrimaryKey(id) }
object Templates : Table("templates") { val id = varchar("id", 80); val payload = text("payload"); override val primaryKey = PrimaryKey(id) }
object Work : Table("work_orders") { val id = varchar("id", 80); val owner = varchar("owner", 80); val asset = varchar("asset_id", 80); val template = varchar("template_id", 80); val payload = text("payload"); override val primaryKey = PrimaryKey(id) }
object Inspections : Table("inspections") { val id = varchar("id", 80); val owner = varchar("owner", 80); val work = varchar("work_order_id", 80); val version = integer("version"); val payload = text("payload"); override val primaryKey = PrimaryKey(id) }
object Sessions : Table("sessions") { val hash = varchar("token_hash", 64); val owner = varchar("owner", 80); val expires = long("expires_at"); override val primaryKey = PrimaryKey(hash) }
object Receipts : Table("operation_receipts") { val id = varchar("id", 80); val owner = varchar("owner", 80); val requestHash = varchar("request_hash", 64); val reply = text("reply"); override val primaryKey = PrimaryKey(id) }
object Attachments : Table("attachments") { val id = varchar("id", 80); val owner = varchar("owner", 80); val inspection = varchar("inspection_id", 80); val work = varchar("work_order_id", 80); val hash = varchar("content_hash", 64); val content = text("content"); override val primaryKey = PrimaryKey(id) }
object Audits : Table("audit_events") { val id = varchar("id", 80); val owner = varchar("owner", 80); val inspection = varchar("inspection_id", 80).nullable(); val message = text("message"); val created = long("created_at"); override val primaryKey = PrimaryKey(id) }
class Denied : RuntimeException("Not found or not assigned to this technician")
class Invalid(message: String) : RuntimeException(message)
fun digest(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }

class Store(url: String, user: String, password: String) : AutoCloseable {
    private val pool = HikariDataSource(HikariConfig().apply { jdbcUrl = url; username = user; this.password = password; maximumPoolSize = 6 })
    val database: Database
    init {
        Flyway.configure().dataSource(pool).locations("classpath:db/migration").load().migrate()
        database = Database.connect(pool)
    }
    fun <T> tx(block: () -> T): T = transaction(database) { block() }
    fun seed(password: String, rounds: Int = 12) = tx {
        require(password.length >= 12) { "Development seed password must have at least 12 characters" }
        Seed.technicians.forEach { tech -> if (Technicians.selectAll().where { Technicians.id eq tech.id }.empty()) Technicians.insert { it[id] = tech.id; it[email] = tech.email; it[name] = tech.name; it[hash] = BCrypt.hashpw(password, BCrypt.gensalt(rounds)) } }
        Seed.assets.forEach { a -> if (Assets.selectAll().where { Assets.id eq a.id }.empty()) Assets.insert { it[id] = a.id; it[payload] = Wire.encodeToString(a) } }
        Seed.templates.forEach { t -> if (Templates.selectAll().where { Templates.id eq t.id }.empty()) Templates.insert { it[id] = t.id; it[payload] = Wire.encodeToString(t) } }
        Seed.workOrders.forEach { w -> if (Work.selectAll().where { Work.id eq w.id }.empty()) Work.insert { it[id] = w.id; it[owner] = w.technicianId; it[asset] = w.assetId; it[template] = w.templateId; it[payload] = Wire.encodeToString(w) } }
        Seed.history.forEach { i -> if (Inspections.selectAll().where { Inspections.id eq i.id }.empty()) {
            insertInspection(i); audit(i.technicianId, i.id, "Historical inspection imported · fictional seed")
        } }
    }
    private val dummyHash = BCrypt.hashpw("unmatched-password", BCrypt.gensalt(10))
    fun login(request: LoginRequest): Session? {
        if (request.email.length > 200 || request.password.length !in 1..72) return null
        val tech = tx { Technicians.selectAll().where { Technicians.email eq request.email.lowercase().trim() }.singleOrNull() }
        val matches = BCrypt.checkpw(request.password, tech?.get(Technicians.hash) ?: dummyHash)
        if (tech == null || !matches) return null
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val expiry = now() + 7 * 86_400_000L
        tx { Sessions.insert { it[hash] = digest(token.toByteArray()); it[owner] = tech[Technicians.id]; it[expires] = expiry } }
        return Session(Technician(tech[Technicians.id], tech[Technicians.name], tech[Technicians.email]), token, expiry)
    }
    fun authenticate(token: String): Technician? = tx {
        val session = Sessions.selectAll().where { Sessions.hash eq digest(token.toByteArray()) }.singleOrNull() ?: return@tx null
        if (session[Sessions.expires] <= now()) return@tx null
        Technicians.selectAll().where { Technicians.id eq session[Sessions.owner] }.single().let { Technician(it[Technicians.id], it[Technicians.name], it[Technicians.email]) }
    }
    fun logout(token: String) = tx { Sessions.deleteWhere { hash eq digest(token.toByteArray()) } }
    fun changes(owner: String): Changes = tx {
        val work = Work.selectAll().where { Work.owner eq owner }.limit(1000).map { Wire.decodeFromString<WorkOrder>(it[Work.payload]) }
        val assetIds = work.map { it.assetId }
        Changes(Assets.selectAll().where { Assets.id inList assetIds }.map { Wire.decodeFromString(it[Assets.payload]) }, work,
            Templates.selectAll().limit(100).map { Wire.decodeFromString(it[Templates.payload]) },
            Inspections.selectAll().where { Inspections.owner eq owner }.orderBy(Inspections.id).limit(1000).map { Wire.decodeFromString(it[Inspections.payload]) },
            Audits.selectAll().where { Audits.owner eq owner }.orderBy(Audits.created, SortOrder.DESC).limit(250).map { Audit(it[Audits.id], owner, it[Audits.inspection], it[Audits.message], it[Audits.created]) }, now())
    }
    private fun assigned(owner: String, workId: String): WorkOrder = Work.selectAll().where { Work.id eq workId }.forUpdate().singleOrNull()
        ?.takeIf { it[Work.owner] == owner }?.let { Wire.decodeFromString(it[Work.payload]) } ?: throw Denied()
    fun process(owner: String, op: Operation): SyncReply = tx {
        validId(op.id); validId(op.inspection.id)
        val i = op.inspection
        val work = assigned(owner, i.workOrderId)
        val fingerprint = digest(Wire.encodeToString(op).toByteArray())
        Receipts.selectAll().where { Receipts.id eq op.id }.singleOrNull()?.let {
            if (it[Receipts.owner] != owner) throw Denied()
            if (it[Receipts.requestHash] != fingerprint) throw Invalid("Operation ID already used with a different payload")
            return@tx Wire.decodeFromString<SyncReply>(it[Receipts.reply])
        }
        if (i.technicianId != owner || i.assetId != work.assetId || i.templateId != work.templateId) throw Denied()
        if (i.status != InspectionStatus.SUBMITTED || i.submittedAt == null || (i.submittedAt ?: 0) < i.startedAt) throw Invalid("A completed inspection with valid timestamps is required")
        val template = Wire.decodeFromString<Template>(Templates.selectAll().where { Templates.id eq i.templateId }.single()[Templates.payload])
        val errors = Rules.validate(i, template)
        if (errors.isNotEmpty()) throw Invalid(errors.joinToString("; "))
        if (i.answers.size > 100 || i.defects.size > 100 || i.photos.size > 30) throw Invalid("Inspection exceeds supported limits")
        i.photos.forEach { p ->
            val photo = Attachments.selectAll().where { Attachments.id eq p.id }.singleOrNull()
            if (photo == null || photo[Attachments.owner] != owner || photo[Attachments.inspection] != i.id || photo[Attachments.work] != i.workOrderId) throw Invalid("Photo ${p.id} must be uploaded first")
        }
        val existing = Inspections.selectAll().where { Inspections.id eq i.id }.singleOrNull()
        if (existing != null && (existing[Inspections.owner] != owner || existing[Inspections.work] != i.workOrderId)) throw Denied()
        val version = existing?.get(Inspections.version) ?: 0
        val reply = if (version != op.expectedVersion) {
            audit(owner, i.id, "Version conflict · expected ${op.expectedVersion}, actual $version")
            SyncReply(SyncStatus.CONFLICT, version, "Server version $version differs from expected ${op.expectedVersion}. Local work is preserved.", existing?.let { Wire.decodeFromString(it[Inspections.payload]) })
        } else {
            val saved = i.copy(version = version + 1)
            if (existing == null) insertInspection(saved) else Inspections.update({ Inspections.id eq i.id }) { it[Inspections.version] = saved.version; it[payload] = Wire.encodeToString(saved) }
            audit(owner, i.id, "Inspection synchronized · version ${saved.version}")
            SyncReply(SyncStatus.SYNCED, saved.version, "Inspection persisted")
        }
        Receipts.insert { it[id] = op.id; it[Receipts.owner] = owner; it[requestHash] = fingerprint; it[Receipts.reply] = Wire.encodeToString(reply) }
        reply
    }
    fun upload(owner: String, upload: AttachmentUpload): AttachmentReceipt = tx {
        assigned(owner, upload.workOrderId); validId(upload.id); validId(upload.inspectionId)
        val bytes = try { Base64.getDecoder().decode(upload.base64) } catch (_: IllegalArgumentException) { throw Invalid("Invalid base64 photo") }
        if (bytes.size !in 3..5 * 1024 * 1024 || bytes[0] != 0xff.toByte() || bytes[1] != 0xd8.toByte() || bytes[2] != 0xff.toByte()) throw Invalid("JPEG photo up to 5 MB required")
        val hash = digest(bytes)
        Attachments.selectAll().where { Attachments.id eq upload.id }.singleOrNull()?.let {
            if (it[Attachments.owner] != owner) throw Denied()
            if (it[Attachments.hash] != hash || it[Attachments.inspection] != upload.inspectionId || it[Attachments.work] != upload.workOrderId) throw Invalid("Attachment ID already used")
            return@tx AttachmentReceipt(upload.id)
        }
        Attachments.insert { it[id] = upload.id; it[Attachments.owner] = owner; it[inspection] = upload.inspectionId; it[work] = upload.workOrderId; it[Attachments.hash] = hash; it[content] = upload.base64 }
        AttachmentReceipt(upload.id)
    }
    fun photo(owner: String, id: String): ByteArray = tx {
        val row = Attachments.selectAll().where { Attachments.id eq id }.singleOrNull()?.takeIf { it[Attachments.owner] == owner } ?: throw Denied()
        Base64.getDecoder().decode(row[Attachments.content])
    }
    private fun insertInspection(i: Inspection) { Inspections.insert { it[id] = i.id; it[owner] = i.technicianId; it[work] = i.workOrderId; it[version] = i.version; it[payload] = Wire.encodeToString(i) } }
    private fun audit(owner: String, id: String?, message: String) { Audits.insert { it[Audits.id] = UUID.randomUUID().toString(); it[Audits.owner] = owner; it[inspection] = id; it[Audits.message] = message; it[created] = now() } }
    private fun validId(id: String) { if (!id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) throw Invalid("Invalid identifier") }
    fun healthy(): Boolean = tx { Technicians.selectAll().limit(1).toList(); true }
    override fun close() { pool.close() }
}
