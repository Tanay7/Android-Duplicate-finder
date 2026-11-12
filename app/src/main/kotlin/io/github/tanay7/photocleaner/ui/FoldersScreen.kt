package io.github.tanay7.photocleaner.ui

import android.app.AlertDialog
import android.database.ContentObserver
import android.provider.MediaStore
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import io.github.tanay7.photocleaner.MainActivity
import io.github.tanay7.photocleaner.Prefs
import io.github.tanay7.photocleaner.R
import io.github.tanay7.photocleaner.core.Check
import io.github.tanay7.photocleaner.core.FolderInfo
import io.github.tanay7.photocleaner.core.FolderNode
import io.github.tanay7.photocleaner.core.FolderSelection
import io.github.tanay7.photocleaner.core.FolderTree
import io.github.tanay7.photocleaner.core.Fmt
import io.github.tanay7.photocleaner.media.MediaIndex
import io.github.tanay7.photocleaner.scan.ScanOptions
import io.github.tanay7.photocleaner.scan.ScanRequest
import io.github.tanay7.photocleaner.scan.ScanService
import io.github.tanay7.photocleaner.scan.ScanState
import kotlin.concurrent.thread

/**
 * The start screen: every folder on the phone that holds photos or videos, as a tree with tick
 * boxes. Ticking a folder includes everything below it; the choice is remembered. The Scan button
 * searches only the ticked folders.
 */
class FoldersScreen(act: MainActivity) : Screen(act) {
    private val main = Handler(Looper.getMainLooper())
    private var folders: List<FolderInfo> = emptyList()
    private var roots: List<FolderNode> = emptyList()
    private var rows: List<FolderNode> = emptyList()
    private val expanded = HashSet<String>()
    private var selection = FolderSelection(HashSet())
    private var newFolders: Set<String> = emptySet()
    private var loading = false
    private var loadedAt = 0L
    private var destroyed = false
    private var pendingTest: Pair<String?, Boolean>? = null
    private var verboseNext = false

    private val bar = ui.topBar(
        "Photo Cleaner", null, null,
        ui.icon(R.drawable.ic_tune, c.onSurfaceVariant, "What to look for") { showOptions() },
        ui.icon(R.drawable.ic_info, c.onSurfaceVariant, "About") { showAbout() },
    )

