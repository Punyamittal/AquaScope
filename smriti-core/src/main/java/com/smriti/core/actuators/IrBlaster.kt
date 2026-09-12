package com.smriti.core.actuators

import android.content.Context
import android.hardware.ConsumerIrManager

/**
 * IR blaster over [ConsumerIrManager].
 * Returns false when the device has no emitter or the TRANSMIT_IR permission
 * path throws — never crashes.
 */
class IrBlaster(context: Context) {

    private val irManager: ConsumerIrManager? =
        context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager

    /**
     * Transmits [pattern] (alternating on/off durations in microseconds) at
     * [frequencyHz] carrier. False if no emitter or transmit fails.
     */
    fun irBlast(frequencyHz: Int, pattern: IntArray): Boolean {
        val mgr = irManager ?: return false
        if (!mgr.hasIrEmitter()) return false
        if (pattern.isEmpty()) return false
        return try {
            mgr.transmit(frequencyHz, pattern)
            true
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * NEC-protocol IR frame builder (38 kHz carrier).
 *
 * Frame layout: 9000us on / 4500us off lead, then 32 bits LSB-first —
 * 8-bit address, bitwise complement of address, 8-bit command, complement of
 * command. Each bit is a 562.5us burst followed by a 562.5us (logic 0) or
 * 1687.5us (logic 1) space. A final 562.5us burst terminates the frame.
 * Durations are integers in microseconds (562.5us unit rounded to 563us,
 * 1687.5us to 1688us), alternating on/off starting with on.
 */
object IrPatterns {

    const val NEC_FREQUENCY_HZ = 38_000

    private const val LEAD_ON_US = 9000
    private const val LEAD_OFF_US = 4500
    private const val UNIT_US = 563        // 562.5us rounded
    private const val ONE_SPACE_US = 1688  // 1687.5us rounded

    /** Builds one full 38 kHz NEC frame as alternating on/off microsecond ints. */
    fun nec(address: Int, command: Int): IntArray {
        val frame = IntArray(2 + 32 * 2 + 1)
        var i = 0
        frame[i++] = LEAD_ON_US
        frame[i++] = LEAD_OFF_US

        fun putByte(value: Int) {
            for (bit in 0 until 8) { // LSB-first
                frame[i++] = UNIT_US
                frame[i++] = if ((value ushr bit) and 1 == 1) ONE_SPACE_US else UNIT_US
            }
        }

        putByte(address and 0xFF)
        putByte(address.inv() and 0xFF)
        putByte(command and 0xFF)
        putByte(command.inv() and 0xFF)
        frame[i] = UNIT_US // final burst
        return frame
    }

    // DEMO CODES — remap address/command to the user's actual remote before real use.
    val AC_POWER_TOGGLE: IntArray = nec(0x04, 0x08)
    val FAN_SPEED_UP: IntArray = nec(0x04, 0x10)
}
