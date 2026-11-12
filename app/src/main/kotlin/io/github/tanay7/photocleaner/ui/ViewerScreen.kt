package io.github.tanay7.photocleaner.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import io.github.tanay7.photocleaner.MainActivity
import io.github.tanay7.photocleaner.Prefs
import io.github.tanay7.photocleaner.R
import io.github.tanay7.photocleaner.core.Fmt
import io.github.tanay7.photocleaner.core.Group
import io.github.tanay7.photocleaner.core.MediaKind
import io.github.tanay7.photocleaner.core.Member
import io.github.tanay7.photocleaner.media.Thumbs
import io.github.tanay7.photocleaner.scan.ScanResult
import java.text.DateFormat
import java.util.Date

/**
 * One member of a group at full size, to compare it with the others: swipe between them, pinch to
 * zoom, and tick it for the trash or keep it.
 */
class ViewerScreen(
    act: MainActivity,
    private val r: ScanResult,
    private val g: Group,
    private val members: List<Member>,
    start: Int,
) : Screen(act) {
    private val main = Handler(Looper.getMainLooper())
    private var index = start
    private var overlays = true
    private var loading: Int = -1
    private var full: Bitmap? = null

    private val image = ZoomImageView(ctx)
    private val title = ui.text("", 18f, Color.WHITE, 500, lines = 1)
    private val name = ui.text("", 16f, Color.WHITE, 500, lines = 2)
    private val where = ui.text("", 13f, 0xFFDDDDDD.toInt(), lines = 2)
    private val details = ui.text("", 13f, 0xFFDDDDDD.toInt(), lines = 2)
    private val reason = ui.text("", 13f, 0xFFDDDDDD.toInt(), lines = 3)
    private val toggle = ui.button("", Ui.Style.FILLED) { toggleCurrent() }
    private val play = ui.icon(R.drawable.ic_play, Color.WHITE, "Play the video", 88, 20) { openCurrent() }.apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x99000000.toInt()) }
    }
    private val header = ui.hbox(
        ui.icon(R.drawable.ic_arrow_back, Color.WHITE, "Back") { act.pop() },
        title.lp(0, WRAP, 1f, l = ui.dp(4)),
        ui.icon(R.drawable.ic_open, Color.WHITE, "Open in another app") { openCurrent() },
    ).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xAA000000.toInt(), 0x00000000))
        setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(16))
    }
    private val panel = ui.vbox(name, where.lp(t = ui.dp(2)), details.lp(t = ui.dp(2)), reason.lp(t = ui.dp(8)),
        toggle.lp(MATCH, ui.dp(48), t = ui.dp(12))).apply {
        background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xDD000000.toInt(), 0x88000000.toInt()))
        setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(16))
    }

    override val view: View = FrameLayout(ctx).apply {
        setBackgroundColor(Color.BLACK)
        addView(image.flp())
        addView(play.flp(ui.dp(88), ui.dp(88), Gravity.CENTER))
        addView(header.flp(MATCH, WRAP, Gravity.TOP))
        addView(panel.flp(MATCH, WRAP, Gravity.BOTTOM))
    }

    override val background: Int get() = Color.BLACK
    override val lightBars: Boolean get() = false

    init {
        image.onSwipe = { dir -> show(index + dir) }
        image.onTap = {
            overlays = !overlays
            val v = if (overlays) View.VISIBLE else View.GONE
            header.visibility = v
            panel.visibility = v
        }
        show(index)
    }

    private fun show(i: Int) {
        if (i !in members.indices) return
        index = i
        val m = members[i]
        val item = r.items[m.item]
        val row = r.rows[m.item]
        title.text = "${i + 1} of ${members.size} · ${Words.groupTitle(g, members)}"
        name.text = item.name
        where.text = (item.folder.ifEmpty { "Top folder" }) + "  ·  " + Words.label(m, g)
        val w = row?.width?.takeIf { it > 0 } ?: item.shownWidth
        val h = row?.height?.takeIf { it > 0 } ?: item.shownHeight
        val parts = ArrayList<String>()
        parts.add(Fmt.bytes(item.size))
        if (w > 0 && h > 0) parts.add("$w × $h")
        val taken = row?.takenAt?.let { (it * 1000).toLong() } ?: item.takenMs.takeIf { it > 0 }
        if (taken != null) parts.add(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(taken)))
        row?.features?.sharpness?.takeIf { !it.isNaN() }?.let { parts.add("sharpness ${it.toInt()}") }
        if (item.kind == MediaKind.VIDEO && item.durationMs > 0) parts.add(duration(item.durationMs))
        details.text = parts.joinToString(" · ")
        reason.text = Words.reason(r, m, g)
        play.visibility = if (item.kind == MediaKind.VIDEO) View.VISIBLE else View.GONE
        updateToggle()

        // The thumbnail at once, then the picture at screen size.
        full?.recycle()
        full = null
        image.setBitmap(Thumbs.cached(item.uri, ui.dp(112)) ?: Thumbs.cached(item.uri, ui.dp(104)))
        if (item.kind == MediaKind.VIDEO) {
            loading = i
            Thumbs.run {
                val bmp = try {
                    act.contentResolver.loadThumbnail(item.uri, android.util.Size(1024, 1024), null)
                } catch (_: Exception) {
                    null
                }
                main.post { if (loading == i && bmp != null) image.setBitmap(bmp) }
            }
            return
        }
        loading = i
        val dm = act.resources.displayMetrics
        val side = maxOf(dm.widthPixels, dm.heightPixels).coerceAtMost(2560)
        Thumbs.run {
            val bmp = Thumbs.full(act.contentResolver, item.uri, side)
            main.post {
                if (loading != i || bmp == null) {
                    bmp?.recycle()
                    return@post
                }
                // (Coming back to a picture can start its loading twice: the earlier copy goes.)
                val before = full
                full = bmp
                image.setBitmap(bmp, keepZoom = true)
                before?.recycle()
            }
        }
    }

    private fun updateToggle() {
        val m = members[index]
        val selected = m.item in r.selected
        toggle.text = if (selected) "Ticked for the trash · tap to keep" else "Kept · tap to tick for the trash"
        toggle.background = ui.pressable(ui.rounded(if (selected) c.danger else c.keep, 20f), 20f)
        toggle.setTextColor(if (selected) c.onDanger else c.onKeep)
    }

    private fun toggleCurrent() {
        val item = members[index].item
        r.tick(mapOf(item to (item !in r.selected))).let { Prefs.choose(ctx, it.keep, it.tick) }
        updateToggle()
    }

    private fun openCurrent() {
        val item = r.items[members[index].item]
        act.openExternally(item.uri, item.mime.ifEmpty { if (item.kind == MediaKind.VIDEO) "video/*" else "image/*" })
    }

    private fun duration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    override fun onDestroy() {
        loading = -1
        image.setBitmap(null)
        full?.recycle()
        full = null
    }
}
