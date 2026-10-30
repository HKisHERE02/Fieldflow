package com.harshkatariya.fieldflow

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.harshkatariya.fieldflow.core.*
import com.harshkatariya.fieldflow.data.*
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

internal fun writeJpeg(file: File) {
    val bitmap = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
    try { file.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it)) } }
    finally { bitmap.recycle() }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoEvidenceTest {
    private lateinit var context: Context
    private lateinit var provider: DatabaseProvider
    private lateinit var repository: FieldRepository
    private lateinit var id: String
    private lateinit var file: File
    private val session = Session(Seed.technicians.first(), expiresAt = Long.MAX_VALUE, demo = true)
    @Before fun setup() = runTest {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("fieldflow-demo.db")
        provider = DatabaseProvider(context)
        val sessions = SessionStore(context).also { it.session.value = session }
        repository = FieldRepository(provider, sessions); repository.seedDemo()
        id = repository.start("wo-5") // This template actually requires photo evidence.
        Seed.history.first().answers.forEach { repository.answer(id, it) }
        file = File(context.filesDir, "${java.util.UUID.randomUUID()}.jpg")
        repository.photo(id, Photo("required-photo", questionId = "evidence"), file.absolutePath)
    }
    @After fun close() { provider.get(true).close(); file.delete() }
    private suspend fun assertRejected(reason: String) {
        val dao = provider.get(true).dao(); val before = dao.inspection(id)!!
        val error = runCatching { repository.submit(id) }.exceptionOrNull()
        assertTrue("Expected an evidence error, got $error", error is PhotoEvidenceException)
        assertTrue(error!!.message!!.contains(reason)); assertTrue(error.message!!.contains("Restore"))
        assertEquals(before, dao.inspection(id)); assertNull(dao.operationFor(id))
        assertEquals(InspectionStatus.DRAFT, Wire.decodeFromString<Inspection>(dao.inspection(id)!!.payload).status)
        assertEquals(5, Wire.decodeFromString<Inspection>(dao.inspection(id)!!.payload).answers.size)
        assertEquals(file.absolutePath, dao.photos(id).single().path)
    }
    @Test fun missingRequiredFileKeepsDraftAndAnswers() = runTest { assertRejected("missing") }
    @Test fun unreadablePhotoPathKeepsDraftAndAnswers() = runTest { assertTrue(file.mkdir()); assertRejected("unreadable") }
    @Test fun invalidJpegKeepsDraftAndAnswers() = runTest { file.writeText("not an image"); assertRejected("JPEG") }
    @Test fun jpegMarkersAloneAreNotValidEvidence() = runTest { file.writeBytes(byteArrayOf(-1, -40, -1, 0, -1, -39)); assertRejected("JPEG") }
    @Test fun truncatedJpegIsRejected() = runTest { writeJpeg(file); file.writeBytes(file.readBytes().dropLast(20).toByteArray()); assertRejected("JPEG") }
    @Test fun oversizedPhotoKeepsDraftAndAnswers() = runTest {
        java.io.RandomAccessFile(file, "rw").use { it.setLength(PhotoEvidence.MAX_BYTES.toLong() + 1) }
        assertRejected("5 MB")
    }
    @Test fun validJpegSubmitsOnceAndPreservesEvidence() = runTest {
        writeJpeg(file); val original = file.readBytes()
        repository.submit(id); val dao = provider.get(true).dao(); val op = dao.operationFor(id)!!
        repository.submit(id)
        assertEquals(op, dao.operationFor(id)); assertEquals("PENDING", op.status)
        assertEquals(InspectionStatus.SUBMITTED, Wire.decodeFromString<Inspection>(dao.inspection(id)!!.payload).status)
        assertArrayEquals(original, file.readBytes()); assertFalse(dao.photos(id).single().uploaded)
    }
    @Test fun failedSubmissionDoesNotDeleteOtherPhotos() = runTest {
        val valid = File(context.filesDir, "${java.util.UUID.randomUUID()}.jpg"); writeJpeg(valid)
        try {
            repository.photo(id, Photo("other-photo"), valid.absolutePath)
            val before = provider.get(true).dao().inspection(id)
            assertTrue(runCatching { repository.submit(id) }.exceptionOrNull() is PhotoEvidenceException)
            assertTrue(valid.exists()); assertEquals(2, provider.get(true).dao().photos(id).size)
            assertEquals(before, provider.get(true).dao().inspection(id)); assertNull(provider.get(true).dao().operationFor(id))
        } finally { valid.delete() }
    }
}
