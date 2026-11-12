package io.github.tanay7.photocleaner.media

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import io.github.tanay7.photocleaner.core.ImageFeatures

/** What was learnt about one file: its fingerprint, and for photos what its pixels look like. */
class FeatureRow(
    val key: String,
    val size: Long,
    val modified: Long,
    /** SHA-256 of the whole file (hex), or null when it couldn't be read. */
    val sha: String?,
    /** Full-size pixels after rotation, 0 when unknown. */
    val width: Int,
    val height: Int,
    /** Capture time from EXIF (seconds) and camera make and model, when the photo has them. */
    val takenAt: Double?,
    val camera: String?,
    /** Null when only the fingerprint was needed, or the photo couldn't be decoded. */
    val features: ImageFeatures?,
    val error: String?,
) {
    /** Still describes the file: same size, not changed since, and computed by this version. */
    fun matches(m: MediaItem) = size == m.size && modified == m.modified
}

/**
 * Remembers fingerprints between scans (a SQLite database in the app's private storage), so a second
 * scan only reads new and changed files. Rows are found by MediaStore id and checked against the
 * file's size and modification time.
 *
 * A cache must never stop a scan: a damaged database is deleted and started again, and when the
 * storage is full the rows just aren't kept this time.
 */
class FeatureCache(private val ctx: Context) : SQLiteOpenHelper(ctx, NAME, null, ALGO) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE features (
                key TEXT PRIMARY KEY, size INTEGER, modified INTEGER, sha TEXT, w INTEGER, h INTEGER,
                taken REAL, camera TEXT, analysed INTEGER, phash INTEGER, v0 INTEGER, v1 INTEGER, v2 INTEGER,
                v3 INTEGER, crop INTEGER, flat INTEGER, cb REAL, cr REAL, sat REAL, sharp REAL, detail REAL,
                med REAL, black REAL, white REAL, error TEXT)""",
        )
    }

    /** A new algorithm version recomputes everything: the old fingerprints aren't comparable. */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS features")
        onCreate(db)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = onUpgrade(db, oldVersion, newVersion)

    fun load(): HashMap<String, FeatureRow> = try {
        read()
    } catch (e: Exception) {
        Log.w(TAG, "Fingerprint cache unreadable, starting a new one", e)
        reset()
        HashMap()
    }

    private fun read(): HashMap<String, FeatureRow> {
        val out = HashMap<String, FeatureRow>()
        readableDatabase.rawQuery("SELECT * FROM features", null).use { c ->
            val idx = (0 until c.columnCount).associateBy { c.getColumnName(it) }
            fun i(n: String) = idx.getValue(n)
            fun dbl(n: String): Double? = if (c.isNull(i(n))) null else c.getDouble(i(n))
            fun flt(n: String): Float = if (c.isNull(i(n))) Float.NaN else c.getFloat(i(n))
            while (c.moveToNext()) {
                val features = if (c.getInt(i("analysed")) != 0) {
                    ImageFeatures(
                        phash = c.getLong(i("phash")),
                        variants = longArrayOf(c.getLong(i("v0")), c.getLong(i("v1")), c.getLong(i("v2")), c.getLong(i("v3"))),
                        cropHash = c.getLong(i("crop")),
                        flat = c.getInt(i("flat")) != 0,
                        cb = flt("cb"),
                        cr = flt("cr"),
                        saturation = flt("sat"),
                        sharpness = flt("sharp"),
                        detail = flt("detail"),
                        median = flt("med"),
                        black = flt("black"),
                        white = flt("white"),
                    )
                } else {
                    null
                }
                val key = c.getString(i("key"))
                out[key] = FeatureRow(
                    key = key,
                    size = c.getLong(i("size")),
                    modified = c.getLong(i("modified")),
                    sha = c.getString(i("sha")),
                    width = c.getInt(i("w")),
                    height = c.getInt(i("h")),
                    takenAt = dbl("taken"),
                    camera = c.getString(i("camera")),
                    features = features,
                    error = c.getString(i("error")),
                )
            }
        }
        return out
    }

    fun save(rows: Collection<FeatureRow>) {
        if (rows.isEmpty()) return
        try {
            write(rows)
        } catch (e: Exception) {
            Log.w(TAG, "Fingerprints not kept (${rows.size})", e)
        }
    }

    private fun write(rows: Collection<FeatureRow>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (r in rows) {
                val v = ContentValues()
                v.put("key", r.key)
                v.put("size", r.size)
                v.put("modified", r.modified)
                v.put("sha", r.sha)
                v.put("w", r.width)
                v.put("h", r.height)
                v.put("taken", r.takenAt)
                v.put("camera", r.camera)
                v.put("error", r.error)
                val f = r.features
                v.put("analysed", if (f != null) 1 else 0)
                if (f != null) {
                    v.put("phash", f.phash)
                    v.put("v0", f.variants[0])
                    v.put("v1", f.variants[1])
                    v.put("v2", f.variants[2])
                    v.put("v3", f.variants[3])
                    v.put("crop", f.cropHash)
                    v.put("flat", if (f.flat) 1 else 0)
                    v.put("cb", f.cb)
                    v.put("cr", f.cr)
                    v.put("sat", f.saturation)
                    if (f.sharpness.isNaN()) v.putNull("sharp") else v.put("sharp", f.sharpness)
                    v.put("detail", f.detail)
                    v.put("med", f.median)
                    v.put("black", f.black)
                    v.put("white", f.white)
                }
                db.insertWithOnConflict("features", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Forgets files that no longer exist, so the cache doesn't grow forever. */
    fun prune(liveKeys: Set<String>, known: Collection<String>) {
        val gone = known.filter { it !in liveKeys }
        if (gone.isEmpty()) return
        try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                for (k in gone) db.delete("features", "key = ?", arrayOf(k))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't forget deleted files", e)
        }
    }

    /** Deletes the database file; the next use creates a new, empty one. */
    private fun reset() {
        try {
            close()
            ctx.deleteDatabase(NAME)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't delete the fingerprint cache", e)
        }
    }

    companion object {
        /** Raising this recomputes every cached fingerprint (the database version). */
        const val ALGO = 1
        private const val NAME = "features.db"
        private const val TAG = "PhotoCleaner"
    }
}
