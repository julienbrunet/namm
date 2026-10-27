package fr.jbrunet.yama.data.update

import fr.jbrunet.yama.data.local.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface UpdateUiState {
    data object None : UpdateUiState
    data class Available(val update: AvailableUpdate) : UpdateUiState
    data class Downloading(val update: AvailableUpdate, val progress: Float) : UpdateUiState
    data class Ready(val update: AvailableUpdate, val apk: File) : UpdateUiState
}

/**
 * Single update state, shared by the home screen (banner) and the settings (“Check”):
 * a check made anywhere shows everywhere, even if the app was already open.
 */
class UpdateController(
    private val manager: UpdateManager,
    private val settings: SettingsStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.None)
    val state: StateFlow<UpdateUiState> = _state

    private var lastCheck = 0L
    private var downloadJob: Job? = null

    /** Checks GitHub; if a newer version exists and automatic updates are on, downloads it. */
    suspend fun check(): AvailableUpdate? {
        lastCheck = clock()
        val available = manager.check() ?: return null
        val current = _state.value
        val known = when (current) {
            is UpdateUiState.Available -> current.update
            is UpdateUiState.Downloading -> current.update
            is UpdateUiState.Ready -> current.update
            UpdateUiState.None -> null
        }
        // New version or empty state: (re)start from “available”.
        if (known?.version != available.version) {
            _state.value = UpdateUiState.Available(available)
            if (settings.current().autoUpdate) download()
        }
        return available
    }

    /** Routine check (launch, back to the foreground), at most every 30 min. */
    fun checkIfDue() {
        if (clock() - lastCheck < CHECK_INTERVAL_MS) return
        scope.launch { runCatching { check() } }
    }

    fun onAction() {
        when (val s = _state.value) {
            is UpdateUiState.Available -> download()
            is UpdateUiState.Ready -> manager.install(s.apk)
            else -> Unit
        }
    }

    private fun download() {
        val available = (_state.value as? UpdateUiState.Available)?.update ?: return
        if (downloadJob?.isActive == true) return
        _state.value = UpdateUiState.Downloading(available, 0f)
        downloadJob = scope.launch {
            val apk = manager.download(available) { _state.value = UpdateUiState.Downloading(available, it) }
            _state.value = if (apk != null) UpdateUiState.Ready(available, apk) else UpdateUiState.Available(available)
        }
    }

    companion object {
        const val CHECK_INTERVAL_MS = 30 * 60 * 1000L
    }
}
