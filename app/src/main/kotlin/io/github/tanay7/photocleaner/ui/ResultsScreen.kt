package io.github.tanay7.photocleaner.ui

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import io.github.tanay7.photocleaner.MainActivity
import io.github.tanay7.photocleaner.Prefs
import io.github.tanay7.photocleaner.R
import io.github.tanay7.photocleaner.core.Flag
import io.github.tanay7.photocleaner.core.Fmt
import io.github.tanay7.photocleaner.core.Group
import io.github.tanay7.photocleaner.core.GroupType
import io.github.tanay7.photocleaner.core.MediaKind
import io.github.tanay7.photocleaner.core.Member
import io.github.tanay7.photocleaner.core.Relation
import io.github.tanay7.photocleaner.media.MediaIndex
import io.github.tanay7.photocleaner.scan.Category
import io.github.tanay7.photocleaner.scan.ScanResult
import kotlin.math.roundToInt

/** Words for groups and their members, shared by the results, the grid and the viewer. */
object Words {
    fun groupTitle(g: Group, live: List<Member>): String = when {
        g.type == GroupType.POOR -> Fmt.plural(live.size, "blurry or dark photo", "blurry or dark photos")
        g.kind == MediaKind.VIDEO -> "${live.size} identical videos"
        g.type == GroupType.BURST -> {
            // The shots of the burst, and any copies of them that were found as well.
            val shots = live.count { it.frame }
            "Burst · ${Fmt.plural(shots, "shot", "shots")}" + if (live.size > shots) " and ${Fmt.plural(live.size - shots, "copy", "copies")}" else ""
        }
        g.type == GroupType.IDENTICAL -> "${live.size} identical copies"
        else -> "${live.size} look-alike photos"
    }

    fun label(m: Member, g: Group): String {
        val base = when (m.relation) {
            Relation.KEEP -> if (g.type == GroupType.BURST) "Sharpest · keep" else "Best copy · keep"
            Relation.IDENTICAL -> "Identical copy"
            Relation.LOOKALIKE -> {
                val pct = "${(m.score ?: 0f).toInt()}%"
                when {
                    m.how == "mirrored" -> "Mirrored · $pct"
                    m.how == "cropped" -> "Cropped · $pct"
                    m.edited -> "Edited · $pct"
                    m.how == "rotated" -> "Turned · $pct"
                    else -> "$pct alike"
                }
            }
            Relation.BURST -> m.relSharpness?.let { "${it.roundToInt()}% as sharp" } ?: "Burst shot"
            Relation.VIA -> "Linked through another"
            Relation.POOR -> ""
        }
        val flags = ArrayList<String>()
        if (Flag.BLURRY in m.flags) flags.add("blurry")
        if (Flag.DARK in m.flags) flags.add("dark")
        if (Flag.CLIPPED in m.flags) flags.add("over- or underexposed")
        if (m.relation == Relation.POOR) return flags.joinToString(", ").replaceFirstChar { it.uppercase() }
        return if (flags.isEmpty()) base else "$base · ${flags.first()}"
    }

    /** Why a member is (or isn't) suggested for the trash, for the viewer. */
    fun reason(r: ScanResult, m: Member, g: Group): String = when {
        m.keeper && g.type == GroupType.BURST -> "The sharpest shot of the burst: suggested to keep."
        m.keeper -> "The best copy (largest, sharpest, an original rather than a saved copy): suggested to keep."
        m.note != null -> m.note
        m.relation == Relation.VIA -> "Matched only through another file of the group, so it isn't ticked for you." +
            (m.via?.let { " It looks like ${r.items[it].name}." } ?: "")
        m.relation == Relation.POOR -> "Possibly a poor shot. It isn't ticked for you: decide for yourself."
        m.relation == Relation.IDENTICAL -> "Byte-for-byte the same file as the one kept."
        m.relation == Relation.BURST -> "Another shot of the same moment, no sharper than the one kept."
        m.relation == Relation.LOOKALIKE -> "The same picture as the one kept, at a smaller size or saved again."
        else -> ""
    }
}