    // Header: what the list needs said before it.
    private val accessTitle = ui.text("", 18f, c.onSurface, 500)
    private val accessBody = ui.text("", 14f, c.onSurfaceVariant)
    private val accessButton = ui.button("Allow access") { onAccessButton() }
    private val accessCard = ui.card(c.primaryContainer).apply {
        accessTitle.setTextColor(c.onPrimaryContainer)
        accessBody.setTextColor(c.onPrimaryContainer)
        addView(accessTitle)
        addView(accessBody.lp(t = ui.dp(6)))
        addView(accessButton.lp(WRAP, WRAP, t = ui.dp(14)))
    }
    private val resultsText = ui.text("", 14f, c.onSurfaceVariant)
    private val resultsCard = ui.card().apply {
        addView(ui.text("Last scan", 16f, c.onSurface, 500))
        addView(resultsText.lp(t = ui.dp(4)))
        addView(ui.button("View results", Ui.Style.TONAL) { openResults() }.lp(WRAP, WRAP, t = ui.dp(12)))
    }
    private val countText = ui.text("", 13f, c.onSurfaceVariant)
    private val sectionHead = ui.vbox(
        ui.hbox(
            ui.text("Folders to search", 18f, c.onSurface, 500).lp(0, WRAP, 1f),
            ui.button("All", Ui.Style.TEXT) { setAll(true) },
            ui.button("None", Ui.Style.TEXT) { setAll(false) },
        ),
        countText,
        ui.text("Tick a folder to search it. A folder's subfolders are ticked with it; open it with the arrow to choose them one by one.",
            13f, c.onSurfaceVariant).lp(t = ui.dp(4)),
    ).apply { setPadding(ui.dp(20), ui.dp(16), ui.dp(8), ui.dp(8)) }
    private val spinner = ProgressBar(ctx).apply {
        indeterminateTintList = ColorStateList.valueOf(c.primary)
    }
    private val emptyText = ui.text("", 14f, c.onSurfaceVariant).apply {
        gravity = Gravity.CENTER
        setPadding(ui.dp(32), ui.dp(24), ui.dp(32), ui.dp(24))
    }
    private val header = ui.vbox(
        accessCard.lp(l = ui.dp(16), t = ui.dp(4), r = ui.dp(16), b = ui.dp(8)),
        resultsCard.lp(l = ui.dp(16), t = ui.dp(4), r = ui.dp(16), b = ui.dp(8)),
        sectionHead,
        spinner.lp(WRAP, WRAP, gravity = Gravity.CENTER_HORIZONTAL, t = ui.dp(8), b = ui.dp(8)),
        emptyText,
    )

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val holder = (convertView?.tag as? Row) ?: Row()
            holder.bind(rows[position])
            return holder.root
        }
    }

    private val list = ListView(ctx).apply {
        divider = null
        addHeaderView(header, null, false)
        adapter = this@FoldersScreen.adapter
        clipToPadding = false
        setPadding(0, 0, 0, ui.dp(8))
    }

    private val summary = ui.text("", 13f, c.onSurfaceVariant, lines = 2)
    private val scanButton = ui.button("Scan") { startScan() }
    private val bottom = ui.BottomBar(summary, scanButton)

    override val view: View = ui.vbox(bar.view, list.lp(MATCH, 0, 1f), bottom.view)

    override fun onConfigurationChanged() = bottom.layout()

    /**
     * Reloads the list when the media index changes while it shows: photos taken or copied meanwhile,
     * or files Android is still indexing, appear by themselves. Bursts of changes are read once.
     */
    private val reloadSoon = Runnable { load(force = true) }
    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            main.removeCallbacks(reloadSoon)
            main.postDelayed(reloadSoon, 1000)
        }
    }
    private var observing = false

    override fun onShown() {
        bottom.layout()
        refresh()
        load(force = act.mediaChanged)
        act.mediaChanged = false
        if (!observing && act.access() != MainActivity.Access.NONE) {
            try {
                // Every volume (phone, SD cards) notifies under the media authority.
                ctx.contentResolver.registerContentObserver(MediaStore.AUTHORITY_URI, true, observer)
                observing = true
            } catch (_: Exception) {
                // no live updates: the list still reloads every time the screen comes back
            }
        }
    }

    override fun onHidden() = stopObserving()

    override fun onResumed() = load(force = false)

    override fun onDestroy() {
        destroyed = true
        stopObserving()
    }

    private fun stopObserving() {
        main.removeCallbacks(reloadSoon)
        if (!observing) return
        observing = false
        try {
            ctx.contentResolver.unregisterContentObserver(observer)
        } catch (_: Exception) {
        }
    }

    // -- data ------------------------------------------------------------------------------------

    /** Re-reads the folders from the media index (in the background; the list stays meanwhile). */
    private fun load(force: Boolean) {
        if (loading || destroyed) return
        if (act.access() == MainActivity.Access.NONE) {
            folders = emptyList()
            roots = emptyList()
            rows = emptyList()
            refresh()
            return
        }
        if (!force && SystemClock.elapsedRealtime() - loadedAt < 3000 && roots.isNotEmpty()) return
        loading = true
        refresh()
        val appCtx = ctx.applicationContext
        thread(name = "folders") {
            // Nothing thrown here may escape: an uncaught error on any thread ends the app.
            val (found, tree) = try {
                val f = MediaIndex.folders(MediaIndex.query(appCtx))
                val labels = try {
                    MediaIndex.volumeLabels(appCtx)
                } catch (_: Exception) {
                    emptyMap()
                }
                f to FolderTree.build(f) { labels[it] ?: if (it == "external_primary") "Phone" else "SD card" }
            } catch (t: Throwable) {
                android.util.Log.w("PhotoCleaner", "Couldn't list the folders", t)
                emptyList<FolderInfo>() to emptyList()
            }
            main.post { if (!destroyed) loaded(found, tree) }
        }
    }

    private fun loaded(found: List<FolderInfo>, tree: List<FolderNode>) {
        val first = loadedAt == 0L
        val before = folders.mapTo(HashSet()) { it.key }
        folders = found
        roots = tree
        loading = false
        loadedAt = SystemClock.elapsedRealtime()
        val keys = found.map { it.key }
        val known = Prefs.knownFolders(ctx)
        if (first) {
            selection = FolderSelection(FolderTree.initialSelection(keys, Prefs.selection(ctx), known, Prefs.allSelected(ctx)))
            roots.forEach { expanded.add(it.key) }
        } else if (Prefs.allSelected(ctx)) {
            // A folder that appeared while the app was open follows the same rule as at start.
            for (k in keys) if (k !in before) selection.selected.add(k)
        }
        newFolders = if (known == null) emptySet() else keys.filterTo(HashSet()) { it !in known }
        refresh()
        pendingTest?.let {
            pendingTest = null
            applyTest(it.first, it.second)
        }
    }

    private fun save() {
        Prefs.saveSelection(ctx, selection.selected.intersect(folders.map { it.key }.toSet()), folders.mapTo(HashSet()) { it.key })
    }

    // -- view ------------------------------------------------------------------------------------

    private fun refresh() {
        val access = act.access()
        accessCard.visibility = if (access == MainActivity.Access.FULL) View.GONE else View.VISIBLE
        when (access) {
            MainActivity.Access.NONE -> {
                accessTitle.text = "Allow access to your photos and videos"
                accessBody.text = "Photo Cleaner compares the photos and videos in the folders you choose. " +
                    "They never leave your phone: the app has no internet access."
                accessButton.text = if (act.mediaBlocked()) "Open settings" else "Allow access"
            }
            MainActivity.Access.PARTIAL -> {
                accessTitle.text = "You've allowed only some photos"
                accessBody.text = "Photo Cleaner can only compare the photos you picked. Allow all photos to find every duplicate."
                accessButton.text = "Allow all photos"
            }
            MainActivity.Access.FULL -> Unit
        }
        val result = ScanState.result
        resultsCard.visibility = if (result != null) View.VISIBLE else View.GONE
        if (result != null) {
            val dupes = result.groups.sumOf { g -> g.members.count { it.item !in result.trashed && it.suggestTrash } }
            val groups = result.groups.count { result.shown(it) }
            resultsText.text = if (groups == 0) "Nothing left to clean in ${Fmt.plural(result.folders, "folder", "folders")}."
            else "${Fmt.plural(groups, "group", "groups")} to review · ${Fmt.plural(dupes, "duplicate", "duplicates")} suggested"
        }
        sectionHead.visibility = if (access == MainActivity.Access.NONE) View.GONE else View.VISIBLE
        spinner.visibility = if (loading && roots.isEmpty()) View.VISIBLE else View.GONE
        emptyText.visibility = if (!loading && access != MainActivity.Access.NONE && roots.isEmpty() && loadedAt > 0) View.VISIBLE else View.GONE
        emptyText.text = "No photos or videos found on this phone."

        rows = FolderTree.visible(roots, expanded)
        adapter.notifyDataSetChanged()

        val chosen = folders.filter { it.key in selection.selected }
        val o = Prefs.options(ctx)
        val photos = if (o.photos) chosen.sumOf { it.photos } else 0
        val videos = if (o.videos) chosen.sumOf { it.videos } else 0
        countText.text = "${Fmt.count(chosen.size)} of ${Fmt.plural(folders.size, "folder", "folders")} ticked"
        summary.text = when {
            access == MainActivity.Access.NONE -> "Allow access to choose folders."
            chosen.isEmpty() -> "Tick at least one folder."
            else -> listOfNotNull(
                Fmt.plural(chosen.size, "folder", "folders"),
                if (o.photos) Fmt.plural(photos, "photo", "photos") else null,
                if (o.videos) Fmt.plural(videos, "video", "videos") else null,
                Fmt.bytes(chosen.sumOf { it.bytes }),
            ).joinToString(" · ") + "\n" + lookingFor(o)
        }
        scanButton.text = if (chosen.isEmpty()) "Scan" else "Scan ${Fmt.plural(chosen.size, "folder", "folders")}"
        ui.enable(scanButton, access != MainActivity.Access.NONE && chosen.isNotEmpty() && (photos + videos) > 0 && !ScanState.running)
    }

    private fun lookingFor(o: ScanOptions): String {
        val parts = ArrayList<String>()
        parts.add("identical copies")
        if (o.photos && o.similar) parts.add("look-alikes")
        if (o.photos && o.bursts) parts.add("bursts")
        if (o.photos && o.poor) parts.add("blurry or dark photos")
        return "Looking for " + parts.joinToString(", ")
    }

    private fun statsText(n: FolderNode): String {
        val parts = ArrayList<String>()
        if (n.photos > 0) parts.add(Fmt.plural(n.photos, "photo", "photos"))
        if (n.videos > 0) parts.add(Fmt.plural(n.videos, "video", "videos"))
        parts.add(Fmt.bytes(n.bytes))
        return parts.joinToString(" · ")
    }

    private inner class Row {
        val chevron = ui.icon(R.drawable.ic_chevron_right, c.onSurfaceVariant, null, 40, 8) {}
        val check = ImageView(ctx)
        val icon = ImageView(ctx)
        val label = ui.text("", 16f, c.onSurface, lines = 1)
        val stats = ui.text("", 13f, c.onSurfaceVariant, lines = 1)
        val badge = ui.text("New", 11f, c.onPrimaryContainer, 500).apply {
            background = ui.rounded(c.primaryContainer, 8f)
            setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(2))
        }
        val root: LinearLayout = ui.hbox(
            chevron,
            check.lp(ui.dp(24), ui.dp(24), r = ui.dp(12)),
            icon.lp(ui.dp(20), ui.dp(20), r = ui.dp(12)),
            ui.vbox(label, stats).lp(0, WRAP, 1f),
            badge.lp(WRAP, WRAP, l = ui.dp(8)),
        ).apply {
            minimumHeight = ui.dp(64)
            background = ui.pressable(null, 0f)
            tag = this@Row
        }

        fun bind(n: FolderNode) {
            root.setPadding(ui.dp(4 + n.depth * 20), ui.dp(6), ui.dp(16), ui.dp(6))
            val open = n.key in expanded
            chevron.visibility = if (n.children.isEmpty()) View.INVISIBLE else View.VISIBLE
            chevron.rotation = if (open) 90f else 0f
            chevron.contentDescription = if (open) "Close ${n.label}" else "Open ${n.label}"
            chevron.setOnClickListener {
                if (!expanded.remove(n.key)) expanded.add(n.key)
                refresh()
            }
            val state = selection.state(n)
            check.setImageResource(
                when (state) {
                    Check.ALL -> R.drawable.ic_check_box
                    Check.SOME -> R.drawable.ic_check_box_some
                    Check.NONE -> R.drawable.ic_check_box_blank
                },
            )
            check.imageTintList = ColorStateList.valueOf(if (state == Check.NONE) c.onSurfaceVariant else c.primary)
            icon.setImageResource(
                when {
                    !n.isVolume -> R.drawable.ic_folder
                    n.volume == "external_primary" -> R.drawable.ic_phone
                    else -> R.drawable.ic_sd_card
                },
            )
            icon.imageTintList = ColorStateList.valueOf(c.onSurfaceVariant)
            label.text = n.label
            stats.text = statsText(n)
            badge.visibility = if (n.folder?.key in newFolders) View.VISIBLE else View.GONE
            root.contentDescription = "${n.label}, ${stats.text}, " + when (state) {
                Check.ALL -> "ticked"
                Check.SOME -> "partly ticked"
                Check.NONE -> "not ticked"
            }
            root.setOnClickListener {
                selection.toggle(n)
                save()
                refresh()
            }
        }
    }

    private fun setAll(on: Boolean) {
        selection.setAll(roots, on)
        save()
        refresh()
    }

    // -- actions ---------------------------------------------------------------------------------

    private fun onAccessButton() {
        if (act.mediaBlocked()) act.openAppSettings() else act.requestMedia { load(force = true); refresh() }
    }

    private fun startScan() {
        val keys = selection.selected.intersect(folders.map { it.key }.toSet())
        if (keys.isEmpty() || ScanState.running) return
        save()
        act.askNotificationsOnce()
        ScanService.start(ctx, ScanRequest(keys, Prefs.options(ctx), act.access() == MainActivity.Access.FULL, verboseNext))
        verboseNext = false
        act.push(ScanScreen(act))
    }

    private fun openResults() {
        val r = ScanState.result ?: return
        act.push(ResultsScreen(act, r))
    }

    /** Called with the on-device test's extras: tick [folders] (comma-separated, "*" for all), maybe scan. */
    private var testSince = 0L

    fun testRequest(folders: String?, autoscan: Boolean) {
        testSince = SystemClock.elapsedRealtime()
        if (loading || loadedAt == 0L) {
            pendingTest = folders to autoscan
            load(force = true)
        } else {
            applyTest(folders, autoscan)
        }
    }

    private fun applyTest(wanted: String?, autoscan: Boolean) {
        if (wanted != null) {
            val list = wanted.split(',').map { it.trim().trim('/') }
            val match = folders.filter { f -> list.contains("*") || list.any { it.equals(f.path, ignoreCase = true) } }
            val missing = list.filter { w -> w != "*" && folders.none { it.path.equals(w, ignoreCase = true) } }
            if ((match.isEmpty() || missing.isNotEmpty()) && SystemClock.elapsedRealtime() - testSince < 120_000) {
                // Not indexed yet: wait for the media index to catch up (the observer reloads the list).
                android.util.Log.i("PhotoCleaner", "TEST waiting for folders: $missing")
                pendingTest = wanted to autoscan
                main.postDelayed(reloadSoon, 3000)
                return
            }
            selection.selected.clear()
            for (f in match) selection.selected.add(f.key)
            save()
            refresh()
        }
        if (autoscan) {
            verboseNext = true
            startScan()
        }
    }

    // -- dialogs ---------------------------------------------------------------------------------

    private fun showOptions() {
        val o = Prefs.options(ctx)
        fun switchRow(title: String, sub: String, on: Boolean): Pair<View, Switch> {
            val sw = Switch(ctx).apply { isChecked = on }
            val row = ui.hbox(
                ui.vbox(ui.text(title, 16f, c.onSurface), ui.text(sub, 13f, c.onSurfaceVariant)).lp(0, WRAP, 1f, r = ui.dp(12)),
                sw,
            ).apply {
                setPadding(ui.dp(24), ui.dp(10), ui.dp(24), ui.dp(10))
                background = ui.pressable(null, 0f)
                setOnClickListener { sw.toggle() }
            }
            return row to sw
        }
        val photos = switchRow("Photos", "Identical copies of photos", o.photos)
        val videos = switchRow("Videos", "Identical copies of videos", o.videos)
        val similar = switchRow("Look-alike photos", "Resized, re-saved, turned or cropped copies", o.similar)
        val bursts = switchRow("Burst shots", "Photos taken a moment apart: keeps the sharpest", o.bursts)
        val poor = switchRow("Blurry and dark photos", "Listed on their own, never ticked for you", o.poor)
        val strict = RadioButton(ctx).apply { text = "Strict: only near-identical pictures"; id = View.generateViewId() }
        val normal = RadioButton(ctx).apply { text = "Normal"; id = View.generateViewId() }
        val loose = RadioButton(ctx).apply { text = "Loose: also more edited copies"; id = View.generateViewId() }
        val group = RadioGroup(ctx).apply {
            addView(strict)
            addView(normal)
            addView(loose)
            check(when { o.maxBits <= 4 -> strict.id; o.maxBits >= 12 -> loose.id; else -> normal.id })
            setPadding(ui.dp(20), 0, ui.dp(24), ui.dp(8))
        }
        val content = ui.vbox(
            ui.text("Identical copies are always looked for.", 14f, c.onSurfaceVariant).apply { setPadding(ui.dp(24), ui.dp(8), ui.dp(24), ui.dp(8)) },
            photos.first, videos.first, similar.first, bursts.first, poor.first,
            ui.text("How alike look-alikes must be", 16f, c.onSurface, 500).apply { setPadding(ui.dp(24), ui.dp(16), ui.dp(24), ui.dp(4)) },
            group,
        )
        AlertDialog.Builder(ctx)
            .setTitle("What to look for")
            .setView(ScrollView(ctx).apply { addView(content) })
            .setPositiveButton("Done") { _, _ ->
                val bits = when (group.checkedRadioButtonId) {
                    strict.id -> 4
                    loose.id -> 12
                    else -> 8
                }
                val p = photos.second.isChecked
                val v = videos.second.isChecked
                Prefs.saveOptions(
                    ctx,
                    ScanOptions(
                        photos = p || !v,
                        videos = v,
                        similar = similar.second.isChecked,
                        bursts = bursts.second.isChecked,
                        poor = poor.second.isChecked,
                        maxBits = bits,
                    ),
                )
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAbout() {
        val version = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (_: Exception) {
            ""
        }
        val text = TextView(ctx).apply {
            setPadding(ui.dp(24), ui.dp(8), ui.dp(24), ui.dp(8))
            setTextColor(c.onSurfaceVariant)
            textSize = 14f
            text = """
                Photo Cleaner finds identical copies, look-alike photos (resized, re-saved, turned or cropped), burst shots, and blurry or dark photos in the folders you choose. For each group it suggests the copy to keep: the largest and sharpest, an original from the camera over one saved from a chat, and always your favourites.

                Nothing is deleted for good. Files you remove go to the phone's trash and stay there for 30 days: Undo brings them straight back, and so does the trash in the Files or Photos app.

                Nothing leaves your phone. The app has no internet access. It remembers fingerprints of your photos in its own storage, so the next scan only reads new files.

                Version $version
            """.trimIndent()
        }
        AlertDialog.Builder(ctx)
            .setTitle("About Photo Cleaner")
            .setView(ScrollView(ctx).apply { addView(text) })
            .setPositiveButton("OK", null)
            .show()
    }
}
