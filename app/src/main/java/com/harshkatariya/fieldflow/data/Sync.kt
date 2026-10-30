package com.harshkatariya.fieldflow.data

import android.content.Context
import android.util.Base64
import androidx.room.withTransaction
import androidx.work.*
import com.harshkatariya.fieldflow.core.*
import com.harshkatariya.fieldflow.core.Operation
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import retrofit2.HttpException
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

object SyncSchedule {
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    fun enqueue(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("fieldflow-sync", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(network).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    fun periodic(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("fieldflow-periodic", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(network).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork("fieldflow-sync"); WorkManager.getInstance(context).cancelUniqueWork("fieldflow-periodic") }
}
@EntryPoint @InstallIn(SingletonComponent::class) interface SyncDependencies {
    fun engine(): SyncEngine
    fun sessions(): SessionStore
    fun repository(): FieldRepository
    fun api(): FieldApi
}
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, SyncDependencies::class.java)
        if (!deps.sessions().ready.value) deps.sessions().restore()
        return try { if (deps.engine().run()) Result.success() else Result.retry() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
    }
}
@Singleton class SyncEngine @Inject constructor(private val repository: FieldRepository, private val api: FieldApi, private val sessions: SessionStore) {
    private val mutex = Mutex()
    suspend fun run(): Boolean = mutex.withLock {
        val session = sessions.session.value ?: return@withLock true
        if (session.demo) return@withLock true
        if (!Rules.validSession(session, now())) { sessions.clear(expected = session); return@withLock true }
        val db = repository.database(session); val dao = db.dao(); val token = "Bearer ${session.token}"
        var retry = false
        for (row in dao.pending(session.technician.id)) {
            if (sessions.session.value != session) break
            val operation = Wire.decodeFromString<Operation>(row.payload)
            val attempting = row.copy(status = "SYNCING", attempts = row.attempts + 1)
            db.withTransaction {
                dao.operation(attempting)
                dao.inspection(operation.inspection.row("SYNCING"))
            }
            repository.audit(dao, operation.inspection, "Sync attempted · attempt ${attempting.attempts}")
            try {
                val photos = dao.photos(row.inspectionId).associateBy { it.id }
                for (reference in operation.inspection.photos) {
                    val photo = photos[reference.id] ?: throw PhotoEvidenceException("Photo ${reference.id}: local attachment record is missing. Restore the original evidence before retrying.")
                    if (photo.uploaded) continue
                    val bytes = PhotoEvidence.read(photo)
                    api.upload(token, AttachmentUpload(photo.id, row.inspectionId, operation.inspection.workOrderId, Base64.encodeToString(bytes, Base64.NO_WRAP)))
                    dao.attachment(photo.copy(uploaded = true))
                }
                val response = api.operation(token, operation)
                val reply = if (response.isSuccessful) response.body() else if (response.code() == 409)
                    Wire.decodeFromString<SyncReply>(requireNotNull(response.errorBody()).string()) else throw HttpException(response)
                requireNotNull(reply)
                db.withTransaction {
                    if (reply.status == SyncStatus.SYNCED) {
                        dao.inspection(operation.inspection.copy(version = reply.version).row("SYNCED"))
                        dao.operation(attempting.copy(status = "SYNCED", error = ""))
                        repository.audit(dao, operation.inspection, "Sync succeeded · server version ${reply.version}")
                    } else {
                        dao.inspection(operation.inspection.row("CONFLICT"))
                        dao.operation(attempting.copy(status = "CONFLICT", error = reply.message, serverPayload = reply.server?.let { Wire.encodeToString(it) }))
                        repository.audit(dao, operation.inspection, "Conflict detected · local work preserved")
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val code = (e as? HttpException)?.code()
                val kind = when {
                    code == 401 -> "AUTH"
                    e is PhotoEvidenceException -> "EVIDENCE"
                    code != null && Rules.retryable(code) -> "TRANSIENT"
                    code != null || e is IllegalArgumentException -> "PERMANENT"
                    e is IOException -> "TRANSIENT"
                    else -> "UNKNOWN"
                }
                val transient = kind == "TRANSIENT"
                val state = if (transient && attempting.attempts < 8) "PENDING" else "FAILED"
                val message = when (kind) {
                    "AUTH" -> "Session expired. Sign in again to retry."
                    "PERMANENT" -> "Request rejected (${code ?: "invalid data"}). Automatic and manual batch retries are paused; resolve the validation problem."
                    else -> (e.message ?: "Sync failed. Review the operation before retrying.").take(400)
                }
                db.withTransaction {
                    dao.operation(attempting.copy(status = state, error = message, failureKind = kind))
                    dao.inspection(operation.inspection.row(state))
                    repository.audit(dao, operation.inspection, "Sync failed · $message")
                }
                if (code == 401) { sessions.clear(expected = session); return@withLock true }
                retry = retry || state == "PENDING"
            }
        }
        if (sessions.session.value == session) try { repository.merge(session, api.changes(token)) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            dao.audit(AuditRow(java.util.UUID.randomUUID().toString(), session.technician.id, null, "Refresh failed · ${e.message?.take(180)}", now()))
            val code = (e as? HttpException)?.code()
            if (code == 401) sessions.clear(expected = session)
            else retry = retry || (if (code != null) Rules.retryable(code) else e is IOException)
        }
        !retry
    }
}