/** Thumbnail tiles: a picture with a keep badge and a tick for the trash, and three lines below. */
class Tiles(private val act: MainActivity, private val r: ScanResult, val sizePx: Int,
            private val onOpen: (Group, Member) -> Unit, private val onToggle: (Int) -> Unit) {
    private val ui = act.ui
    private val c = ui.c

    inner class Tile {
        val img = ImageView(act).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ui.rounded(c.surfaceHigh, 16f)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, ui.dp(16f))
            }
            clipToOutline = true
        }
        val badge = ui.text("Keep", 11f, c.onKeep, 500).apply {
            background = ui.rounded(c.keep, 8f)
            setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(2))
        }
        val star = ImageView(act).apply {
            setImageResource(R.drawable.ic_star)
            imageTintList = ColorStateList.valueOf(0xFFFFC107.toInt())
            setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x99000000.toInt()) }
            contentDescription = "Favourite"
        }
        val play = ImageView(act).apply {
            setImageResource(R.drawable.ic_play)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x99000000.toInt()) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val check = ImageView(act).apply {
            setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
            background = ui.pressable(null, 24f)
        }
        // Made once for the tile, not on every scroll step.
        private val tickOn = tickDrawable(true)
        private val tickOff = tickDrawable(false)
        private val tickedFrame = ui.rounded(0x33000000, 16f, c.danger, 3f)
        val frame = FrameLayout(act).apply {
            addView(img.flp(MATCH, MATCH))
            addView(badge.flp(WRAP, WRAP, Gravity.TOP or Gravity.START, l = ui.dp(6), t = ui.dp(6)))
            addView(star.flp(ui.dp(24), ui.dp(24), Gravity.BOTTOM or Gravity.START, l = ui.dp(6), b = ui.dp(6)))
            addView(play.flp(ui.dp(40), ui.dp(40), Gravity.CENTER))
            addView(check.flp(ui.dp(48), ui.dp(48), Gravity.TOP or Gravity.END))
            background = ui.pressable(null, 16f)
            foregroundGravity = Gravity.FILL
        }
        val label = ui.text("", 12f, c.onSurface, 500, lines = 1)
        val info = ui.text("", 11f, c.onSurfaceVariant, lines = 1)
        val folder = ui.text("", 11f, c.onSurfaceVariant, lines = 1)
        val root: LinearLayout = ui.vbox(frame.lp(sizePx, sizePx), label.lp(t = ui.dp(6)), info, folder).apply { tag = this@Tile }

        fun bind(g: Group, m: Member) {
            val item = r.items[m.item]
            Thumbs.load(act.contentResolver, img, item.uri, sizePx)
            val selected = m.item in r.selected
            check.setImageDrawable(if (selected) tickOn else tickOff)
            check.contentDescription = if (selected) "Ticked for the trash: tap to keep" else "Tap to tick for the trash"
            frame.foreground = if (selected) tickedFrame else null
            badge.visibility = if (m.keeper) View.VISIBLE else View.GONE
            star.visibility = if (Flag.FAVORITE in m.flags) View.VISIBLE else View.GONE
            play.visibility = if (item.kind == MediaKind.VIDEO) View.VISIBLE else View.GONE
            label.text = Words.label(m, g)
            label.setTextColor(if (selected) c.danger else c.onSurface)
            val w = r.rows[m.item]?.width?.takeIf { it > 0 } ?: item.shownWidth
            val h = r.rows[m.item]?.height?.takeIf { it > 0 } ?: item.shownHeight
            info.text = if (w > 0 && h > 0) "${Fmt.bytes(item.size)} · $w×$h" else Fmt.bytes(item.size)
            folder.text = item.folder.substringAfterLast('/').ifEmpty { "Top folder" }
            frame.contentDescription = "${item.name}, ${label.text}, in ${item.folder.ifEmpty { "the top folder" }}. Tap to view."
            frame.setOnClickListener { onOpen(g, m) }
            check.setOnClickListener { onToggle(m.item) }
        }
    }

    private fun tickDrawable(selected: Boolean) = if (selected) {
        android.graphics.drawable.LayerDrawable(
            arrayOf(
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c.danger) },
                act.getDrawable(R.drawable.ic_check)!!.mutate().apply { setTint(c.onDanger) },
            ),
        ).apply { setLayerInset(1, ui.dp(5), ui.dp(5), ui.dp(5), ui.dp(5)) }
    } else {
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x55000000)
            setStroke(ui.dp(2), Color.WHITE)
        }
    }

    fun make() = Tile()
}

/**
 * The groups found: the copy to keep is marked, the extra copies are ticked for the trash. Ticked
 * files go to Android's trash with one confirmation, and Undo brings them back.
 */
