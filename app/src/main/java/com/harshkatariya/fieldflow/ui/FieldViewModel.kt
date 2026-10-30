package com.harshkatariya.fieldflow.ui

import android.content.Context
import android.net.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.harshkatariya.fieldflow.core.*
import com.harshkatariya.fieldflow.data.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class UiState(val ready: Boolean = false, val session: Session? = null, val local: LocalState = LocalState(), val online: Boolean = false)
fun connectivityMessage(demo: Boolean, networkAvailable: Boolean): String = when {
    demo -> "LOCAL DEMO  ·  Saved on this device"
    networkAvailable -> "NETWORK AVAILABLE  ·  Work saved locally"
    else -> "OFFLINE  ·  Your work is saved here"
}
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel class FieldViewModel @Inject constructor(
    val repository: FieldRepository, val sessions: SessionStore, private val api: FieldApi,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val busy = MutableStateFlow(false)

    private val network = callbackFlow {
        val manager = context.getSystemService(ConnectivityManager::class.java)

        fun currentNetworkValidated(): Boolean {
            val active = manager.activeNetwork ?: return false
            val capabilities = manager.getNetworkCapabilities(active)
                ?: return false

            return capabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_VALIDATED
            )
        }

        val callback = object : ConnectivityManager.NetworkCallback() {

            override fun onAvailable(network: Network) {
                // Wait for onCapabilitiesChanged to confirm validation.
            }

            override fun onLost(network: Network) {
                trySend(false)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                trySend(
                    capabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_VALIDATED
                    )
                )
            }
        }

        manager.registerDefaultNetworkCallback(callback)

        trySend(currentNetworkValidated())

        awaitClose {
            manager.unregisterNetworkCallback(callback)
        }
    }.distinctUntilChanged().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        false
    )

    private val local = sessions.session.flatMapLatest { session -> if (session == null) flowOf(UiState()) else repository.observe(session).map { UiState(session = session, local = it) }.onStart { emit(UiState(session = session)) } }
    val state = combine(sessions.ready, local, network) { ready, data, online -> data.copy(ready = ready, online = online) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState())
    val theme = sessions.theme.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "system")
    init {
        viewModelScope.launch { for (command in commands) {
            busy.value = true
            try { command() } catch (e: CancellationException) { throw e } catch (e: Exception) { messages.emit(e.message ?: "Unable to complete action") }
            finally { busy.value = false }
        } }
        action { sessions.restore(); if (sessions.session.value?.demo == false) { SyncSchedule.periodic(context); SyncSchedule.enqueue(context) } }
        viewModelScope.launch { while (isActive) { delay(30_000); sessions.session.value?.let { if (!Rules.validSession(it, now())) sessions.clear(expected = it) } } }
    }
    fun action(block: suspend () -> Unit) { commands.trySend(block) }
    fun demo(technician: Technician) = action { repository.seedDemo(); sessions.save(Session(technician, expiresAt = now() + 7 * 86_400_000L, demo = true)) }
    fun login(email: String, password: String) = action {
        val session = api.login(LoginRequest(email, password))
        sessions.save(session)
        repository.retryFailed(afterReauthentication = true)
        SyncSchedule.periodic(context); SyncSchedule.enqueue(context)
    }
    fun logout() = action {
        val session = sessions.session.value
        sessions.clear(); SyncSchedule.cancel(context)
        if (session != null && !session.demo) runCatching { api.logout("Bearer ${session.token}") }
    }
    fun start(work: String, navigate: (String) -> Unit) = action { navigate(repository.start(work)) }
    fun answer(id: String, answer: Answer) = action { repository.answer(id, answer) }
    fun submit(id: String, navigate: () -> Unit) = action {
        repository.submit(id)
        if (sessions.session.value?.demo == false) SyncSchedule.enqueue(context)
        messages.emit("Inspection saved to the persistent queue"); navigate()
    }
    fun sync() = action {
        if (sessions.session.value?.demo == true) messages.emit("Local demo keeps submissions on this device. Sign in to use backend sync.")
        else { repository.retryFailed(); SyncSchedule.enqueue(context); messages.emit("Sync scheduled when a network is available") }
    }
    fun report(message: String) { messages.tryEmit(message) }
}
