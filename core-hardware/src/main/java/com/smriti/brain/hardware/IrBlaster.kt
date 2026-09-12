package com.smriti.brain.hardware

import android.content.Context
import android.hardware.ConsumerIrManager

class IrBlaster(context: Context) {
    private val ir = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager

    val available: Boolean get() = ir?.hasIrEmitter() == true

    fun transmitNec(frequencyHz: Int = 38_000, pattern: IntArray): Boolean {
        val mgr = ir ?: return false
        if (!mgr.hasIrEmitter()) return false
        return try {
            mgr.transmit(frequencyHz, pattern)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Common 38 kHz AC power-toggle burst (NEC-style mark/space in µs). */
    fun acPowerToggle(): Boolean = transmitNec(
        pattern = intArrayOf(
            9000, 4500, 560, 560, 560, 1690, 560, 560, 560, 1690,
            560, 560, 560, 560, 560, 1690, 560, 560, 560, 20000
        )
    )

    fun fanPowerToggle(): Boolean = transmitNec(
        pattern = intArrayOf(
            9000, 4500, 560, 1690, 560, 560, 560, 1690, 560, 560,
            560, 560, 560, 560, 560, 1690, 560, 20000
        )
    )
}