class ResultsScreen(act: MainActivity, private val r: ScanResult) : Screen(act) {
    private var filter = Category.ALL
    private var shown: List<Group> = emptyList()
    private var lastTrashed: List<Int> = emptyList()
    private val thumbPx = ui.dp(112)
    private val tiles = Tiles(act, r, thumbPx, { g, m -> openViewer(g, m) }, { toggle(it) })

    private val bar = ui.topBar("Results", null, { act.pop() })
    private val chips = ui.hbox().apply { setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(8)) }
    private val chipScroll = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        addView(chips)
    }

    /** Each group keeps its number, so a card stays with its group when others leave the list. */
    private val ids = java.util.IdentityHashMap<Group, Long>().apply { r.groups.forEachIndexed { i, g -> put(g, i.toLong()) } }
    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = ids[shown[position]] ?: position.toLong()
        override fun hasStableIds() = true
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val card = (convertView?.tag as? Card) ?: Card()
            card.bind(shown[position])
            return card.root
        }
    }
    private val list = ListView(ctx).apply {
        divider = null
        adapter = this@ResultsScreen.adapter
        clipToPadding = false
        setPadding(0, 0, 0, ui.dp(8))
    }
    private val emptyTitle = ui.text("No duplicates found", 20f, c.onSurface, 500).apply { gravity = Gravity.CENTER }
    private val emptyBody = ui.text("", 14f, c.onSurfaceVariant).apply { gravity = Gravity.CENTER }
    private val empty = ui.vbox(emptyTitle, emptyBody.lp(t = ui.dp(8))).apply {
        gravity = Gravity.CENTER
        setPadding(ui.dp(32), 0, ui.dp(32), 0)
    }

    private val undoText = ui.text("", 14f, c.onSurface)
    private val undoBar = ui.hbox(undoText.lp(0, WRAP, 1f), ui.button("Undo", Ui.Style.TEXT) { undo() }).apply {
        background = ui.rounded(c.surfaceHigh, 16f)
        setPadding(ui.dp(16), ui.dp(4), ui.dp(4), ui.dp(4))
        visibility = View.GONE
    }
    private val selText = ui.text("", 13f, c.onSurfaceVariant, lines = 2)
    private val trashButton = ui.button("Move to trash", Ui.Style.DANGER) { trashSelected() }
    private val bottom = ui.BottomBar(selText, trashButton)

    override val view: View = ui.vbox(
        bar.view,
        chipScroll,
        FrameLayout(ctx).apply {
            addView(list.flp())
            addView(empty.flp(MATCH, MATCH))
        }.lp(MATCH, 0, 1f),
        undoBar.lp(l = ui.dp(12), r = ui.dp(12), b = ui.dp(4)),
        bottom.view,
    )

    override fun onShown() {
        bottom.layout()
        refresh()
        checkStillThere()
    }

    override fun onConfigurationChanged() = bottom.layout()

    private fun refresh() {
        val visible = r.groups.filter { r.shown(it) }
        val counts = visible.groupingBy { r.category(it) }.eachCount()
        if (filter != Category.ALL && (counts[filter] ?: 0) == 0) filter = Category.ALL
        shown = visible.filter { filter == Category.ALL || r.category(it) == filter }
        adapter.notifyDataSetChanged()

        chips.removeAllViews()
        if (visible.isNotEmpty()) {
            for (cat in Category.entries) {
                val n = if (cat == Category.ALL) visible.size else counts[cat] ?: 0
                if (n == 0) continue
                chips.addView(chip("${cat.label} $n", cat == filter) { filter = cat; refresh(); list.setSelection(0) }.lp(WRAP, WRAP, r = ui.dp(8)))
            }
        }
        chipScroll.visibility = if (visible.isEmpty()) View.GONE else View.VISIBLE

        val dupes = visible.filter { it.type != GroupType.POOR }
        val freeable = dupes.sumOf { g -> r.live(g).filter { !it.keeper }.sumOf { r.items[it.item].size } }
        bar.set(
            "Results",
            if (dupes.isEmpty()) "${Fmt.plural(r.photoCount, "photo", "photos")} and ${Fmt.plural(r.videoCount, "video", "videos")} checked"
            else "${Fmt.plural(dupes.size, "group", "groups")} · up to ${Fmt.bytes(freeable)} to free",
        )
        empty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        emptyTitle.text = if (r.trashed.isEmpty()) "No duplicates found" else "All cleaned up"
        emptyBody.text = "Checked ${Fmt.plural(r.photoCount, "photo", "photos")} and ${Fmt.plural(r.videoCount, "video", "videos")} " +
            "in ${Fmt.plural(r.folders, "folder", "folders")}" +
            (if (r.trashed.isNotEmpty()) ", and ${Fmt.plural(r.trashed.size, "file", "files")} went to the trash." else ".") +
            (if (r.unreadable > 0) "\n${Fmt.plural(r.unreadable, "file", "files")} couldn't be read." else "")

        val sel = selectedLive()
        val bytes = sel.sumOf { r.items[it].size }
        selText.text = if (sel.isEmpty()) "Nothing ticked. Tap the circle on a picture to tick it for the trash."
        else "${Fmt.plural(sel.size, "file", "files")} ticked · ${Fmt.bytes(bytes)}\nThey stay in the trash for 30 days."
        trashButton.text = if (sel.isEmpty()) "Move to trash" else "Move ${Fmt.count(sel.size)} to trash"
        ui.enable(trashButton, sel.isNotEmpty())
    }

    private fun selectedLive(): List<Int> = r.selected.filter { it !in r.trashed }.sorted()

    private fun chip(label: String, on: Boolean, onClick: () -> Unit) = ui.text(label, 14f, if (on) c.onSecondaryContainer else c.onSurfaceVariant, 500).apply {
        gravity = Gravity.CENTER
        minHeight = ui.dp(36)
        setPadding(ui.dp(16), ui.dp(6), ui.dp(16), ui.dp(6))
        background = ui.pressable(if (on) ui.rounded(c.secondaryContainer, 10f) else ui.rounded(Color.TRANSPARENT, 10f, c.outline, 1f), 10f)
        isSelected = on
        setOnClickListener { onClick() }
    }

    private fun toggle(item: Int) = tick(mapOf(item to (item !in r.selected)))

    /** Ticks by hand (null: back to the suggestion). What you keep is remembered in later scans too. */
    private fun tick(changes: Map<Int, Boolean?>) {
        r.tick(changes).let { Prefs.choose(ctx, it.keep, it.tick) }
        refresh()
    }

    private inner class Card {
        val title = ui.text("", 16f, c.onSurface, 500, lines = 1)
        val sub = ui.text("", 13f, c.onSurfaceVariant, lines = 1)
        val strip = ui.hbox().apply { gravity = Gravity.TOP }
        val stripTiles = ArrayList<Tiles.Tile>()
        val more = ui.text("", 14f, c.primary, 500).apply {
            gravity = Gravity.CENTER
            background = ui.pressable(ui.rounded(c.surfaceHigh, 16f), 16f)
        }
        val keepBest = ui.button("Keep best", Ui.Style.TEXT) {}
        val keepAll = ui.button("Keep all", Ui.Style.TEXT) {}
        val showAll = ui.button("Show all", Ui.Style.TEXT) {}
        private var bound: Group? = null
        private val scroller = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(ui.dp(16), 0, ui.dp(8), 0)
            addView(strip)
        }
        private val inner: LinearLayout = ui.vbox(
            title.lp(l = ui.dp(16), r = ui.dp(16)),
            sub.lp(l = ui.dp(16), r = ui.dp(16)),
            scroller.lp(t = ui.dp(12)),
            ui.hbox(ui.space(weight = 1f), showAll, keepAll, keepBest).lp(t = ui.dp(4), r = ui.dp(8)),
        ).apply {
            background = ui.rounded(c.surface, 24f)
            setPadding(0, ui.dp(16), 0, ui.dp(4))
        }
        /** List rows can't have margins: the card sits in a padded row. */
        val root: LinearLayout = ui.vbox(inner.lp(l = ui.dp(12), t = ui.dp(6), r = ui.dp(12), b = ui.dp(6))).apply { tag = this@Card }

        fun bind(g: Group) {
            // A card reused for another group starts at its first picture, the one to keep.
            if (bound !== g) scroller.scrollTo(0, 0)
            bound = g
            val live = r.live(g)
            title.text = Words.groupTitle(g, live)
            val ticked = live.filter { it.item in r.selected }
            val folders = live.map { r.items[it.item].folder.substringAfterLast('/').ifEmpty { "Top folder" } }.distinct()
            sub.text = (if (ticked.isEmpty()) "Nothing ticked" else "${Fmt.bytes(ticked.sumOf { r.items[it.item].size })} ticked") +
                " · " + folders.take(3).joinToString(", ") + if (folders.size > 3) " +${folders.size - 3}" else ""
            val max = if (live.size > MAX_STRIP) MAX_STRIP - 1 else live.size
            while (stripTiles.size < max) stripTiles.add(tiles.make())
            strip.removeAllViews()
            for (k in 0 until max) {
                val t = stripTiles[k]
                t.bind(g, live[k])
                strip.addView(t.root.lp(WRAP, WRAP, r = ui.dp(8)))
            }
            if (live.size > max) {
                more.text = "+${live.size - max}\nmore"
                more.setOnClickListener { act.push(GroupScreen(act, r, g)) }
                strip.addView(more.lp(thumbPx, thumbPx, r = ui.dp(8)))
            }
            val suggest = live.filter { it.suggestTrash }
            keepBest.visibility = if (g.type == GroupType.POOR || suggest.isEmpty()) View.GONE else View.VISIBLE
            keepBest.setOnClickListener { tick(live.associate { it.item to null }) }
            keepAll.text = if (g.type == GroupType.POOR) "Untick all" else "Keep all"
            keepAll.setOnClickListener { tick(live.associate { it.item to false }) }
            showAll.visibility = if (live.size > 6) View.VISIBLE else View.GONE
            showAll.setOnClickListener { act.push(GroupScreen(act, r, g)) }
        }
    }

    private fun openViewer(g: Group, m: Member) {
        val live = r.live(g)
        act.push(ViewerScreen(act, r, g, live, live.indexOf(m).coerceAtLeast(0)))
    }

    // -- trash -----------------------------------------------------------------------------------

    /** True while a trash request is being prepared or shown: one at a time. */
    private var busy = false

    private fun trashSelected() {
        if (busy) return
        if (selectedLive().isEmpty()) return
        busy = true
        ui.enable(trashButton, false)
        // Files deleted or moved by another app since the scan would make Android refuse the batch,
        // and a copy that has gone changes what "every copy" means: check every file of the groups
        // that have a tick, not only the ticked ones.
        val check = filesToCheck()
        Bg.run({ MediaIndex.existing(ctx, check.map { r.items[it] }) }) { present ->
            busy = false
            if (present == null) {
                act.toast("Couldn't check the files. Try again.")
                refresh()
                return@run
            }
            gone(check.filter { r.items[it].key !in present })
            val left = selectedLive()
            if (left.isEmpty()) {
                refresh()
                return@run
            }
            // Every copy of something ticked: say so before it all goes.
            val whole = r.groups.filter { g -> g.type != GroupType.POOR && r.shown(g) && r.live(g).all { it.item in r.selected } }
            if (whole.isNotEmpty()) {
                AlertDialog.Builder(ctx)
                    .setTitle("Every copy is ticked")
                    .setMessage("In ${Fmt.plural(whole.size, "group", "groups")}, every copy is ticked, so none would be left outside the trash. Move them anyway?")
                    .setPositiveButton("Move anyway") { _, _ -> trash(left) }
                    .setNegativeButton("Cancel", null)
                    .setOnDismissListener { refresh() }
                    .show()
                return@run
            }
            trash(left)
        }
    }

    /** Every file still listed in a group that has a tick (and the ticked blurry or dark photos). */
    private fun filesToCheck(): List<Int> {
        val out = LinkedHashSet<Int>()
        for (g in r.groups) {
            val live = r.live(g)
            if (live.none { it.item in r.selected }) continue
            for (m in live) if (g.type != GroupType.POOR || m.item in r.selected) out.add(m.item)
        }
        return out.toList()
    }

    /**
     * Files that left the phone without this app: shown as gone, never acted on. A copy that is now
     * the last of its group is unticked, since its group is no longer shown.
     */
    private fun gone(items: List<Int>) {
        if (items.isEmpty()) return
        r.trashed.addAll(items)
        r.selected.removeAll(items.toSet())
        val unticked = r.settle()
        act.mediaChanged = true
        act.toast(
            (if (items.size == 1) "1 file was already gone." else "${Fmt.count(items.size)} files were already gone.") +
                if (unticked.isEmpty()) "" else " A last copy left on its own was unticked.",
        )
    }

    /** In batches: one confirmation can only carry so many files. */
    private fun trash(items: List<Int>) {
        busy = true
        val chunks = items.chunked(BATCH)
        val asked = ArrayList<Int>()
        fun finish() {
            // What Android really did: a file still listed wasn't moved, whatever the answer said.
            Bg.run({ MediaIndex.existing(ctx, asked.map { r.items[it] }) }) { present ->
                busy = false
                val moved = if (present == null) asked else asked.filter { r.items[it].key !in present }
                trashed(moved)
                refresh()
            }
        }
        fun next(k: Int) {
            if (k == chunks.size) {
                finish()
                return
            }
            act.requestTrash(chunks[k].map { r.items[it].uri }, true) { ok ->
                if (ok) {
                    asked.addAll(chunks[k])
                    next(k + 1)
                } else {
                    finish()
                }
            }
        }
        next(0)
    }

    private fun trashed(moved: List<Int>) {
        if (moved.isEmpty()) return
        r.trashed.addAll(moved)
        r.settle()
        lastTrashed = moved
        act.mediaChanged = true
        val bytes = moved.sumOf { r.items[it].size }
        undoText.text = "${Fmt.plural(moved.size, "file", "files")} moved to the trash · ${Fmt.bytes(bytes)} freed"
        undoBar.visibility = View.VISIBLE
    }

    private fun undo() {
        val items = lastTrashed
        if (items.isEmpty() || busy) return
        busy = true
        act.requestTrash(items.map { r.items[it].uri }, false) { ok ->
            if (!ok) {
                busy = false
                return@requestTrash
            }
            Bg.run({ MediaIndex.existing(ctx, items.map { r.items[it] }) }) { present ->
                busy = false
                val back = if (present == null) items else items.filter { r.items[it].key in present }
                r.trashed.removeAll(back.toSet())
                lastTrashed = emptyList()
                act.mediaChanged = true
                undoBar.visibility = View.GONE
                refresh()
            }
        }
    }

    /**
     * Back in the app or on this screen: files may have been deleted elsewhere meanwhile (or an
     * answer from Android lost when the screen was rebuilt). Those are shown as gone.
     */
    override fun onResumed() = checkStillThere()

    private fun checkStillThere() {
        if (busy) return
        val check = filesToCheck()
        if (check.isEmpty()) return
        Bg.run({ MediaIndex.existing(ctx, check.map { r.items[it] }) }) { present ->
            if (present == null || busy) return@run
            val missing = check.filter { r.items[it].key !in present && it !in r.trashed }
            if (missing.isNotEmpty()) {
                gone(missing)
                refresh()
            }
        }
    }

    companion object {
        private const val MAX_STRIP = 12
        private const val BATCH = 500
    }
}

