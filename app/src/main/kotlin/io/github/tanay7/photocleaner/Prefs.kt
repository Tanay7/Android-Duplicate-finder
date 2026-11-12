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

    var Context.askedNotifications: Boolean
        get() = sp(this).getBoolean("askedNotifications", false)
        set(v) = sp(this).edit().putBoolean("askedNotifications", v).apply()
}
