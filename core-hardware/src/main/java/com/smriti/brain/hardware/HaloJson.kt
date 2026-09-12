package com.smriti.brain.hardware

object HaloJson {
    fun solid(hex: String): String =
        """{"sceneId":0,"keepGoingOnScreenOff":false,"repeat":1,"combineName":"smriti_brain","effects":[{"type":"strobe","lightName":"monster_halo","brightness":100,"periodTime":4000,"count":1,"totalTime":4000,"isCountPriority":true,"firstLightColor":"$hex","secondLightColor":"$hex","thirdLightColor":"$hex","fourthLightColor":"$hex"}]}"""

    fun strobe(hex: String, periodMs: Int): String =
        """{"sceneId":0,"keepGoingOnScreenOff":false,"repeat":-1,"combineName":"smriti_brain","effects":[{"type":"strobe","lightName":"monster_halo","brightness":100,"periodTime":$periodMs,"count":24,"totalTime":${periodMs * 24},"isCountPriority":true,"firstLightColor":"$hex","secondLightColor":"000000","thirdLightColor":"$hex","fourthLightColor":"000000"}]}"""
}
