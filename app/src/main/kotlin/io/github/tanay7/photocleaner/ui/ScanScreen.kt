package io.github.tanay7.photocleaner.ui

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import io.github.tanay7.photocleaner.MainActivity
import io.github.tanay7.photocleaner.scan.ScanService
import io.github.tanay7.photocleaner.scan.ScanState
import io.github.tanay7.photocleaner.scan.Stage

/** Shows a running scan; becomes the results when it's done. */
class ScanScreen(act: MainActivity) : Screen(act) {
    private var unlisten: (() -> Unit)? = null
    private var finished = false
    private var stopping = false

    private val title = ui.text("Looking for duplicates", 24f, c.onSurface, 500).apply { gravity = Gravity.CENTER }
    private val stage = ui.text("", 15f, c.onSurfaceVariant).apply { gravity = Gravity.CENTER }
    private val coresLine = ui.text("", 13f, c.onSurfaceVariant).apply { gravity = Gravity.CENTER }
    private val progress = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
        progressTintList = ColorStateList.valueOf(c.primary)
        progressBackgroundTintList = ColorStateList.valueOf(c.surfaceHigh)
        indeterminateTintList = ColorStateList.valueOf(c.primary)
        isIndeterminate = true
    }
    private val hint = ui.text(
        "You can leave the app: the scan carries on, and its notification shows how far it is. " +
            "The first scan reads every photo; later ones only read new photos.",
        13f, c.onSurfaceVariant,
    ).apply { gravity = Gravity.CENTER }
    private val button = ui.button("Cancel", Ui.Style.TONAL) { confirmCancel() }

    override val view: View = FrameLayout(ctx).apply {
        addView(
            ui.vbox(
                title,
                stage.lp(t = ui.dp(12)),
                progress.lp(MATCH, ui.dp(12), t = ui.dp(24)),
                coresLine.lp(t = ui.dp(10)),
                hint.lp(t = ui.dp(24)),
                button.lp(WRAP, WRAP, t = ui.dp(28), gravity = Gravity.CENTER_HORIZONTAL),
            ).apply { setPadding(ui.dp(32), 0, ui.dp(32), 0) }
                .flp(MATCH, WRAP, Gravity.CENTER),
        )
    }

    override val wantsBack: Boolean get() = true

    override fun onShown() {
        if (ScanState.request?.restoring == true) title.text = "Bringing back your results"
        unlisten = ScanState.listen { update(it) }
        update(ScanState.status)
    }

    override fun onHidden() {
        unlisten?.invoke()
        unlisten = null
    }

    override fun onDestroy() = onHidden()

    override fun onBack(): Boolean {
        if (ScanState.running) confirmCancel() else act.pop()
        return true
    }

    private fun update(s: ScanState.Status) {
        if (finished) return
        when (s) {
            is ScanState.Running -> {
                if (stopping) return
                stage.text = ScanService.stageText(s.stage, s.done, s.total)
                coresLine.text = if (s.stage == Stage.READING || s.stage == Stage.VIDEOS || s.stage == Stage.COMPARING) {
                    ScanService.coresText(if (s.stage == Stage.COMPARING) Runtime.getRuntime().availableProcessors() else s.threads)
                } else {
                    ""
                }
                progress.isIndeterminate = s.total == 0
                if (s.total > 0) {
                    progress.max = s.total
                    progress.progress = s.done
                }
            }
            is ScanState.Done -> {
                finished = true
                act.replace(ResultsScreen(act, s.result))
            }
            is ScanState.Failed -> {
                title.text = "The scan stopped"
                stage.text = s.message
                progress.visibility = View.INVISIBLE
                hint.visibility = View.GONE
                coresLine.text = ""
                button.text = "Back"
                ui.enable(button, true)
                button.setOnClickListener { act.pop() }
            }
            ScanState.Idle -> {
                finished = true
                act.pop()
            }
        }
    }

    private fun confirmCancel() {
        if (!ScanState.running) {
            act.pop()
            return
        }
        AlertDialog.Builder(ctx)
            .setTitle("Stop the scan?")
            .setMessage("What it has read so far is remembered, so the next scan picks up from there.")
            .setPositiveButton("Stop") { _, _ ->
                stopping = true
                stage.text = "Stopping…"
                ui.enable(button, false)
                ScanService.cancel()
            }
            .setNegativeButton("Keep scanning", null)
            .show()
    }
}
