package com.aquascope.ui

import android.app.Activity
import android.content.Intent
import com.aquascope.R
import com.google.android.material.bottomnavigation.BottomNavigationView

/** Shared bottom nav: Home · Scan · Memory · Ask */
object SmritiNav {

    const val TAB_HOME = 0
    const val TAB_SCAN = 1
    const val TAB_MEMORY = 2
    const val TAB_ASK = 3
    const val TAB_SYSTEM = 4

    fun bind(activity: Activity, nav: BottomNavigationView, selected: Int) {
        nav.selectedItemId = when (selected) {
            TAB_HOME -> R.id.nav_home
            TAB_SCAN -> R.id.nav_scan
            TAB_MEMORY -> R.id.nav_memory
            else -> R.id.nav_ask
        }
        nav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    if (selected != TAB_HOME) {
                        activity.startActivity(
                            Intent(activity, SmritiHomeActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        )
                        activity.finish()
                    }
                    true
                }
                R.id.nav_scan -> {
                    if (selected != TAB_SCAN) {
                        activity.startActivity(
                            Intent(activity, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        )
                        activity.finish()
                    }
                    true
                }
                R.id.nav_memory -> {
                    if (selected != TAB_MEMORY) {
                        activity.startActivity(
                            Intent(activity, MemoryTimelineActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        )
                        activity.finish()
                    }
                    true
                }
                R.id.nav_ask -> {
                    if (selected != TAB_ASK) {
                        activity.startActivity(
                            Intent(activity, AskSmritiActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        )
                        activity.finish()
                    }
                    true
                }
                else -> false
            }
        }
    }

    fun bind(activity: Activity, nav: SmritiNavBar, selected: Int) {
        nav.select(selected)
        nav.setOnTab { tab ->
            if (tab == selected) return@setOnTab
            val target = when (tab) {
                TAB_HOME -> SmritiHomeActivity::class.java
                TAB_SCAN -> MainActivity::class.java
                TAB_MEMORY -> MemoryTimelineActivity::class.java
                TAB_ASK -> AskSmritiActivity::class.java
                else -> LocalModelActivity::class.java
            }
            activity.startActivity(
                Intent(activity, target)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            val stay =
                (tab == TAB_HOME && activity is SmritiHomeActivity) ||
                    (tab == TAB_SCAN && activity is MainActivity) ||
                    (tab == TAB_MEMORY && activity is MemoryTimelineActivity) ||
                    (tab == TAB_ASK && activity is AskSmritiActivity) ||
                    (tab == TAB_SYSTEM && activity is LocalModelActivity)
            if (!stay) activity.finish()
        }
    }
}
