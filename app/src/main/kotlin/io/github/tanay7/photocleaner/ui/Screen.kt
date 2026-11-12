package io.github.tanay7.photocleaner.ui

import android.content.Context
import android.view.View
import io.github.tanay7.photocleaner.MainActivity

/** One screen of the app: a view, and what to do when it is shown, hidden or asked to go back. */
abstract class Screen(val act: MainActivity) {
    val ui: Ui get() = act.ui
    val c: Palette get() = act.ui.c
    val ctx: Context get() = act

    abstract val view: View

    open val background: Int get() = c.background
    /** Dark status-bar icons, for a light screen. */
    open val lightBars: Boolean get() = !c.dark
    /** Handles Back even as the only screen (such as a running scan asking before it stops). */
    open val wantsBack: Boolean get() = false

    open fun onShown() {}
    open fun onHidden() {}
    open fun onResumed() {}
    open fun onDestroy() {}

    /** True when the screen handled Back itself. */
    open fun onBack(): Boolean = false
}
