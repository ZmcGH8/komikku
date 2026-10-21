package eu.kanade.tachiyomi.ui.reader.viewer

import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.sign

/**
 * Continuous vertical scrolling driven by a gamepad stick (or held D-pad).
 *
 * Stick deflection maps linearly to scroll speed after a deadzone; releasing the stick
 * (or returning inside the deadzone) stops immediately — no fling/coast.
 */
class JoystickScrollHelper(
    private val scrollByPx: (Float) -> Unit,
    private val maxSpeedPxPerSec: () -> Float,
    private val isEnabled: () -> Boolean = { true },
) {
    private val choreographer = Choreographer.getInstance()
    private var axis = 0f
    private var heldDirection = 0f
    private var lastFrameNs = 0L
    private var running = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNs: Long) {
            if (!running) return
            if (!isEnabled()) {
                lastFrameNs = frameTimeNs
                choreographer.postFrameCallback(this)
                return
            }
            val effective = effectiveAxis()
            if (abs(effective) < EPSILON) {
                stopLoop()
                return
            }
            val dtSec = if (lastFrameNs == 0L) {
                0f
            } else {
                ((frameTimeNs - lastFrameNs).coerceAtMost(MAX_DT_NS) / 1_000_000_000f)
            }
            lastFrameNs = frameTimeNs
            if (dtSec > 0f) {
                scrollByPx(effective * maxSpeedPxPerSec() * dtSec)
            }
            choreographer.postFrameCallback(this)
        }
    }

    /**
     * Consumes a joystick/gamepad [MotionEvent]. Returns true when the event was a stick move
     * we handle (so the activity can treat it as consumed).
     */
    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val sources = event.source
        val isJoystick =
            (sources and InputDevice.SOURCE_CLASS_JOYSTICK) == InputDevice.SOURCE_CLASS_JOYSTICK ||
                (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
                (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
        if (!isJoystick) return false
        if (event.actionMasked != MotionEvent.ACTION_MOVE) return false

        val y = event.getAxisValue(MotionEvent.AXIS_Y)
        val rz = event.getAxisValue(MotionEvent.AXIS_RZ)
        val raw = if (abs(y) >= abs(rz)) y else rz
        axis = applyDeadzone(raw)
        ensureLoop()
        return true
    }

    /**
     * Held vertical D-pad as full deflection (±1). Call from [handleKeyEvent] for
     * [KeyEvent.KEYCODE_DPAD_UP] / [KeyEvent.KEYCODE_DPAD_DOWN].
     * Returns true when the key was handled.
     */
    fun onDpadKeyEvent(event: KeyEvent): Boolean {
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> 1f
            KeyEvent.KEYCODE_DPAD_UP -> -1f
            else -> return false
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // Ignore auto-repeat; direction is already held.
                if (event.repeatCount > 0) return true
                heldDirection = direction
                ensureLoop()
                return true
            }
            KeyEvent.ACTION_UP -> {
                if (heldDirection == direction) {
                    heldDirection = 0f
                }
                if (abs(effectiveAxis()) < EPSILON) {
                    stopLoop()
                }
                return true
            }
        }
        return false
    }

    fun stop() {
        axis = 0f
        heldDirection = 0f
        stopLoop()
    }

    private fun effectiveAxis(): Float =
        // Prefer analog axis when present so stick+DPAD dual reports stay proportional.
        if (abs(axis) > EPSILON) axis else heldDirection

    private fun ensureLoop() {
        if (abs(effectiveAxis()) < EPSILON) {
            stopLoop()
            return
        }
        if (!running) {
            running = true
            lastFrameNs = 0L
            choreographer.postFrameCallback(frameCallback)
        }
    }

    private fun stopLoop() {
        if (!running) return
        running = false
        lastFrameNs = 0L
        choreographer.removeFrameCallback(frameCallback)
    }

    companion object {
        private const val DEADZONE = 0.2f
        private const val EPSILON = 0.001f
        private const val MAX_DT_NS = 50_000_000L // 50ms clamp after a hitch

        private fun applyDeadzone(value: Float): Float {
            val magnitude = abs(value)
            if (magnitude < DEADZONE) return 0f
            val remapped = ((magnitude - DEADZONE) / (1f - DEADZONE)).coerceIn(0f, 1f)
            return remapped * sign(value)
        }
    }
}
