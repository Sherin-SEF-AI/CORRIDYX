package ai.deepmost.corridyx.conditions_api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide live PerceptionConditions bus. The capture pipeline pushes updates here; both the
 * in-app DRIVE UI and the bound [PerceptionConditionsService] (for sibling ai.deepmost.* apps) read
 * from it. Single source of truth so the API and the UI never disagree.
 */
object PerceptionConditionsBus {
    private val _state = MutableStateFlow(PerceptionConditions.UNKNOWN)
    val state: StateFlow<PerceptionConditions> = _state.asStateFlow()

    /** True while a capture session is actively scoring (consumers can tell live from stale). */
    @Volatile var capturing: Boolean = false

    fun publish(c: PerceptionConditions) { _state.value = c }
    fun current(): PerceptionConditions = _state.value
}
