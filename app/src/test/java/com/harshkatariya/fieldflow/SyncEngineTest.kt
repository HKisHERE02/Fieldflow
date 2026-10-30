package com.harshkatariya.fieldflow

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.harshkatariya.fieldflow.core.*
import com.harshkatariya.fieldflow.data.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlinx.coroutines.flow.first
import retrofit2.Response
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SyncEngineTest {
    private lateinit var databases: DatabaseProvider
    private lateinit var sessions: SessionStore
    private lateinit var repository: FieldRepository
    private lateinit var api: FakeApi
    private lateinit var engine: SyncEngine
    private lateinit var id: String
    private val session = Session(Seed.technicians.first(), "test-token", Long.MAX_VALUE)
    @Before fun setup() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("fieldflow-connected.db")
        databases = DatabaseProvider(context); sessions = SessionStore(context); sessions.session.value = session
        repository = FieldRepository(databases, sessions); api = FakeApi(); engine = SyncEngine(repository, api, sessions)
        repository.merge(session, api.snapshot)
        id = repository.start("wo-1"); Seed.history.first().answers.forEach { repository.answer(id, it) }; repository.submit(id)
    }
    @After fun close() { databases.get(false).close() }
    @Test fun networkFailureRetainsStableOperation() = runTest {
        val dao = databases.get(false).dao(); val original = dao.operationFor(id)!!
        api.failure = IOException("Network unavailable")
        assertFalse(engine.run()); val pending = dao.operationFor(id)!!
        assertEquals(original.id, pending.id); assertEquals(original.payload, pending.payload); assertEquals("PENDING", pending.status); assertEquals(1, pending.attempts)
    }
    @Test fun successfulSyncMarksLocalAndStopsReplaying() = runTest {
        assertTrue(engine.run()); assertEquals("SYNCED", databases.get(false).dao().inspection(id)!!.syncStatus)
        assertTrue(engine.run()); assertEquals(1, api.calls)
    }
    @Test fun structuredConflictKeepsLocalAnswers() = runTest {
        val original = Wire.decodeFromString<Inspection>(databases.get(false).dao().inspection(id)!!.payload)
        api.reply = Response.error(409, Wire.encodeToString(SyncReply(SyncStatus.CONFLICT, 4, "Version changed", original.copy(version = 4))).toResponseBody("application/json".toMediaType()))
        assertTrue(engine.run()); val op = databases.get(false).dao().operationFor(id)!!
        assertEquals("CONFLICT", op.status); assertNotNull(op.serverPayload)
        assertEquals(original.answers, Wire.decodeFromString<Inspection>(databases.get(false).dao().inspection(id)!!.payload).answers)
    }
    @Test fun validationFailureRemainsVisibleWithoutAutomaticOrBatchRetry() = runTest {
        api.reply = Response.error(400, "{\"message\":\"invalid\"}".toResponseBody("application/json".toMediaType()))
        assertTrue(engine.run()); assertEquals("FAILED", databases.get(false).dao().operationFor(id)!!.status)
        val failed = databases.get(false).dao().operationFor(id)!!
        assertEquals("PERMANENT", failed.failureKind)
        repository.retryFailed(); repository.retryFailed(afterReauthentication = true)
        repeat(3) { assertTrue(engine.run()) }
        assertEquals(1, api.calls); assertEquals(failed, databases.get(false).dao().operationFor(id))
        assertTrue(repository.observe(session).first().operations.any { it.id == failed.id && it.status == "FAILED" })
    }
    @Test fun unauthorizedSessionPreservesQueueAndLogsOut() = runTest {
        api.reply = Response.error(401, "{}".toResponseBody("application/json".toMediaType()))
        assertTrue(engine.run()); assertNull(sessions.session.value); assertEquals("FAILED", databases.get(false).dao().operationFor(id)!!.status)
    }
    @Test fun crashedSyncingOperationIsRecovered() = runTest {
        val dao = databases.get(false).dao(); val row = dao.operationFor(id)!!; dao.operation(row.copy(status = "SYNCING"))
        assertTrue(engine.run()); assertEquals("SYNCED", dao.operationFor(id)!!.status); assertEquals(row.id, api.lastId)
    }
    @Test fun attemptsEventuallyRequireExplicitRetry() = runTest {
        val dao = databases.get(false).dao(); val row = dao.operationFor(id)!!; dao.operation(row.copy(attempts = 7))
        api.failure = IOException("Offline")
        assertTrue(engine.run()); assertEquals("FAILED", dao.operationFor(id)!!.status)
    }
    @Test fun eightTransientFailuresThenManualRetryResetsBudgetAndSucceeds() = runTest {
        val dao = databases.get(false).dao(); val original = dao.operationFor(id)!!
        api.failure = IOException("Offline")
        repeat(8) { attempt ->
            assertEquals(attempt == 7, engine.run())
            val row = dao.operationFor(id)!!
            assertEquals(original.id, row.id); assertEquals(original.payload, row.payload)
            assertEquals(original.inspectionId, row.inspectionId); assertEquals(attempt + 1, row.attempts)
        }
        val visible = repository.observe(session).first()
        assertEquals("FAILED", visible.operations.single().status); assertEquals("FAILED", visible.sync[id])
        assertTrue(engine.run()); assertEquals(8, api.calls)
        repository.retryFailed()
        val retried = dao.operationFor(id)!!
        assertEquals(0, retried.attempts); assertEquals("PENDING", retried.status)
        assertEquals("PENDING", dao.inspection(id)!!.syncStatus)
        assertEquals(original.id, retried.id); assertEquals(original.payload, retried.payload)
        assertFalse(engine.run()); assertEquals("PENDING", dao.operationFor(id)!!.status)
        api.failure = null
        assertTrue(engine.run()); assertEquals("SYNCED", dao.operationFor(id)!!.status)
        assertEquals(original.id, api.lastId); assertEquals(original.payload, dao.operationFor(id)!!.payload)
        assertEquals(1, repository.observe(session).first().operations.size)
    }
    @Test fun reauthenticationOnlyResumesAuthenticationFailuresForSameOwner() = runTest {
        val dao = databases.get(false).dao(); val original = dao.operationFor(id)!!
        dao.operation(original.copy(attempts = 7))
        api.reply = Response.error(401, "{}".toResponseBody("application/json".toMediaType()))
        assertTrue(engine.run()); assertNull(sessions.session.value)
        assertEquals("AUTH", dao.operationFor(id)!!.failureKind)
        sessions.session.value = session.copy(technician = Seed.technicians.last(), token = "other-user-token")
        repository.retryFailed(afterReauthentication = true)
        assertEquals("FAILED", dao.operationFor(id)!!.status)
        sessions.session.value = session.copy(token = "fresh-token")
        repository.retryFailed()
        assertEquals("FAILED", dao.operationFor(id)!!.status)
        repository.retryFailed(afterReauthentication = true)
        assertEquals(0, dao.operationFor(id)!!.attempts)
        api.reply = Response.success(SyncReply(SyncStatus.SYNCED, 1, "Saved"))
        assertTrue(engine.run()); assertEquals("Bearer fresh-token", api.lastToken)
        assertEquals(original.id, api.lastId); assertEquals(original.payload, dao.operationFor(id)!!.payload)
    }
    @Test fun reauthenticationDoesNotResetUnrelatedTransientFailure() = runTest {
        val dao = databases.get(false).dao(); val row = dao.operationFor(id)!!
        dao.operation(row.copy(status = "FAILED", attempts = 8, failureKind = "TRANSIENT"))
        repository.retryFailed(afterReauthentication = true)
        assertEquals(8, dao.operationFor(id)!!.attempts); assertEquals("FAILED", dao.operationFor(id)!!.status)
    }
    @Test fun lateUnauthorizedResponseDoesNotClearNewSession() = runTest {
        val fresh = session.copy(token = "newly-authenticated-token")
        api.beforeOperation = { sessions.session.value = fresh }
        api.reply = Response.error(401, "{}".toResponseBody("application/json".toMediaType()))
        assertTrue(engine.run())
        assertEquals(fresh, sessions.session.value)
        assertEquals("AUTH", databases.get(false).dao().operationFor(id)!!.failureKind)
    }
    @Test fun manualRetryPreservesConflictAndServerSnapshot() = runTest {
        val dao = databases.get(false).dao(); val row = dao.operationFor(id)!!.copy(status = "CONFLICT", serverPayload = "saved server evidence")
        dao.operation(row); repository.retryFailed(); repository.retryFailed(afterReauthentication = true)
        assertEquals(row, dao.operationFor(id))
    }
    @Test fun disappearingPhotoIsVisibleAndRecoverableWithoutChangingOperation() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val draft = repository.start("wo-1")
        Seed.history.first().answers.forEach { repository.answer(draft, it) }
        val file = java.io.File(context.filesDir, "sync-evidence.jpg"); writeJpeg(file)
        val bytes = file.readBytes()
        repository.photo(draft, Photo("sync-evidence"), file.absolutePath); repository.submit(draft)
        val dao = databases.get(false).dao(); val original = dao.operationFor(draft)!!
        assertTrue(file.delete()); assertTrue(engine.run())
        val failed = dao.operationFor(draft)!!
        assertEquals("FAILED", failed.status); assertEquals("EVIDENCE", failed.failureKind)
        assertTrue(failed.error.contains("Restore")); assertEquals(original.payload, failed.payload)
        assertEquals(1, dao.photos(draft).size); assertEquals(0, api.uploads)
        file.writeBytes(bytes); repository.retryFailed(); assertTrue(engine.run())
        assertEquals("SYNCED", dao.operationFor(draft)!!.status); assertEquals(original.id, dao.operationFor(draft)!!.id)
        assertEquals(original.payload, dao.operationFor(draft)!!.payload); assertEquals(1, api.uploads)
        assertArrayEquals(bytes, file.readBytes())
    }
    private class FakeApi : FieldApi {
        val snapshot = Changes(Seed.assets, Seed.workOrders.filter { it.technicianId == "tech-alex" }, Seed.templates, emptyList(), emptyList(), now())
        var failure: Exception? = null
        var reply: Response<SyncReply> = Response.success(SyncReply(SyncStatus.SYNCED, 1, "Saved"))
        var calls = 0; var lastId = ""; var lastToken = ""; var uploads = 0
        var beforeOperation: () -> Unit = {}
        override suspend fun login(request: LoginRequest): Session = error("Not used")
        override suspend fun logout(token: String) = Unit
        override suspend fun changes(token: String) = snapshot
        override suspend fun upload(token: String, upload: AttachmentUpload): AttachmentReceipt { uploads++; return AttachmentReceipt(upload.id) }
        override suspend fun operation(token: String, operation: Operation): Response<SyncReply> { calls++; lastId = operation.id; lastToken = token; beforeOperation(); failure?.let { throw it }; return reply }
    }
}
