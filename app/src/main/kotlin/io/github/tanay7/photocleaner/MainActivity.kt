package io.github.tanay7.photocleaner

import android.Manifest
import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import io.github.tanay7.photocleaner.Prefs.askedNotifications
import io.github.tanay7.photocleaner.scan.ScanRequest
import io.github.tanay7.photocleaner.scan.ScanService
import io.github.tanay7.photocleaner.scan.ScanState
import io.github.tanay7.photocleaner.ui.FoldersScreen
import io.github.tanay7.photocleaner.ui.MATCH
import io.github.tanay7.photocleaner.ui.ResultsScreen
import io.github.tanay7.photocleaner.ui.ScanScreen
import io.github.tanay7.photocleaner.ui.Screen
import io.github.tanay7.photocleaner.ui.Ui

/**
 * The app's only activity. Screens are plain views kept on a stack; the activity handles what
 * needs it: the window, Back, permissions and the system's "move to trash?" confirmation.
 */
class MainActivity : Activity() {
    lateinit var ui: Ui
        private set
    private lateinit var root: FrameLayout
    private val stack = ArrayList<Screen>()
    private val backCallback = OnBackInvokedCallback { goBack() }
    private var backRegistered = false
    private var trashDone: ((Boolean) -> Unit)? = null
    private var permissionDone: (() -> Unit)? = null

    /** Files were moved to or from the trash since the folder list was read. */
    var mediaChanged = false

