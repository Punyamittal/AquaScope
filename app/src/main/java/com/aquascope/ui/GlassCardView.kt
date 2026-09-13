package com.aquascope.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import com.aquascope.R

/**
 * GlassCardView — a FrameLayout that renders a glassmorphism surface.
 *
 * Visual fidelity comes from the drawable XML (translucent fill + frosted stroke + shimmer).
 * This class is a drop-in for FrameLayout / CardView in layouts.
 *
 * Usage in XML:
 *   <com.aquascope.ui.GlassCardView
 *       android:layout_width="match_parent"
 *       android:layout_height="wrap_content"
 *       android:background="@drawable/bg_glass_card"
 *       android:padding="20dp" />
 */
class GlassCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    init {
        // Apply default glass background if none set in XML
        if (background == null) {
            setBackgroundResource(R.drawable.bg_glass_card)
        }
    }

    /**
     * Dynamically toggle between normal and elevated glass appearance.
     * @param elevated true → bg_glass_card_elevated (stronger glow + more opaque fill)
     */
    fun setElevated(elevated: Boolean) {
        setBackgroundResource(
            if (elevated) R.drawable.bg_glass_card_elevated
            else R.drawable.bg_glass_card
        )
    }
}
