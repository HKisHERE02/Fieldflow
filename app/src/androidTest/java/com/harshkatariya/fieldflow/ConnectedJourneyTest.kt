package com.harshkatariya.fieldflow

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.harshkatariya.fieldflow.core.*
import com.harshkatariya.fieldflow.data.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

/** Opt-in, two invocations with a real process kill between them; requires the local server. */
@RunWith(AndroidJUnit4::class)
class ConnectedJourneyTest {
    private val args get() = InstrumentationRegistry.getArguments()
    private fun deps() = EntryPointAccessors.fromApplication(ApplicationProvider.getApplicationContext(), SyncDependencies::class.java)
    @Test fun manualRetryOfExhaustedOperation() = runBlocking {
        Assume.assumeTrue(args.getString("journeyStage") == "retry")
        val d = deps()
        val session = d.api().login(LoginRequest("alex@fieldflow.example", args.getString("demoPassword") ?: "FieldFlow-Demo-2026!"))
        d.sessions().save(session)
        d.repository().merge(session, d.api().changes("Bearer ${session.token}"))
        val id = d.repository().start("wo-1")
        Seed.history.first().answers.forEach { d.repository().answer(id, it) }
        d.repository().submit(id)
        val dao = d.repository().database(session).dao(); val original = dao.operationFor(id)!!
        // The eight-failure progression is covered by SyncEngineTest. Here verify the persisted exhausted state through WorkManager.
        dao.operation(original.copy(status = "FAILED", attempts = 8, failureKind = "TRANSIENT", error = "Connection unavailable"))
        dao.inspection(dao.inspection(id)!!.copy(syncStatus = "FAILED"))
        d.repository().retryFailed()
        assertEquals(0, dao.operationFor(id)!!.attempts)
        assertEquals(original.id, dao.operationFor(id)!!.id); assertEquals(original.payload, dao.operationFor(id)!!.payload)
        SyncSchedule.enqueue(ApplicationProvider.getApplicationContext())
        withTimeout(90_000) { while (dao.operationFor(id)!!.status != "SYNCED") delay(500) }
        assertEquals(1, dao.operationFor(id)!!.attempts)
        assertEquals(original.payload, dao.operationFor(id)!!.payload)
        assertEquals(1, d.api().changes("Bearer ${session.token}").inspections.count { it.id == id })
        assertEquals(SyncStatus.SYNCED, d.api().operation("Bearer ${session.token}", Wire.decodeFromString(original.payload)).body()!!.status)
    }
    @Test fun prepareOfflineSubmission() = runBlocking {
        Assume.assumeTrue(args.getString("journeyStage") == "prepare")
        val d = deps()
        val session = d.api().login(LoginRequest("alex@fieldflow.example", args.getString("demoPassword") ?: "FieldFlow-Demo-2026!"))
        d.sessions().save(session)
        d.repository().merge(session, d.api().changes("Bearer ${session.token}"))
        SyncSchedule.cancel(ApplicationProvider.getApplicationContext())
        val id = d.repository().start("wo-1")
        Seed.history.first().answers.forEach { d.repository().answer(id, it) }
        d.repository().defect(id, "Fictional inspection: worn protective cover", Severity.MEDIUM)
        val photoId = UUID.randomUUID().toString()
        val photo = java.io.File(ApplicationProvider.getApplicationContext<android.content.Context>().filesDir, "$photoId.jpg")
        val bitmap = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(0, 110, 100))
        photo.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
        bitmap.recycle()
        d.repository().photo(id, Photo(photoId), photo.absolutePath)
        d.repository().submit(id)
        val dao = d.repository().database(session).dao()
        dao.metadata(MetadataRow("journey-id", id))
        assertEquals("PENDING", dao.operationFor(id)!!.status)
        assertEquals(5, Wire.decodeFromString<Inspection>(dao.inspection(id)!!.payload).answers.size)
        assertFalse(d.api().changes("Bearer ${session.token}").inspections.any { it.id == id })
    }
    @Test fun restoreAndSynchronizeExactlyOnce() = runBlocking {
        Assume.assumeTrue(args.getString("journeyStage") == "restore")
        val d = deps(); d.sessions().restore()
        val session = d.sessions().session.value!!
        val dao = d.repository().database(session).dao()
        val id = dao.metadata("journey-id")!!
        assertEquals("PENDING", dao.operationFor(id)!!.status)
        assertEquals(5, Wire.decodeFromString<Inspection>(dao.inspection(id)!!.payload).answers.size)
        SyncSchedule.enqueue(ApplicationProvider.getApplicationContext())
        withTimeout(90_000) { while (dao.operationFor(id)!!.status != "SYNCED") delay(500) }
        val op = Wire.decodeFromString<Operation>(dao.operationFor(id)!!.payload)
        repeat(2) { assertEquals(SyncStatus.SYNCED, d.api().operation("Bearer ${session.token}", op).body()!!.status) }
        val changes = d.api().changes("Bearer ${session.token}")
        assertEquals(1, changes.inspections.count { it.id == id })
        assertEquals(1, changes.inspections.first { it.id == id }.version)
        assertTrue(dao.photos(id).all { it.uploaded && java.io.File(it.path).exists() })
        assertEquals(1, changes.inspections.first { it.id == id }.photos.size)
        val original = dao.operationFor(id)!!
        val conflictOp = op.copy(id = UUID.randomUUID().toString())
        dao.deleteOperation(original.id)
        dao.operation(original.copy(id = conflictOp.id, payload = com.harshkatariya.fieldflow.core.Wire.encodeToString(Operation.serializer(), conflictOp), status = "PENDING"))
        SyncSchedule.enqueue(ApplicationProvider.getApplicationContext())
        withTimeout(90_000) { while (dao.operationFor(id)!!.status != "CONFLICT") delay(500) }
        assertEquals(op.inspection.answers, Wire.decodeFromString<Inspection>(dao.inspection(id)!!.payload).answers)
        assertNotNull(dao.operationFor(id)!!.serverPayload)
    }
    @Test fun serverOutagePreservesSubmission() = runBlocking {
        Assume.assumeTrue(args.getString("journeyStage") == "outage")
        val d = deps(); d.sessions().restore()
        val session = d.sessions().session.value!!
        val dao = d.repository().database(session).dao()
        val id = dao.metadata("journey-id")!!
        val before = dao.operationFor(id)!!
        assertFalse(d.engine().run())
        val after = dao.operationFor(id)!!
        assertEquals(before.id, after.id); assertEquals(before.payload, after.payload)
        assertEquals("PENDING", after.status); assertTrue(after.attempts > before.attempts)
        assertEquals(5, Wire.decodeFromString<Inspection>(dao.inspection(id)!!.payload).answers.size)
        assertTrue(dao.photos(id).all { java.io.File(it.path).exists() })
    }
}
