package ai.deepmost.corridyx.ui

import ai.deepmost.corridyx.AppGraph
import ai.deepmost.corridyx.config.SettingsRepository
import ai.deepmost.corridyx.packet.db.PacketEntity
import ai.deepmost.corridyx.packet.db.PacketStatus
import ai.deepmost.corridyx.packet.db.SessionEntity
import ai.deepmost.corridyx.registry.EngineStatus
import ai.deepmost.corridyx.registry.ModelRegistry
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CorridyxViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = AppGraph.get(app)
    val settingsRepo: SettingsRepository = graph.settingsRepository

    val settings = settingsRepo.flow.stateIn(viewModelScope, SharingStarted.Eagerly, ai.deepmost.corridyx.config.Settings.DEFAULT)

    val packets: StateFlow<List<PacketEntity>> =
        graph.db.packets().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val sessions: StateFlow<List<SessionEntity>> =
        graph.db.sessions().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val pendingCount: StateFlow<Int> =
        graph.db.packets().countByStatus(PacketStatus.PENDING).stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val doneCount: StateFlow<Int> =
        graph.db.packets().countByStatus(PacketStatus.DONE).stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val failedCount: StateFlow<Int> =
        graph.db.packets().countByStatus(PacketStatus.FAILED).stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    fun edit(block: SettingsRepository.SettingsEditor.() -> Unit) {
        viewModelScope.launch { settingsRepo.update(block) }
    }

    /** Engine status per head. Uses a transient registry probe when capture isn't running. */
    suspend fun registryStatuses(): List<EngineStatus> = withContext(Dispatchers.IO) {
        val reg = ModelRegistry(getApplication())
        reg.initialize()
        val s = reg.statuses()
        reg.close()
        s
    }

    suspend fun packetsForSession(sessionId: String): List<PacketEntity> = withContext(Dispatchers.IO) {
        graph.db.packets().forSession(sessionId)
    }
}
