package com.aquascope.ui

import android.app.Activity
import android.view.View
import com.aquascope.halo.HaloRender
import com.aquascope.halo.MonsterHaloController

/** Binds on-screen halo indicator to the controller for an activity lifetime. */
class HaloBinding(
    private val activity: Activity,
    private val indicator: HaloIndicatorView?
) {
    private val halo = MonsterHaloController.get(activity)
    private val listener: (HaloRender) -> Unit = { render ->
        activity.runOnUiThread {
            if (indicator == null) return@runOnUiThread
            indicator.visibility =
                if (halo.prefs.showSimulator) View.VISIBLE else View.GONE
            indicator.bind(render)
        }
    }

    fun start() {
        halo.addListener(listener)
    }

    fun stop() {
        halo.removeListener(listener)
    }

    fun controller(): MonsterHaloController = halo
}
