package eu.kanade.presentation.more.settings

import android.content.Context
import exh.yakuyomi.DeviceMemory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observable mirror of the RAM-gate bypass.
 *
 * The bypass itself lives in [DeviceMemory] as a plain `SharedPreferences` read, which is invisible
 * to Compose: a row gated on `ramGated` composes against whatever the flag was at that moment and
 * never recomposes when it changes. Flipping it from the About easter egg therefore left
 * already-composed AI settings rows - and the categories the gate is supposed to hide - stuck
 * until the process restarted.
 *
 * Seeded once during `App.onCreate` and updated by [setDisabled], which is the only writer.
 */
object RamGateState {

    private val _isDisabled = MutableStateFlow(false)

    val isDisabled: StateFlow<Boolean> = _isDisabled.asStateFlow()

    fun seed(context: Context) {
        _isDisabled.value = DeviceMemory.isRamGateDisabled(context)
    }

    fun setDisabled(context: Context, disabled: Boolean) {
        DeviceMemory.setRamGateDisabled(context, disabled)
        _isDisabled.value = disabled
    }
}
