package io.github.tanay7.photocleaner

import android.content.Context
import android.content.SharedPreferences
import io.github.tanay7.photocleaner.scan.ScanOptions

/**
 * What is kept between runs: the folders to search, what to look for, the choices made by hand in
 * the results, the files to read one at a time after the app died, and the questions already asked.
 */
object Prefs {
    private fun sp(ctx: Context): SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** The selected folders, or null if the user never chose. */
    fun selection(ctx: Context): Set<String>? = sp(ctx).getStringSet("selected", null)?.toHashSet()

    /** Every folder that existed when the selection was saved: anything else is new since. */
    fun knownFolders(ctx: Context): Set<String>? = sp(ctx).getStringSet("known", null)?.toHashSet()

    /** Everything was selected when saved: folders that appear later are selected too. */
    fun allSelected(ctx: Context): Boolean = sp(ctx).getBoolean("all", true)

    fun saveSelection(ctx: Context, selected: Set<String>, known: Set<String>) {
        sp(ctx).edit()
            .putStringSet("selected", HashSet(selected))
            .putStringSet("known", HashSet(known))
            .putBoolean("all", known.all { it in selected })
            .apply()
    }

    fun options(ctx: Context): ScanOptions {
        val p = sp(ctx)
        return ScanOptions(
            photos = p.getBoolean("photos", true),
            videos = p.getBoolean("videos", true),
            similar = p.getBoolean("similar", true),
            bursts = p.getBoolean("bursts", true),
            poor = p.getBoolean("poor", true),
            maxBits = p.getInt("maxBits", 8),
        )
    }

    fun saveOptions(ctx: Context, o: ScanOptions) {
        sp(ctx).edit()
            .putBoolean("photos", o.photos)
            .putBoolean("videos", o.videos)
            .putBoolean("similar", o.similar)
            .putBoolean("bursts", o.bursts)
            .putBoolean("poor", o.poor)
            .putInt("maxBits", o.maxBits)
            .apply()
    }

    /** The last scan's folders and options: its results come back if Android closed the app meanwhile. */
    fun saveLastScan(ctx: Context, folders: Set<String>, o: ScanOptions) {
        sp(ctx).edit().putStringSet("lastFolders", HashSet(folders)).putString("lastOptions", encode(o)).apply()
    }

    fun lastScan(ctx: Context): Pair<Set<String>, ScanOptions>? {
        val f = sp(ctx).getStringSet("lastFolders", null) ?: return null
        val o = decode(sp(ctx).getString("lastOptions", null)) ?: return null
        return f.toHashSet() to o
    }

    private fun encode(o: ScanOptions) = listOf(o.photos, o.videos, o.similar, o.bursts, o.poor).joinToString(",") + ",${o.maxBits}"

    private fun decode(s: String?): ScanOptions? {
        val p = s?.split(',') ?: return null
        if (p.size != 6) return null
        val b = p.take(5).map { it == "true" }
        return ScanOptions(b[0], b[1], b[2], b[3], b[4], p[5].toIntOrNull() ?: 8)
    }

    // -- choices made by hand ----------------------------------------------------------------------

    private var keepsCache: HashSet<String>? = null
    private var ticksCache: HashSet<String>? = null

    private fun keepsLive(ctx: Context) = keepsCache ?: (sp(ctx).getStringSet("keeps", null)?.toHashSet() ?: HashSet()).also { keepsCache = it }
    private fun ticksLive(ctx: Context) = ticksCache ?: (sp(ctx).getStringSet("ticks", null)?.toHashSet() ?: HashSet()).also { ticksCache = it }

    /**
     * Files you chose to keep although they were suggested for the trash. They are never ticked for
     * you again, in any later scan (until the file is gone). At most [MAX_CHOICES] are remembered.
     */
    @Synchronized fun keeps(ctx: Context): Set<String> = HashSet(keepsLive(ctx))

    /**
     * Files you ticked although they weren't suggested. Remembered for the results at hand only (so
     * they come back if Android closes the app meanwhile), never for a later scan: an old tick must
     * not send a file to the trash months later.
     */
    @Synchronized fun ticks(ctx: Context): Set<String> = HashSet(ticksLive(ctx))

    /** Records choices: for each file key, true adds it to the keeps (or ticks), false takes it out. */
    @Synchronized fun choose(ctx: Context, keep: Map<String, Boolean>, tick: Map<String, Boolean>) {
        val keeps = keepsLive(ctx)
        val ticks = ticksLive(ctx)
        for ((k, on) in keep) if (!on) keeps.remove(k) else if (keeps.size < MAX_CHOICES) keeps.add(k)
        for ((k, on) in tick) if (!on) ticks.remove(k) else if (ticks.size < MAX_CHOICES) ticks.add(k)
        sp(ctx).edit().putStringSet("keeps", HashSet(keeps)).putStringSet("ticks", HashSet(ticks)).apply()
    }

    /** A new scan starts: the ticks made in the last results don't carry over. */
    @Synchronized fun clearTicks(ctx: Context) {
        if (ticksLive(ctx).isEmpty()) return
        ticksCache = HashSet()
        sp(ctx).edit().remove("ticks").apply()
    }

    /**
     * Forgets the keeps of files that are gone, so the list doesn't grow forever. Only on the
     * [volumes] that are in the phone: what you kept on an SD card stays kept while the card is out.
     */
    @Synchronized fun pruneKeeps(ctx: Context, live: Set<String>, volumes: Set<String>) {
        val keeps = keepsLive(ctx)
        if (!keeps.removeAll { it !in live && it.substringBefore('/') in volumes }) return
        sp(ctx).edit().putStringSet("keeps", HashSet(keeps)).apply()
    }

    private const val MAX_CHOICES = 20_000

    /** Files that were being read when the app died: the next scan reads them one at a time. */
    fun suspects(ctx: Context): Set<String> = sp(ctx).getStringSet("suspects", null)?.toHashSet() ?: emptySet()

    fun setSuspects(ctx: Context, keys: Set<String>) {
        if (keys.isEmpty() && !sp(ctx).contains("suspects")) return
        sp(ctx).edit().putStringSet("suspects", HashSet(keys)).apply()
    }

    /** Asked once each, before the first scan: notifications, and (after an update) the photos' own bytes. */
    var Context.askedNotifications: Boolean
        get() = sp(this).getBoolean("askedNotifications", false)
        set(v) = sp(this).edit().putBoolean("askedNotifications", v).apply()

    var Context.askedOriginals: Boolean
        get() = sp(this).getBoolean("askedOriginals", false)
        set(v) = sp(this).edit().putBoolean("askedOriginals", v).apply()
}