/** Every member of one large group, as a grid. */
class GroupScreen(act: MainActivity, private val r: ScanResult, private val g: Group) : Screen(act) {
    private var live = r.live(g)
    private val tilePx = ui.dp(104)
    private val tiles = Tiles(act, r, tilePx, { gr, m ->
        act.push(ViewerScreen(act, r, gr, live, live.indexOf(m).coerceAtLeast(0)))
    }, { item -> tick(mapOf(item to (item !in r.selected))) })
    private val bar = ui.topBar(Words.groupTitle(g, live), null, { act.pop() },
        ui.button("Tick all", Ui.Style.TEXT) { tick(live.filter { !it.keeper }.associate { it.item to true }) },
        ui.button("None", Ui.Style.TEXT) { tick(live.associate { it.item to false }) },
    )

    private fun tick(changes: Map<Int, Boolean?>) {
        r.tick(changes).let { Prefs.choose(ctx, it.keep, it.tick) }
        refresh()
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = live.size
        override fun getItem(position: Int) = live[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val t = (convertView?.tag as? Tiles.Tile) ?: tiles.make()
            t.bind(g, live[position])
            return t.root
        }
    }
    private val grid = GridView(ctx).apply {
        columnWidth = tilePx
        numColumns = GridView.AUTO_FIT
        stretchMode = GridView.STRETCH_SPACING_UNIFORM
        verticalSpacing = ui.dp(12)
        horizontalSpacing = ui.dp(8)
        setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(16))
        clipToPadding = false
        adapter = this@GroupScreen.adapter
    }
    override val view: View = ui.vbox(bar.view, grid.lp(MATCH, 0, 1f))

    override fun onShown() = refresh()

    private fun refresh() {
        live = r.live(g)
        val ticked = live.count { it.item in r.selected }
        bar.set(Words.groupTitle(g, live), "$ticked ticked for the trash")
        adapter.notifyDataSetChanged()
    }
}

private typealias Thumbs = io.github.tanay7.photocleaner.media.Thumbs
