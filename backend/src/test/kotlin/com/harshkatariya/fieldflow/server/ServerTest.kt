package com.harshkatariya.fieldflow.server

import com.harshkatariya.fieldflow.core.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.encodeToString
import org.junit.*
import org.junit.Assert.*
import java.nio.file.Files
import java.util.UUID

class ServerTest {
    private lateinit var store: Store
    private val password = "Fictional-Test-2026!"
    private val alex = Seed.technicians.first().id
    @Before fun setup() { store = Store("jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", ""); store.seed(password, 4) }
    @After fun close() { store.close() }
    private fun operation(id: String = UUID.randomUUID().toString()) = Operation(UUID.randomUUID().toString(), Seed.history.first().copy(id = id, version = 0), 0, now())
    @Test fun loginAndTokenValidation() { val session = store.login(LoginRequest("alex@fieldflow.example", password))!!; assertEquals(alex, store.authenticate(session.token)!!.id); assertNull(store.authenticate("invalid")); assertNull(store.login(LoginRequest("alex@fieldflow.example", "wrong"))) }
    @Test fun logoutRevokesToken() { val session = store.login(LoginRequest("alex@fieldflow.example", password))!!; store.logout(session.token); assertNull(store.authenticate(session.token)) }
    @Test fun technicianIsolation() { assertEquals(7, store.changes(alex).workOrders.size); assertEquals(3, store.changes("tech-riley").workOrders.size); assertFalse(store.changes(alex).assets.any { it.id == "asset-8" }) }
    @Test fun seedIdempotency() { store.seed(password, 4); assertEquals(7, store.changes(alex).workOrders.size); assertEquals(3, store.changes(alex).inspections.size) }
    @Test fun submissionAndReplay() { val op = operation(); assertEquals(SyncStatus.SYNCED, store.process(alex, op).status); assertEquals(store.process(alex, op), store.process(alex, op)); assertEquals(1, store.changes(alex).inspections.count { it.id == op.inspection.id }); assertEquals(1, store.changes(alex).audits.count { it.inspectionId == op.inspection.id }) }
    @Test(expected = Invalid::class) fun keyReuseWithDifferentPayloadRejected() { val op = operation(); store.process(alex, op); store.process(alex, op.copy(createdAt = op.createdAt + 1)) }
    @Test fun conflictDoesNotOverwriteServer() { val op = operation(); store.process(alex, op); val conflict = store.process(alex, op.copy(id = UUID.randomUUID().toString())); assertEquals(SyncStatus.CONFLICT, conflict.status); assertEquals(1, conflict.version); assertEquals(op.inspection.answers, conflict.server!!.answers); assertEquals(1, store.changes(alex).inspections.first { it.id == op.inspection.id }.version) }
    @Test fun conflictReplayStable() { val op = operation().copy(expectedVersion = 10); assertEquals(store.process(alex, op), store.process(alex, op)) }
    @Test(expected = Denied::class) fun otherTechnicianCannotSubmit() { store.process("tech-riley", operation()) }
    @Test(expected = Invalid::class) fun invalidChecklistRejected() { val op = operation(); store.process(alex, op.copy(inspection = op.inspection.copy(answers = emptyList()))) }
    @Test(expected = Invalid::class) fun draftRejected() { val op = operation(); store.process(alex, op.copy(inspection = op.inspection.copy(status = InspectionStatus.DRAFT))) }
    @Test(expected = Invalid::class) fun missingPhotoRejected() { val op = operation(); store.process(alex, op.copy(inspection = op.inspection.copy(photos = listOf(Photo("missing"))))) }
    @Test fun attachmentReplayAndIsolation() {
        val upload = AttachmentUpload(UUID.randomUUID().toString(), "inspection-photo", "wo-1", "/9j/AA==")
        assertEquals(store.upload(alex, upload), store.upload(alex, upload)); assertArrayEquals(byteArrayOf(-1, -40, -1, 0), store.photo(alex, upload.id))
        assertTrue(runCatching { store.photo("tech-riley", upload.id) }.exceptionOrNull() is Denied)
    }
    @Test fun apiAuthenticationAndRetrieval() = testApplication {
        application { fieldFlow(store) }
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/assets").status)
        val login = client.post("/api/v1/auth/login") { contentType(ContentType.Application.Json); setBody(Wire.encodeToString(LoginRequest("alex@fieldflow.example", password))) }
        assertEquals(HttpStatusCode.OK, login.status)
        val session = Wire.decodeFromString<Session>(login.bodyAsText())
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/assets") { bearerAuth(session.token) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/work-orders/wo-8") { bearerAuth(session.token) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/auth/login") { contentType(ContentType.Application.Json); setBody(Wire.encodeToString(LoginRequest("alex@fieldflow.example", "wrong"))) }.status)
    }
    @Test fun apiMutationStatusCodes() = testApplication {
        application { fieldFlow(store) }
        val session = store.login(LoginRequest("alex@fieldflow.example", password))!!
        val op = operation()
        suspend fun send(value: Operation) = client.post("/api/v1/sync/operations") { bearerAuth(session.token); contentType(ContentType.Application.Json); setBody(Wire.encodeToString(value)) }
        assertEquals(HttpStatusCode.OK, send(op).status)
        assertEquals(HttpStatusCode.OK, send(op).status)
        assertEquals(HttpStatusCode.Conflict, send(op.copy(id = UUID.randomUUID().toString())).status)
        assertEquals(HttpStatusCode.BadRequest, send(operation().let { it.copy(inspection = it.inspection.copy(answers = emptyList())) }).status)
    }
    @Test fun relationalDatabaseSurvivesRestart() {
        val directory = Files.createTempDirectory("fieldflow-persistence").toFile()
        val url = "jdbc:h2:file:${directory.resolve("db").absolutePath.replace('\\', '/')};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
        val op = operation()
        Store(url, "sa", "").use { it.seed(password, 4); it.process(alex, op) }
        Store(url, "sa", "").use { reopened -> assertEquals(1, reopened.changes(alex).inspections.count { it.id == op.inspection.id }); assertEquals(SyncStatus.SYNCED, reopened.process(alex, op).status) }
        directory.deleteRecursively()
    }
    @Test fun postgresIntegrationWhenConfigured() {
        val url = System.getenv("TEST_DATABASE_URL")
        Assume.assumeTrue("Set TEST_DATABASE_URL for PostgreSQL integration", url != null)
        val op = operation()
        Store(url!!, System.getenv("TEST_DATABASE_USER") ?: "fieldflow", System.getenv("TEST_DATABASE_PASSWORD") ?: "").use { pg -> pg.seed(password, 4); assertEquals(SyncStatus.SYNCED, pg.process(alex, op).status) }
        Store(url, System.getenv("TEST_DATABASE_USER") ?: "fieldflow", System.getenv("TEST_DATABASE_PASSWORD") ?: "").use { pg -> assertEquals(SyncStatus.SYNCED, pg.process(alex, op).status); assertEquals(1, pg.changes(alex).inspections.count { it.id == op.inspection.id }); assertEquals(SyncStatus.CONFLICT, pg.process(alex, op.copy(id = UUID.randomUUID().toString())).status) }
    }
    @Test fun concurrentReplayCreatesOneMutation() {
        val op = operation()
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val results = executor.invokeAll(List(2) { java.util.concurrent.Callable { store.process(alex, op) } }).map { it.get() }
            assertEquals(results[0], results[1]); assertEquals(1, store.changes(alex).inspections.count { it.id == op.inspection.id })
        } finally { executor.shutdownNow() }
    }
}
