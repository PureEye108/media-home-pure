package app.mediahome.tablet

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import app.mediahome.tablet.remote.MediaBridge
import app.mediahome.tablet.remote.MediaListener
import app.mediahome.tablet.remote.Remote
import app.mediahome.tablet.remote.YouTubeSearch

/**
 * The set-up checklist. Shown once when Media Home Pure first opens, and any time from Settings > Set up.
 * Every step is optional and only opens Android's own page for that permission or setting; the person decides.
 * (On the original tablet a PC script did these steps; this screen lets anyone do them on the device itself.)
 */
class SetupActivity : Activity() {

    override fun attachBaseContext(base: Context) { super.attachBaseContext(Lang.wrap(base)); Fit.apply(this, base) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.of(this).edit().putBoolean(Prefs.SETUP_SHOWN, true).apply()
        render()
    }

    /** Coming back from Android's settings: show the new state of every step. */
    override fun onResume() {
        super.onResume()
        if (rendered) render()
        rendered = true
    }

    private var rendered = false

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) Prefs.of(this).edit().remove(Prefs.WEATHER_CACHE).apply()
        render()
    }

    @Deprecated("Activity result for the home-screen role request")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        render()
    }

    private fun render() {
        val page = Page(this, getString(R.string.setup_title))
        page.paragraph(getString(R.string.setup_intro))
        val bridge = MediaBridge(applicationContext)
        val restricted = if (Build.VERSION.SDK_INT >= 33) getString(R.string.setup_restricted) else null
        var n = 0

        page.section(R.string.section_language)
        val chosenLang = Lang.chosen(this)
        page.row(
            getString(R.string.lang_title),
            if (chosenLang.isEmpty()) getString(R.string.lang_system, Lang.name(Lang.supported(Lang.systemLocale(this))))
            else Lang.name(Lang.supported(java.util.Locale.forLanguageTag(chosenLang))),
        ) { Dialogs.askLanguage(this) }

        page.section(R.string.setup_section_basics)
        page.step(++n, getString(R.string.setup_home_title), getString(R.string.setup_home_why), Checks.isHome(this)) { askHome() }
        if (Build.VERSION.SDK_INT >= 33) {
            page.step(++n, getString(R.string.setup_notif_title), getString(R.string.setup_notif_why), Checks.canNotify(this)) {
                if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) || !Prefs.of(this).getBoolean(ASKED_NOTIFY, false)) {
                    Prefs.of(this).edit().putBoolean(ASKED_NOTIFY, true).apply()
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
                } else {
                    Ui.tryStart(this, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                }
            }
        }
        page.step(++n, getString(R.string.setup_files_title), getString(R.string.setup_files_why), bridge.hasFileAccess()) {
            if (!Ui.tryStartQuiet(this, Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))))
                Ui.tryStart(this, Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
        page.step(++n, getString(R.string.setup_playing_title), joinLines(getString(R.string.setup_playing_why), restricted),
            bridge.hasNotificationAccess()) {
            val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(this, MediaListener::class.java).flattenToString())
            if (!Ui.tryStartQuiet(this, detail)) Ui.tryStart(this, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        val locOk = Locator.hasPermission(this)
        page.step(++n, getString(R.string.setup_location_title), getString(R.string.setup_location_why),
            locOk && Locator.locationOn(this),
            if (locOk && !Locator.locationOn(this)) getString(R.string.setup_location_off) else null) { askLocation() }

        page.section(R.string.section_remote)
        page.toggle(R.string.remote_toggle, R.string.setup_remote_why, Prefs.REMOTE) {
            Remote.syncService(this)
            render()
        }
        page.row(getString(R.string.pin_title), getString(R.string.pin_now, Remote.pin(this))) { Dialogs.askPin(this) { render() } }
        page.step(++n, getString(R.string.remote_launch_title), getString(R.string.setup_overlay_why), bridge.canLaunchApps()) {
            Ui.tryStart(this, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        page.step(++n, getString(R.string.remote_input_title), joinLines(getString(R.string.setup_input_why), restricted),
            bridge.hasInputService()) { Ui.tryStart(this, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        val ytKey = bridge.youTubeKey()
        page.step(++n, getString(R.string.setup_youtube_title), getString(R.string.setup_youtube_why), ytKey != null,
            if (ytKey == null) getString(R.string.setup_optional) else null) { Dialogs.askYouTubeKey(this) { render() } }

        page.section(R.string.section_saver)
        val saver = Saver.selected(this)
        page.step(++n, getString(R.string.setup_saver_title), getString(R.string.setup_saver_why), saver == true,
            if (saver == null) getString(R.string.setup_unknown) else null) { Ui.tryStart(this, Intent(Settings.ACTION_DREAM_SETTINGS)) }
        page.step(++n, getString(R.string.setup_timeout_title), getString(R.string.setup_timeout_why), Saver.canSetStartAfter(this)) {
            Ui.tryStart(this, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
        }
        if (Saver.stayAwakeWhileCharging(this)) {
            page.step(++n, getString(R.string.setup_stay_awake_title), getString(R.string.saver_stay_awake), false) {
                Ui.tryStart(this, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            }
        }

        page.section(R.string.setup_section_help)
        if (restricted != null) {
            page.row(getString(R.string.setup_app_info), getString(R.string.setup_app_info_sub)) {
                Ui.tryStart(this, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
        page.row(getString(R.string.setup_manual), getString(R.string.setup_manual_sub)) {
            Ui.tryStart(this, Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.MANUAL_URL)))
        }
        page.row(getString(R.string.setup_finish), getString(R.string.setup_finish_sub)) { finish() }
        setContentView(page.root)
    }

    private fun joinLines(a: String, b: String?) = if (b == null) a else "$a\n$b"

    /** Android 10+ asks "Set as default home app?" itself; otherwise its Home settings page opens. */
    private fun askHome() {
        val rm = getSystemService(RoleManager::class.java)
        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_HOME)
                return
            } catch (e: RuntimeException) { /* fall through */ }
        }
        Ui.tryStart(this, Intent(Settings.ACTION_HOME_SETTINGS))
    }

    private fun askLocation() {
        when {
            !Locator.hasPermission(this) ->
                if (shouldShowRequestPermissionRationale(Locator.PERMISSION) || !Prefs.of(this).getBoolean(Prefs.WEATHER_LOC_ASKED, false)) {
                    Prefs.of(this).edit().putBoolean(Prefs.WEATHER_LOC_ASKED, true).putBoolean(Prefs.WEATHER_USE_LOCATION, true).apply()
                    requestPermissions(arrayOf(Locator.PERMISSION), REQ_LOCATION)
                } else {
                    Ui.tryStart(this, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
            !Locator.locationOn(this) -> Ui.tryStart(this, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            else -> Unit
        }
    }

    private companion object {
        const val REQ_HOME = 51
        const val REQ_NOTIFY = 52
        const val REQ_LOCATION = 53
        const val ASKED_NOTIFY = "setup_asked_notify"
    }
}

/** Small yes/no checks used by the set-up checklist. */
object Checks {
    fun isHome(ctx: Context): Boolean {
        val rm = ctx.getSystemService(RoleManager::class.java)
        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) return rm.isRoleHeld(RoleManager.ROLE_HOME)
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        @Suppress("DEPRECATION")
        return ctx.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == ctx.packageName
    }

    fun canNotify(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

/** Dialogs shared by Settings and the set-up checklist. */
object Dialogs {
    /** The device's language, or one of the app's languages (each shown in its own words). */
    fun askLanguage(activity: Activity) {
        val tags = listOf("") + Lang.OPTIONS.map { it.first }
        val names = listOf(activity.getString(R.string.lang_system, Lang.name(Lang.supported(Lang.systemLocale(activity))))) + Lang.OPTIONS.map { it.second }
        val now = tags.indexOf(Lang.chosen(activity).let { c -> if (c.isEmpty()) "" else Lang.supported(java.util.Locale.forLanguageTag(c)) }).coerceAtLeast(0)
        Ui.dialog(activity)
            .setTitle(R.string.lang_title)
            .setSingleChoiceItems(names.toTypedArray(), now) { d, which ->
                d.dismiss()
                if (which != now) Lang.choose(activity, tags[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Change the web remote's PIN (4 to 8 digits). */
    fun askPin(activity: Activity, done: () -> Unit) {
        val input = EditText(activity).apply {
            hint = activity.getString(R.string.pin_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(8))
            setText(Remote.pin(activity))
            setSingleLine()
        }
        Ui.dialog(activity)
            .setTitle(R.string.pin_title)
            .setMessage(R.string.pin_message)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val pin = input.text.toString().trim()
                if (pin == Remote.pin(activity)) return@setPositiveButton
                if (Remote.setPin(activity, pin)) {
                    Toast.makeText(activity, R.string.pin_changed, Toast.LENGTH_LONG).show(); done()
                } else {
                    Toast.makeText(activity, R.string.pin_bad, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Paste (or remove) the user's own YouTube Data API key. Shows how to restrict the key to this app. */
    fun askYouTubeKey(activity: Activity, done: () -> Unit) {
        YouTubeSearch.identify(activity)
        val input = EditText(activity).apply {
            hint = activity.getString(R.string.yt_key_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setSingleLine()
        }
        val cert = YouTubeSearch.androidCert?.let { YouTubeSearch.colonHex(it) } ?: "?"
        Ui.dialog(activity)
            .setTitle(R.string.yt_key_title)
            .setMessage(activity.getString(R.string.yt_key_restrict, activity.packageName, cert))
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val key = input.text.toString().trim()
                if (!YouTubeSearch.isValidKey(key)) {
                    Toast.makeText(activity, R.string.yt_key_bad, Toast.LENGTH_LONG).show()
                } else {
                    MediaBridge(activity.applicationContext).setYouTubeKey(key); done()
                }
            }
            .apply {
                if (Prefs.of(activity).getString(Prefs.YT_KEY, null).isNullOrBlank()) {
                    setNeutralButton(R.string.yt_key_howto) { _, _ ->
                        Ui.tryStart(activity, Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.YOUTUBE_KEY_URL)))
                    }
                } else {
                    setNeutralButton(R.string.yt_key_remove) { _, _ -> MediaBridge(activity.applicationContext).setYouTubeKey(null); done() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