    enum class Access { NONE, PARTIAL, FULL }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        root = FrameLayout(this)
        setContentView(root)
        // Edge to edge: keep the content clear of the status and navigation bars and any camera cut-out.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsets.CONSUMED
        }
        push(FoldersScreen(this))
        val result = ScanState.result
        val wasShowingResults = savedInstanceState?.getBoolean(STATE_RESULTS) == true
        val wasScanning = savedInstanceState?.getBoolean(STATE_SCANNING) == true
        when {
            ScanState.running -> push(ScanScreen(this))
            // A scan that finished while the screen was gone: straight to what it found.
            result != null && (wasShowingResults || wasScanning) -> push(ResultsScreen(this, result))
            // Android closed the app while its results were open: bring them back (quick: the
            // fingerprints are remembered, so nothing is read again).
            wasShowingResults -> restorePending = true
        }
        handleIntent(intent)
    }

    private var restorePending = false

    private fun restore() {
        val last = Prefs.lastScan(this) ?: return
        val access = access()
        if (access == Access.NONE || ScanState.running) return
        ScanService.start(this, ScanRequest(last.first, last.second, access == Access.FULL, verbose = false, restoring = true))
        push(ScanScreen(this))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_RESULTS, stack.any { it is ResultsScreen })
        outState.putBoolean(STATE_SCANNING, stack.any { it is ScanScreen })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        ScanState.uiVisible = true
    }

    override fun onStop() {
        super.onStop()
        ScanState.uiVisible = false
    }

    override fun onResume() {
        super.onResume()
        if (restorePending) {
            restorePending = false
            restore()
        }
        top?.onResumed()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        top?.onConfigurationChanged()
    }

    override fun onDestroy() {
        stack.forEach { it.onDestroy() }
        stack.clear()
        super.onDestroy()
    }

    // -- screens ---------------------------------------------------------------------------------

    val top: Screen? get() = stack.lastOrNull()

    fun push(s: Screen) {
        top?.onHidden()
        stack.add(s)
        show(s)
    }

    /** Replaces the top screen (a finished scan becomes its results). */
    fun replace(s: Screen) {
        stack.removeLastOrNull()?.let {
            it.onHidden()
            it.onDestroy()
        }
        stack.add(s)
        show(s)
    }

    fun pop() {
        if (stack.size <= 1) {
            finish()
            return
        }
        val s = stack.removeAt(stack.size - 1)
        s.onHidden()
        s.onDestroy()
        show(stack.last())
    }

    /** Back to the first screen, closing everything above it. */
    fun popToRoot() {
        while (stack.size > 1) {
            val s = stack.removeAt(stack.size - 1)
            s.onHidden()
            s.onDestroy()
        }
        show(stack[0])
    }

    private fun show(s: Screen) {
        root.removeAllViews()
        (s.view.parent as? ViewGroup)?.removeView(s.view)
        root.addView(s.view, FrameLayout.LayoutParams(MATCH, MATCH))
        root.setBackgroundColor(s.background)
        window.insetsController?.setSystemBarsAppearance(
            if (s.lightBars) LIGHT_BARS else 0, LIGHT_BARS,
        )
        s.onShown()
        updateBack()
    }

    /**
     * Back is the app's own only while there is a screen to go back to (or a screen wants it);
     * otherwise the system's predictive Back animation takes the app home.
     */
    fun updateBack() {
        val want = stack.size > 1 || top?.wantsBack == true
        if (want && !backRegistered) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback)
            backRegistered = true
        } else if (!want && backRegistered) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback)
            backRegistered = false
        }
    }

    fun goBack() {
        if (top?.onBack() == true) return
        pop()
    }

    // -- permissions -----------------------------------------------------------------------------

    fun access(): Access = when {
        granted(Manifest.permission.READ_MEDIA_IMAGES) -> Access.FULL
        granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Access.PARTIAL
        else -> Access.NONE
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** Asks for photos and videos; [done] runs once the person has answered. */
    fun requestMedia(done: () -> Unit) {
        permissionDone = done
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("askedMedia", true).apply()
        requestPermissions(
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
            REQ_MEDIA,
        )
    }

    /** Android no longer shows the question after two refusals: then only Settings can grant it. */
    fun mediaBlocked(): Boolean =
        access() == Access.NONE &&
            getSharedPreferences("settings", MODE_PRIVATE).getBoolean("askedMedia", false) &&
            !shouldShowRequestPermissionRationale(Manifest.permission.READ_MEDIA_IMAGES)

    fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } catch (_: Exception) {
            toast("Open Settings, then Apps, then Photo Cleaner, to allow access")
        }
    }

    /** Notifications show a scan's progress while the app is closed. Asked once, at the first scan. */
    fun askNotificationsOnce() {
        if (askedNotifications) return
        askedNotifications = true
        if (!granted(Manifest.permission.POST_NOTIFICATIONS)) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MEDIA) {
            val d = permissionDone
            permissionDone = null
            d?.invoke()
        }
    }

    // -- trash -----------------------------------------------------------------------------------

    /**
     * Asks Android to move files to its trash (or back out of it, with [trash] false). The system
     * shows its own confirmation; [done] gets whether the person allowed it. Trashed files can be
     * restored for 30 days, here with Undo or in the Files and Photos apps.
     */
    fun requestTrash(uris: List<Uri>, trash: Boolean, done: (Boolean) -> Unit) {
        val pi = try {
            MediaStore.createTrashRequest(contentResolver, uris, trash)
        } catch (e: Exception) {
            toast("Android refused: ${e.message}")
            done(false)
            return
        }
        trashDone = done
        val options = ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE)
            .toBundle()
        try {
            startIntentSenderForResult(pi.intentSender, REQ_TRASH, null, 0, 0, 0, options)
        } catch (e: IntentSender.SendIntentException) {
            trashDone = null
            toast("Couldn't ask Android: ${e.message}")
            done(false)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_TRASH) {
            val d = trashDone
            trashDone = null
            d?.invoke(resultCode == RESULT_OK)
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    /** Opens a file in the phone's own viewer (Photos, Gallery, a video player). */
    fun openExternally(uri: Uri, mime: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {
            toast("No app on this phone can open it")
        }
    }

    // -- test hooks ------------------------------------------------------------------------------

    /**
     * Extras the on-device test uses (harmless for anyone else: scanning only reads):
     *  - `folders`: comma-separated folders to select, such as "DCIM/Camera,Download", or "*" for all;
     *  - `autoscan`: start a scan straight away, logging every group.
     */
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val folders = i.getStringExtra(EXTRA_FOLDERS)
        val autoscan = i.getBooleanExtra(EXTRA_AUTOSCAN, false)
        if (folders == null && !autoscan) return
        i.removeExtra(EXTRA_FOLDERS)
        i.removeExtra(EXTRA_AUTOSCAN)
        if (ScanState.running) return
        popToRoot()
        (stack[0] as FoldersScreen).testRequest(folders, autoscan)
    }

    companion object {
        private const val REQ_MEDIA = 1
        private const val REQ_NOTIFY = 2
        private const val REQ_TRASH = 3
        private const val STATE_RESULTS = "results"
        private const val STATE_SCANNING = "scanning"
        private const val EXTRA_FOLDERS = "folders"
        private const val EXTRA_AUTOSCAN = "autoscan"
        private const val LIGHT_BARS =
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
    }
}
