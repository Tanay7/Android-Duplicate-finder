package io.github.tanay7.photocleaner

import android.content.Context
import android.content.SharedPreferences
import io.github.tanay7.photocleaner.scan.ScanOptions

/** Settings kept between runs: the folders to search and what to look for. */
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

    /**
     * Files ticked (true) or unticked (false) by hand, against the suggestion. They win over the
     * suggestion in every later scan too, so a photo you chose to keep stays kept.
     */
    fun overrides(ctx: Context): Map<String, Boolean> {
        val out = HashMap<String, Boolean>()
        for (e in sp(ctx).getStringSet("overrides", null) ?: emptySet()) {
            if (e.length > 1) out[e.substring(1)] = e[0] == '+'
        }
        return out
    }

    /** Records a hand-made choice for the file [key]; null forgets it (back to the suggestion). */
    fun setOverrides(ctx: Context, changes: Map<String, Boolean?>) {
        val now = overrides(ctx).toMutableMap()
        for ((k, v) in changes) if (v == null) now.remove(k) else now[k] = v
        val set = LinkedHashSet<String>()
        for ((k, v) in now.entries.toList().takeLast(MAX_OVERRIDES)) set.add((if (v) "+" else "-") + k)
        sp(ctx).edit().putStringSet("overrides", set).apply()
    }

    private const val MAX_OVERRIDES = 5000

    var Context.askedNotifications: Boolean
        get() = sp(this).getBoolean("askedNotifications", false)
        set(v) = sp(this).edit().putBoolean("askedNotifications", v).apply()
}
