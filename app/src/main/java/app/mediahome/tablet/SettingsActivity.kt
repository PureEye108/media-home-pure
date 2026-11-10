package app.mediahome.tablet

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import app.mediahome.tablet.remote.MediaBridge
import app.mediahome.tablet.remote.Remote

/** Settings page: tile apps, web remote, weather, screen options, shortcuts into Android settings, and the way out. */
class SettingsActivity : Activity() {

    override fun attachBaseContext(base: android.content.Context) { super.attachBaseContext(Lang.wrap(base)); Fit.apply(this, base) }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            Prefs.of(this).edit().remove(Prefs.WEATHER_CACHE).apply()   // fetch again for the new place on the home screen
            recreate()
        }
    }

    private companion object { const val REQ_LOCATION = 42 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = Page(this, getString(R.string.tile_settings))

        // ---- Language (1.10): first, so it is easy to find in any language ----
        page.section(R.string.section_language)
        val chosenLang = Lang.chosen(this)
        page.row(
            getString(R.string.lang_title),
            if (chosenLang.isEmpty()) getString(R.string.lang_system, Lang.name(Lang.supported(Lang.systemLocale(this))))
            else Lang.name(Lang.supported(java.util.Locale.forLanguageTag(chosenLang))),
        ) { Dialogs.askLanguage(this) }

        // ---- Set-up checklist (permissions and options, step by step) ----
        page.row(R.string.setup_row, R.string.setup_row_sub) { startActivity(Intent(this, SetupActivity::class.java)) }

        page.section(R.string.section_home)
        page.row(R.string.set_tile_apps, R.string.set_tile_apps_sub) { Pickers.chooseTileThenApp(this) {} }
        page.toggle(R.string.set_keep_on, null, Prefs.KEEP_SCREEN_ON)
        page.toggle(R.string.set_immersive, R.string.set_immersive_sub, Prefs.IMMERSIVE)
        page.row(R.string.tile_all_apps, R.string.row_all_apps_sub) { Pickers.allApps(this) }

        // ---- USB DAC (bit-perfect) ----
        page.section(R.string.section_dac)
        val dacs = Dac.attached(this)
        page.info(listOf(
            if (dacs.isEmpty()) getString(R.string.dac_none) else getString(R.string.dac_connected, dacs.joinToString(", ")),
            "",
            getString(R.string.dac_steps),
        ))
        if (Apps.isLaunchable(this, Apps.PLAYER_PURE)) {
            page.row(R.string.dac_open_pp, R.string.dac_open_pp_sub) { Apps.launch(this, Apps.PLAYER_PURE) }
        }
        if (Apps.isLaunchable(this, Apps.HIBY)) {
            page.row(R.string.dac_open_hiby, R.string.dac_open_hiby_sub) { Apps.launch(this, Apps.HIBY) }
        }

        // ---- Web remote ----
        page.section(R.string.section_remote)
        page.toggle(R.string.remote_toggle, R.string.remote_toggle_sub, Prefs.REMOTE) {
            Remote.syncService(this)
            recreate()
        }
        if (Remote.enabled(this)) {
            val lines = mutableListOf(
                getString(R.string.remote_open_this),
                Remote.address(this),
                getString(R.string.remote_pin_line, Remote.pin(this)),
            )
            Remote.lastError?.let { lines.add(getString(R.string.remote_error, it)) }
            page.info(lines)
            page.row(getString(R.string.pin_title), getString(R.string.pin_now, Remote.pin(this))) { Dialogs.askPin(this) { recreate() } }
            page.toggle(R.string.set_remote_qr_home, R.string.set_remote_qr_home_sub, Prefs.REMOTE_QR_HOME, default = true)
            Remote.link(this)?.let { link ->
                val size = Ui.dp(this, 240)
                page.picture(Qr.bitmap(link, size), size, getString(R.string.remote_qr_title),
                    getString(R.string.remote_qr_sub) + "\n\n" + getString(R.string.remote_qr_note))
            }
        }
        val bridge = MediaBridge(applicationContext)
        page.row(
            getString(R.string.remote_access_title),
            getString(if (bridge.hasNotificationAccess()) R.string.perm_granted else R.string.remote_access_needed),
        ) { Ui.tryStart(this, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        page.row(
            getString(R.string.remote_launch_title),
            getString(if (bridge.canLaunchApps()) R.string.perm_granted else R.string.remote_launch_needed),
        ) { Ui.tryStart(this, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        page.row(
            getString(R.string.remote_input_title),
            getString(if (bridge.hasInputService()) R.string.perm_granted else R.string.remote_input_needed),
        ) { Ui.tryStart(this, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        page.row(
            getString(R.string.remote_files_title),
            getString(if (bridge.hasFileAccess()) R.string.perm_granted else R.string.remote_files_needed),
        ) { Ui.tryStart(this, Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
        val ytKey = Prefs.of(this).getString(Prefs.YT_KEY, null)
        val builtIn = app.mediahome.tablet.remote.YouTubeSearch.builtInKey()
        page.row(
            getString(R.string.yt_key_title),
            when {
                !ytKey.isNullOrBlank() -> getString(R.string.yt_key_set, ytKey.takeLast(4))
                builtIn != null -> getString(R.string.yt_key_builtin, builtIn.takeLast(4))
                else -> getString(R.string.yt_key_none)
            },
        ) { Dialogs.askYouTubeKey(this) { recreate() } }

        // ---- Screen saver ----
        page.section(R.string.section_saver)
        val selected = Saver.selected(this)
        val minutes = Saver.startAfterMinutes(this)
        page.info(listOfNotNull(
            when (selected) {
                true -> getString(R.string.saver_on, minutes)
                false -> getString(R.string.saver_off)
                null -> getString(R.string.saver_unknown, minutes)
            },
            getString(R.string.saver_rules),
            if (Saver.stayAwakeWhileCharging(this)) getString(R.string.saver_stay_awake) else null,
            if (Prefs.of(this).getBoolean(Prefs.KEEP_SCREEN_ON, false)) getString(R.string.saver_keep_on) else null,
        ))
        page.row(R.string.saver_preview, R.string.saver_preview_sub) { startActivity(Intent(this, ScreenSaverActivity::class.java)) }
        page.row(getString(R.string.saver_start_after), getString(R.string.saver_start_after_sub, minutes)) { askStartAfter() }
        page.toggle(R.string.saver_show_music, null, Prefs.SAVER_MUSIC, default = true)
        page.toggle(R.string.saver_show_weather, null, Prefs.SAVER_WEATHER, default = true)
        page.row(R.string.saver_android, R.string.saver_android_sub) { open(Settings.ACTION_DREAM_SETTINGS) }

        // ---- Weather ----
        page.section(R.string.section_weather)
        page.toggle(R.string.wx_toggle, null, Prefs.WEATHER_ON, default = true)
        val chosen = Weather.savedPlace(this)
        val fallback = chosen?.name ?: Weather.cityFromTimeZone()
        if (Weather.useLocation(this)) {
            val here = Weather.herePlace(this)
            val status = when {
                !Locator.hasPermission(this) -> getString(R.string.wx_here_need_permission, fallback)
                !Locator.locationOn(this) -> getString(R.string.wx_here_location_off, fallback)
                here == null -> getString(R.string.wx_here_searching, fallback)
                else -> getString(R.string.wx_here_now, here.name)
            }
            page.row(getString(R.string.wx_here_title), status) {
                when {
                    !Locator.hasPermission(this) ->
                        if (shouldShowRequestPermissionRationale(Locator.PERMISSION) || !Prefs.of(this).getBoolean(Prefs.WEATHER_LOC_ASKED, false)) {
                            Prefs.of(this).edit().putBoolean(Prefs.WEATHER_LOC_ASKED, true).apply()
                            requestPermissions(arrayOf(Locator.PERMISSION), REQ_LOCATION)
                        } else {
                            Ui.tryStart(this, Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                        }
                    !Locator.locationOn(this) -> open(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    else -> Unit
                }
            }
            page.row(getString(R.string.wx_use_city), chosen?.let { getString(R.string.wx_use_city_sub_saved, it.name) }
                ?: getString(R.string.wx_use_city_sub)) { askCity() }
        } else {
            page.row(getString(R.string.wx_city), chosen?.name ?: getString(R.string.wx_city_auto, Weather.cityFromTimeZone())) { askCity() }
            page.row(getString(R.string.wx_use_here), getString(R.string.wx_use_here_sub)) {
                Weather.setUseLocation(this, true)
                if (!Locator.hasPermission(this)) requestPermissions(arrayOf(Locator.PERMISSION), REQ_LOCATION)
                recreate()
            }
        }
        page.info(listOf(getString(R.string.wx_attribution), getString(R.string.wx_privacy)))

        page.section(R.string.section_android)
        page.row(R.string.set_sound, R.string.set_sound_sub) { open(Settings.ACTION_SOUND_SETTINGS) }
        page.row(R.string.set_wifi, R.string.set_wifi_sub) { open(Settings.ACTION_WIFI_SETTINGS) }
        page.row(R.string.set_bt, R.string.set_bt_sub) { open(Settings.ACTION_BLUETOOTH_SETTINGS) }
        page.row(R.string.set_display, R.string.set_display_sub) { open(Settings.ACTION_DISPLAY_SETTINGS) }
        page.row(R.string.set_all, R.string.set_all_sub) { open(Settings.ACTION_SETTINGS) }

        page.section(R.string.section_exit)
        page.row(R.string.set_switch_home, R.string.set_switch_home_sub) { open(Settings.ACTION_HOME_SETTINGS) }

        page.section(R.string.section_about)
        page.info(aboutLines())
        page.row(getString(R.string.setup_manual), getString(R.string.setup_manual_sub)) {
            Ui.tryStart(this, Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.MANUAL_URL)))
        }

        setContentView(page.root)
    }

    /** Type a city name, pick from the matches (Open-Meteo geocoding). */
    private fun askCity() {
        val input = EditText(this).apply {
            hint = getString(R.string.wx_city_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
        }
        Ui.dialog(this)
            .setTitle(R.string.wx_city)
            .setView(input)
            .setPositiveButton(R.string.wx_search) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                Thread {
                    val results = try { Weather.search(name) } catch (e: Exception) { null }
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        when {
                            results == null -> Toast.makeText(this, R.string.wx_search_failed, Toast.LENGTH_LONG).show()
                            results.isEmpty() -> Toast.makeText(this, R.string.wx_no_match, Toast.LENGTH_LONG).show()
                            else -> Ui.dialog(this)
                                .setTitle(R.string.wx_pick)
                                .setItems(results.map { if (it.detail.isEmpty()) it.name else "${it.name} - ${it.detail}" }.toTypedArray()) { _, which ->
                                    Weather.savePlace(this, results[which])
                                    recreate()
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                        }
                    }
                }.start()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Android's screen timeout = when the screen saver starts (while plugged in). Needs "Modify system settings". */
    private fun askStartAfter() {
        if (!Saver.canSetStartAfter(this)) {
            Toast.makeText(this, R.string.saver_need_write, Toast.LENGTH_LONG).show()
            Ui.tryStart(this, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
            return
        }
        val opts = Saver.START_AFTER_MIN
        Ui.dialog(this)
            .setTitle(R.string.saver_start_after)
            .setItems(opts.map { resources.getQuantityString(R.plurals.minutes, it, it) }.toTypedArray()) { _, which ->
                if (!Saver.setStartAfter(this, opts[which])) Toast.makeText(this, R.string.saver_need_write, Toast.LENGTH_LONG).show()
                recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun open(action: String) {
        Ui.tryStart(this, Intent(action))
    }

    private fun aboutLines(): List<String> {
        val version = try {
            if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionName
            }
        } catch (e: PackageManager.NameNotFoundException) {
            "?"
        }
        return listOf(
            "Media Home Pure $version (MIT licence)",
            getString(R.string.info_device, "${Build.MANUFACTURER} ${Build.MODEL}"),
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            getString(R.string.info_patch, Build.VERSION.SECURITY_PATCH),
            getString(R.string.info_build, Build.DISPLAY),
        )
    }
}
