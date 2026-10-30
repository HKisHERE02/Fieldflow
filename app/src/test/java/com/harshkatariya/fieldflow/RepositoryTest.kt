package com.harshkatariya.fieldflow

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.harshkatariya.fieldflow.core.*
import com.harshkatariya.fieldflow.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RepositoryTest {
    private lateinit var context: Context
    private lateinit var provider: DatabaseProvider
    private lateinit var sessions: SessionStore
    private lateinit var repository: FieldRepository
    private val session = Session(Seed.technicians.first(), expiresAt = Long.MAX_VALUE, demo = true)
    @Before fun setup() = runTest {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("fieldflow-demo.db"); context.deleteDatabase("fieldflow-connected.db")
        provider = DatabaseProvider(context); sessions = SessionStore(context); sessions.session.value = session
        repository = FieldRepository(provider, sessions); repository.seedDemo()
    }
    @After fun close() { provider.get(true).close(); provider.get(false).close() }
    private suspend fun draft() = repository.start("wo-1")
    private suspend fun completed(): String { val id = draft(); Seed.history.first().answers.forEach { repository.answer(id, it) }; return id }
    @Test fun seedIsIdempotent() = runTest { repository.seedDemo(); val state = repository.observe(session).first(); assertEquals(12, state.assets.size); assertEquals(7, state.work.size); assertEquals(3, state.inspections.size) }
    @Test fun roleFiltering() = runTest { val other = session.copy(technician = Seed.technicians.last()); assertEquals(3, repository.observe(other).first().work.size); assertTrue(repository.observe(other).first().inspections.isEmpty()) }
    @Test fun unassignedWorkRejected() = runTest { assertTrue(runCatching { repository.start("wo-8") }.isFailure) }
    @Test fun draftResumesSameRecord() = runTest { assertEquals(draft(), draft()) }
    @Test fun draftAnswerPersists() = runTest { val id = draft(); repository.answer(id, Answer("visual", "PASS", "Clean")); val i = Wire.decodeFromString<Inspection>(provider.get(true).dao().inspection(id)!!.payload); assertEquals("Clean", i.answers.single().comment) }
    @Test fun defectPersists() = runTest { val id = draft(); repository.defect(id, "Loose mounting bolt", Severity.HIGH); assertEquals(Severity.HIGH, repository.observe(session).first().inspections.first { it.id == id }.defects.single().severity) }
    @Test fun invalidDefectRejected() = runTest { val id = draft(); assertTrue(runCatching { repository.defect(id, "bad", Severity.LOW) }.isFailure) }
    @Test fun requiredValidationPreventsQueue() = runTest { val id = draft(); assertTrue(runCatching { repository.submit(id) }.isFailure); assertNull(provider.get(true).dao().operationFor(id)) }
    @Test fun submissionAtomicAndIdempotent() = runTest { val id = completed(); repository.submit(id); val first = provider.get(true).dao().operationFor(id); repository.submit(id); assertEquals(first, provider.get(true).dao().operationFor(id)); assertEquals(InspectionStatus.SUBMITTED, repository.observe(session).first().inspections.first { it.id == id }.status) }
    @Test fun submittedRecordImmutable() = runTest { val id = completed(); repository.submit(id); assertTrue(runCatching { repository.answer(id, Answer("visual", "FAIL")) }.isFailure) }
    @Test fun queueSurvivesDatabaseReopen() = runTest {
        val id = completed(); repository.submit(id); provider.get(true).close()
        val reopened = Room.databaseBuilder(context, FieldDatabase::class.java, "fieldflow-demo.db").addMigrations(FieldDatabase.MIGRATION_1_2, FieldDatabase.MIGRATION_2_3).build()
        try { assertEquals("PENDING", reopened.dao().operationFor(id)!!.status); assertEquals(5, Wire.decodeFromString<Inspection>(reopened.dao().inspection(id)!!.payload).answers.size) } finally { reopened.close() }
    }
    @Test fun downloadDoesNotOverwriteDraft() = runTest {
        val id = completed(); val local = repository.observe(session).first().inspections.first { it.id == id }
        repository.merge(session, Changes(emptyList(), emptyList(), emptyList(), listOf(local.copy(answers = emptyList(), version = 8)), emptyList(), now()))
        assertEquals(5, repository.observe(session).first().inspections.first { it.id == id }.answers.size)
    }
    @Test fun retryKeepsOperationIdentity() = runTest { val id = completed(); repository.submit(id); val dao = provider.get(true).dao(); val op = dao.operationFor(id)!!; dao.operation(op.copy(status = "FAILED")); repository.retryFailed(); assertEquals(op.id, dao.pending(session.technician.id).single().id) }
    @Test fun conflictPreservesOriginalAndCreatesDraft() = runTest {
        val id = completed(); repository.submit(id); val dao = provider.get(true).dao(); val op = dao.operationFor(id)!!; dao.operation(op.copy(status = "CONFLICT"))
        val copyId = repository.resolveAsNew(op.id)
        assertNotEquals(id, copyId); assertEquals("CONFLICT", dao.operation(op.id)!!.status)
        assertEquals(5, Wire.decodeFromString<Inspection>(dao.inspection(copyId)!!.payload).answers.size)
        assertEquals(InspectionStatus.SUBMITTED, Wire.decodeFromString<Inspection>(dao.inspection(id)!!.payload).status)
    }
    @Test fun auditDerivedFromWrites() = runTest { val id = draft(); repository.answer(id, Answer("visual", "PASS")); assertTrue(repository.observe(session).first().audits.any { it.message.startsWith("Draft saved") }) }
    @Test fun expiredSessionCannotMutate() = runTest { sessions.session.value = session.copy(expiresAt = 0); assertTrue(runCatching { draft() }.isFailure) }
    @Test fun demoAndConnectedAreSeparate() = runTest { assertTrue(provider.get(false).dao().assets().first().isEmpty()) }
    @Test fun migrationPreservesQueuedInspection() = runTest {
        val id = completed(); repository.submit(id)
        val database = provider.get(true)
        downgradeQueueToVersion2(database)
        database.openHelper.writableDatabase.execSQL("DROP TABLE metadata")
        database.openHelper.writableDatabase.version = 1
        database.close()
        val reopened = Room.databaseBuilder(context, FieldDatabase::class.java, "fieldflow-demo.db").addMigrations(FieldDatabase.MIGRATION_1_2, FieldDatabase.MIGRATION_2_3).build()
        try {
            assertEquals("PENDING", reopened.dao().operationFor(id)!!.status)
            assertEquals(5, Wire.decodeFromString<Inspection>(reopened.dao().inspection(id)!!.payload).answers.size)
            reopened.dao().metadata(MetadataRow("migration-test", "ok"))
            assertEquals("ok", reopened.dao().metadata("migration-test"))
        } finally { reopened.close() }
    }
    @Test fun commentUpdateDoesNotRevertAnswer() = runTest {
        val id = draft()
        repository.answer(id, Answer("visual", "PASS"))
        repository.comment(id, "visual", "Clean housing")
        repository.answer(id, Answer("visual", "FAIL"))
        val answer = Wire.decodeFromString<Inspection>(provider.get(true).dao().inspection(id)!!.payload).answers.single()
        assertEquals("FAIL", answer.value); assertEquals("Clean housing", answer.comment)
    }
    @Test fun version2MigrationPreservesAndClassifiesFailedOperations() = runTest {
        val id = completed(); repository.submit(id)
        val database = provider.get(true); val dao = database.dao(); val original = dao.operationFor(id)!!
        dao.operation(original.copy(status = "FAILED", attempts = 8, error = "HTTP 400 Bad Request"))
        downgradeQueueToVersion2(database)
        database.openHelper.writableDatabase.version = 2
        database.close()
        val reopened = Room.databaseBuilder(context, FieldDatabase::class.java, "fieldflow-demo.db").addMigrations(FieldDatabase.MIGRATION_2_3).build()
        try {
            val migrated = reopened.dao().operationFor(id)!!
            assertEquals(original.id, migrated.id); assertEquals(original.payload, migrated.payload)
            assertEquals(8, migrated.attempts); assertEquals("FAILED", migrated.status); assertEquals("PERMANENT", migrated.failureKind)
        } finally { reopened.close() }
    }
    private fun downgradeQueueToVersion2(database: FieldDatabase) {
        val sql = database.openHelper.writableDatabase
        sql.execSQL("CREATE TABLE legacy_operations (id TEXT NOT NULL PRIMARY KEY, inspectionId TEXT NOT NULL, owner TEXT NOT NULL, payload TEXT NOT NULL, status TEXT NOT NULL, createdAt INTEGER NOT NULL, attempts INTEGER NOT NULL, error TEXT NOT NULL, serverPayload TEXT, FOREIGN KEY(inspectionId) REFERENCES inspections(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
        sql.execSQL("INSERT INTO legacy_operations SELECT id, inspectionId, owner, payload, status, createdAt, attempts, error, serverPayload FROM sync_operations")
        sql.execSQL("DROP TABLE sync_operations")
        sql.execSQL("ALTER TABLE legacy_operations RENAME TO sync_operations")
        sql.execSQL("CREATE UNIQUE INDEX index_sync_operations_inspectionId ON sync_operations(inspectionId)")
        sql.execSQL("CREATE INDEX index_sync_operations_owner ON sync_operations(owner)")
    }
}
