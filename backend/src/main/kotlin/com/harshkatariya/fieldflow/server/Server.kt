package com.harshkatariya.fieldflow.server

import com.harshkatariya.fieldflow.core.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import io.ktor.utils.io.core.readBytes
import kotlinx.serialization.SerializationException

data class TechnicianPrincipal(val technician: Technician) : Principal
fun main() {
    val store = Store(System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/fieldflow", System.getenv("DATABASE_USER") ?: "fieldflow",
        requireNotNull(System.getenv("DATABASE_PASSWORD")) { "Set DATABASE_PASSWORD" })
    if (System.getenv("SEED_DEMO") == "true") store.seed(requireNotNull(System.getenv("DEMO_PASSWORD")) { "Set DEMO_PASSWORD for fictional development accounts" })
    Runtime.getRuntime().addShutdownHook(Thread { store.close() })
    embeddedServer(Netty, host = "0.0.0.0", port = System.getenv("PORT")?.toInt() ?: 8080) { fieldFlow(store) }.start(wait = true)
}
fun Application.fieldFlow(store: Store) {
    install(ContentNegotiation) { json(Wire) }
    install(StatusPages) {
        exception<Denied> { call, _ -> call.respond(HttpStatusCode.NotFound, ApiError("Not found or not assigned")) }
        exception<Invalid> { call, e -> call.respond(HttpStatusCode.BadRequest, ApiError(e.message ?: "Invalid request")) }
        exception<BadRequestException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ApiError("Malformed JSON request")) }
        exception<SerializationException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ApiError("Malformed JSON request")) }
        exception<Throwable> { call, e -> call.application.environment.log.error("Request failed", e); call.respond(HttpStatusCode.InternalServerError, ApiError("Server could not complete the request")) }
    }
    install(CORS) {
        System.getenv("CORS_HOST")?.takeIf { it.isNotBlank() }?.let { allowHost(it, schemes = listOf("https", "http")) }
        allowHeader(HttpHeaders.Authorization); allowHeader(HttpHeaders.ContentType); allowMethod(HttpMethod.Post)
    }
    install(Authentication) {
        bearer("session") { authenticate { credential -> withContext(Dispatchers.IO) { store.authenticate(credential.token)?.let(::TechnicianPrincipal) } } }
    }
    val attempts = ConcurrentHashMap<String, Pair<Long, Int>>()
    routing {
        get("/health") { withContext(Dispatchers.IO) { store.healthy() }; call.respond(Health("ok")) }
        route("/api/v1") {
            post("/auth/login") {
                val key = call.request.local.remoteHost
                val time = now()
                if (attempts.size > 1000) attempts.entries.removeIf { time - it.value.first > 60_000 }
                val count = attempts.compute(key) { _, prior -> if (prior == null || time - prior.first > 60_000) time to 1 else prior.first to prior.second + 1 }!!
                if (count.second > 20) { call.respond(HttpStatusCode.TooManyRequests, ApiError("Try again in a minute")); return@post }
                val request = call.receiveLimited<LoginRequest>(4096)
                val session = withContext(Dispatchers.IO) { store.login(request) }
                if (session == null) call.respond(HttpStatusCode.Unauthorized, ApiError("Invalid email or password")) else call.respond(session)
            }
            authenticate("session") {
                get("/auth/me") { call.respond(call.principal<TechnicianPrincipal>()!!.technician) }
                post("/auth/logout") { withContext(Dispatchers.IO) { store.logout(call.request.headers[HttpHeaders.Authorization]!!.removePrefix("Bearer ")) }; call.respond(HttpStatusCode.NoContent) }
                get("/sync/changes") { call.respond(withContext(Dispatchers.IO) { store.changes(call.owner()) }) }
                get("/work-orders") { call.respond(withContext(Dispatchers.IO) { store.changes(call.owner()).workOrders }) }
                get("/work-orders/{id}") { call.respond(withContext(Dispatchers.IO) { store.changes(call.owner()).workOrders.find { it.id == call.parameters["id"] } ?: throw Denied() }) }
                get("/assets") { call.respond(withContext(Dispatchers.IO) { store.changes(call.owner()).assets }) }
                get("/assets/{id}") { call.respond(withContext(Dispatchers.IO) { store.changes(call.owner()).assets.find { it.id == call.parameters["id"] } ?: throw Denied() }) }
                get("/inspection-templates") { call.respond(withContext(Dispatchers.IO) { store.changes(call.owner()).templates }) }
                get("/inspections") { call.respond(withContext(Dispatchers.IO) { store.changes(call.owner()).inspections }) }
                get("/inspections/{id}") { call.respond(withContext(Dispatchers.IO) { store.changes(call.owner()).inspections.find { it.id == call.parameters["id"] } ?: throw Denied() }) }
                get("/audit-events") { call.respond(withContext(Dispatchers.IO) { store.changes(call.owner()).audits }) }
                post("/sync/operations") {
                    val op = call.receiveLimited<Operation>(1_000_000)
                    val reply = withContext(Dispatchers.IO) { store.process(call.owner(), op) }
                    call.respond(if (reply.status == SyncStatus.CONFLICT) HttpStatusCode.Conflict else HttpStatusCode.OK, reply)
                }
                post("/attachments") {
                    if ((call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0) > 7_100_000) throw Invalid("Photo too large")
                    val upload = call.receiveLimited<AttachmentUpload>(7_100_000)
                    call.respond(withContext(Dispatchers.IO) { store.upload(call.owner(), upload) })
                }
                get("/attachments/{id}") { call.respondBytes(withContext(Dispatchers.IO) { store.photo(call.owner(), call.parameters["id"]!!) }, ContentType.Image.JPEG) }
            }
        }
    }
}
private fun ApplicationCall.owner() = principal<TechnicianPrincipal>()!!.technician.id
private suspend inline fun <reified T> ApplicationCall.receiveLimited(limit: Int): T {
    val bytes = receiveChannel().readRemaining(limit.toLong() + 1).readBytes()
    if (bytes.size > limit) throw Invalid("Request body exceeds size limit")
    return Wire.decodeFromString(bytes.decodeToString())
}
