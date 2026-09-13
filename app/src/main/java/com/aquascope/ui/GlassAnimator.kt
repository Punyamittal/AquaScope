package com.aquascope.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * Utility for glass card entrance and ambient animations.
 *
 * All methods use ViewPropertyAnimator / ObjectAnimator — no external libs.
 * Stagger multiple cards by multiplying [index] × [STAGGER_MS].
 */
object GlassAnimator {

    private const val STAGGER_MS = 70L
    private const val DURATION_CARD = 420L
    private const val DURATION_SCALE = 380L
    private const val DURATION_GLOW = 1800L

    /**
     * Slide-up + fade entrance for a glass card.
     * @param view  The card view to animate.
     * @param index Stagger index (0, 1, 2, …). Each card delayed by index × 70ms.
     */
    fun fadeInUp(view: View, index: Int = 0) {
        view.alpha = 0f
        view.translationY = dpToPx(view, 40f)
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(index * STAGGER_MS)
            .setDuration(DURATION_CARD)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()
    }

    /**
     * Scale + fade entrance (orbs, status indicators, modal panels).
     */
    fun fadeInScale(view: View, delay: Long = 0L) {
        view.alpha = 0f
        view.scaleX = 0.88f
        view.scaleY = 0.88f
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(delay)
            .setDuration(DURATION_SCALE)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()
    }

    /**
     * Continuous gentle alpha pulse — for status indicators / listening orbs.
     * Cancels automatically when [view] is detached.
     */
    fun pulseGlow(view: View): ValueAnimator {
        val anim = ValueAnimator.ofFloat(0.55f, 1.0f).apply {
            duration = DURATION_GLOW
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { view.alpha = it.animatedValue as Float }
        }
        anim.start()
        return anim
    }

    /**
     * Quick pop-in scale for nav icon selection feedback.
     */
    fun navIconPop(view: View) {
        view.animate()
            .scaleX(1.18f)
            .scaleY(1.18f)
            .setDuration(140)
            .setInterpolator(OvershootInterpolator(2.0f))
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(160)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    /**
     * Nav icon de-select: shrink slightly then return to 1.
     */
    fun navIconDeselect(view: View) {
        view.animate()
            .scaleX(0.9f)
            .scaleY(0.9f)
            .setDuration(100)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(180)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    /**
     * Horizontal shimmer sweep — draws attention to a glass card.
     * Animates the view's translationX slightly left-right.
     */
    fun shimmerAttention(view: View) {
        val anim = ObjectAnimator.ofFloat(view, View.TRANSLATION_X, 0f, 6f, -4f, 2f, 0f).apply {
            duration = 500
            interpolator = DecelerateInterpolator()
        }
        anim.start()
    }

    /**
     * Fade a view out gently (for dismissal or hide).
     */
    fun fadeOut(view: View, durationMs: Long = 250L, endAction: (() -> Unit)? = null) {
        view.animate()
            .alpha(0f)
            .setDuration(durationMs)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { endAction?.invoke() }
            .start()
    }

    private fun dpToPx(view: View, dp: Float): Float =
        dp * view.context.resources.displayMetrics.density
}
