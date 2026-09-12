package com.smriti.brain.gaming

object KillFeedJni {
    @Volatile var loaded: Boolean = false
        private set

    init {
        loaded = try {
            System.loadLibrary("smriti_killfeed")
            true
        } catch (_: Throwable) {
            false
        }
    }

    @JvmStatic
    external fun matchSad(
        frameGray: ByteArray, fw: Int, fh: Int,
        templGray: ByteArray, tw: Int, th: Int,
        roiX: Int, roiY: Int, roiW: Int, roiH: Int
    ): Float
}
