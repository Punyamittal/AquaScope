package com.aquascope.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.aquascope.R

/**
 * Tab screens animate their own content. OriginOS / Material window
 * transitions always slide in from the right, so those are disabled.
 */
open class SmritiScreenActivity : AppCompatActivity() {

    private var restoreWithoutSlide = false

    override fun onCreate(savedInstanceState: Bundle?) {
        restoreWithoutSlide = savedInstanceState != null
        super.onCreate(savedInstanceState)
        suppressSystemTransition()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        suppressSystemTransition()
        TabSlide.playEnter(this)
    }

    override fun setContentView(view: View?) {
        super.setContentView(view)
        if (!restoreWithoutSlide) TabSlide.playEnter(this)
    }

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        if (!restoreWithoutSlide) TabSlide.playEnter(this)
    }

    override fun setContentView(view: View?, params: ViewGroup.LayoutParams?) {
        super.setContentView(view, params)
        if (!restoreWithoutSlide) TabSlide.playEnter(this)
    }

    private fun suppressSystemTransition() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.no_anim, R.anim.no_anim)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.no_anim, R.anim.no_anim)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.no_anim, R.anim.no_anim)
        }
    }
}

object TabSlide {
    const val EXTRA_FROM = "smriti_tab_from"
    const val EXTRA_TO = "smriti_tab_to"
    const val EXTRA_ANIMATE = "smriti_tab_animate"

    fun playEnter(activity: SmritiScreenActivity) {
        val intent = activity.intent ?: return
        if (!intent.getBooleanExtra(EXTRA_ANIMATE, false)) return
        intent.putExtra(EXTRA_ANIMATE, false)
        val from = intent.getIntExtra(EXTRA_FROM, Int.MIN_VALUE)
        val to = intent.getIntExtra(EXTRA_TO, Int.MIN_VALUE)
        if (from == Int.MIN_VALUE || to == Int.MIN_VALUE || from == to) return
        val content = activity.findViewById<View>(android.R.id.content) as? ViewGroup ?: return
        val root = content.getChildAt(0) ?: content
        val width = activity.resources.displayMetrics.widthPixels.toFloat()
        if (width <= 0f) return
        val fromRight = to > from
        root.animate().cancel()
        root.translationX = if (fromRight) width else -width
        root.animate()
            .translationX(0f)
            .setDuration(280L)
            .setInterpolator(FastOutSlowInInterpolator())
            .start()
    }
}
