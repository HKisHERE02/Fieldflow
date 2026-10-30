package com.harshkatariya.fieldflow.data

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "assets") data class AssetRow(@PrimaryKey val id: String, val payload: String)
@Entity(tableName = "templates") data class TemplateRow(@PrimaryKey val id: String, val payload: String)
@Entity(tableName = "technicians") data class TechnicianRow(@PrimaryKey val id: String, val payload: String)
@Entity(tableName = "work_orders", foreignKeys = [ForeignKey(entity = AssetRow::class, parentColumns = ["id"], childColumns = ["assetId"]), ForeignKey(entity = TemplateRow::class, parentColumns = ["id"], childColumns = ["templateId"]), ForeignKey(entity = TechnicianRow::class, parentColumns = ["id"], childColumns = ["owner"])], indices = [Index("assetId"), Index("templateId"), Index("owner")])
data class WorkRow(@PrimaryKey val id: String, val assetId: String, val templateId: String, val owner: String, val payload: String)
@Entity(tableName = "inspections", foreignKeys = [ForeignKey(entity = WorkRow::class, parentColumns = ["id"], childColumns = ["workOrderId"])], indices = [Index("workOrderId"), Index("owner")])
data class InspectionRow(@PrimaryKey val id: String, val workOrderId: String, val owner: String, val payload: String, val syncStatus: String)
@Entity(tableName = "attachments", foreignKeys = [ForeignKey(entity = InspectionRow::class, parentColumns = ["id"], childColumns = ["inspectionId"])], indices = [Index("inspectionId")])
data class AttachmentRow(@PrimaryKey val id: String, val inspectionId: String, val path: String, val uploaded: Boolean = false)
@Entity(tableName = "sync_operations", foreignKeys = [ForeignKey(entity = InspectionRow::class, parentColumns = ["id"], childColumns = ["inspectionId"])], indices = [Index(value = ["inspectionId"], unique = true), Index("owner")])
data class OperationRow(@PrimaryKey val id: String, val inspectionId: String, val owner: String, val payload: String,
    val status: String = "PENDING", val createdAt: Long, val attempts: Int = 0, val error: String = "", val serverPayload: String? = null,
    @ColumnInfo(defaultValue = "''") val failureKind: String = "")
@Entity(tableName = "audit_events", indices = [Index("owner")])
data class AuditRow(@PrimaryKey val id: String, val owner: String, val inspectionId: String?, val message: String, val createdAt: Long)
@Entity(tableName = "metadata") data class MetadataRow(@PrimaryKey val key: String, val value: String)

@Dao interface FieldDao {
    @Query("SELECT * FROM assets ORDER BY id") fun assets(): Flow<List<AssetRow>>
    @Query("SELECT * FROM work_orders WHERE owner=:owner") fun work(owner: String): Flow<List<WorkRow>>
    @Query("SELECT * FROM templates") fun templates(): Flow<List<TemplateRow>>
    @Query("SELECT * FROM inspections WHERE owner=:owner") fun inspections(owner: String): Flow<List<InspectionRow>>
    @Query("SELECT * FROM sync_operations WHERE owner=:owner ORDER BY createdAt") fun operations(owner: String): Flow<List<OperationRow>>
    @Query("SELECT * FROM audit_events WHERE owner=:owner ORDER BY createdAt DESC LIMIT 250") fun audits(owner: String): Flow<List<AuditRow>>
    @Query("SELECT * FROM attachments") fun attachments(): Flow<List<AttachmentRow>>
    @Query("SELECT * FROM work_orders WHERE id=:id") suspend fun workById(id: String): WorkRow?
    @Query("SELECT * FROM templates WHERE id=:id") suspend fun template(id: String): TemplateRow?
    @Query("SELECT * FROM inspections WHERE id=:id") suspend fun inspection(id: String): InspectionRow?
    @Query("SELECT * FROM inspections WHERE workOrderId=:workId AND owner=:owner") suspend fun forWork(workId: String, owner: String): List<InspectionRow>
    @Query("SELECT * FROM attachments WHERE inspectionId=:id") suspend fun photos(id: String): List<AttachmentRow>
    @Query("SELECT * FROM sync_operations WHERE owner=:owner AND status IN ('PENDING','SYNCING') ORDER BY createdAt LIMIT 100") suspend fun pending(owner: String): List<OperationRow>
    @Query("SELECT * FROM sync_operations WHERE id=:id") suspend fun operation(id: String): OperationRow?
    @Query("SELECT * FROM sync_operations WHERE inspectionId=:id") suspend fun operationFor(id: String): OperationRow?
    @Query("SELECT value FROM metadata WHERE `key`=:key") suspend fun metadata(key: String): String?
    @Upsert suspend fun assets(rows: List<AssetRow>)
    @Upsert suspend fun templates(rows: List<TemplateRow>)
    @Upsert suspend fun technicians(rows: List<TechnicianRow>)
    @Upsert suspend fun work(rows: List<WorkRow>)
    @Upsert suspend fun inspection(row: InspectionRow)
    @Upsert suspend fun attachment(row: AttachmentRow)
    @Upsert suspend fun operation(row: OperationRow)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun audit(row: AuditRow)
    @Upsert suspend fun metadata(row: MetadataRow)
    @Query("SELECT * FROM sync_operations WHERE owner=:owner AND status='FAILED' AND failureKind IN (:kinds)") suspend fun retryableFailures(owner: String, kinds: List<String>): List<OperationRow>
    @Query("DELETE FROM sync_operations WHERE id=:id") suspend fun deleteOperation(id: String)
}

@Database(entities = [AssetRow::class, TemplateRow::class, TechnicianRow::class, WorkRow::class, InspectionRow::class, AttachmentRow::class, OperationRow::class, AuditRow::class, MetadataRow::class], version = 3, exportSchema = true)
abstract class FieldDatabase : RoomDatabase() {
    abstract fun dao(): FieldDao
    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_operations ADD COLUMN failureKind TEXT NOT NULL DEFAULT ''")
                // Legacy rows have no structured cause. Recognize known HTTP errors without changing their payloads.
                db.execSQL("UPDATE sync_operations SET failureKind='AUTH' WHERE status='FAILED' AND (error LIKE 'Session expired.%' OR error LIKE 'HTTP 401%')")
                db.execSQL("UPDATE sync_operations SET failureKind='PERMANENT' WHERE status='FAILED' AND error LIKE 'HTTP 4%' AND error NOT LIKE 'HTTP 401%' AND error NOT LIKE 'HTTP 408%' AND error NOT LIKE 'HTTP 429%'")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("CREATE TABLE IF NOT EXISTS metadata (`key` TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(`key`))") }
        }
    }
}
