package app.mediahome.tablet

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import android.text.format.DateFormat
import java.util.Locale

/**
 * Media Home's language (1.10): the tablet's own language by default, or one chosen in Settings > Language.
 * Android 13+ keeps the choice per app (it also shows in Android's Settings > Apps > Media Home > Language);
 * on older Android the choice is applied to each screen as it opens. The phone remote follows the same language.
 */
object Lang {
    /** Language tag -> its own name. "" (not listed) = the tablet's language. Resources: values-<lang>, Portuguese is Brazilian. */
    val OPTIONS: List<Pair<String, String>> = listOf(
        "en" to "English",
        "vi" to "Tiếng Việt",
        "es" to "Español",
        "zh-CN" to "中文（简体）",
        "hi" to "हिन्दी",
        "fr" to "Français",
        "de" to "Deutsch",
        "ja" to "日本語",
        "ko" to "한국어",
        "pt-BR" to "Português (Brasil)",
        "ru" to "Русский",
    )

    /** The language chosen in Media Home, or "" for the tablet's own language. */
    fun chosen(ctx: Context): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val l = ctx.getSystemService(LocaleManager::class.java)?.applicationLocales
            return if (l == null || l.isEmpty) "" else l[0].toLanguageTag()
        }
        return Prefs.of(ctx).getString(Prefs.LANGUAGE, "") ?: ""
    }

    /** Sets the language ("" = the tablet's own). Android recreates the open screens; [activity] is redrawn on older Android. */
    fun choose(activity: Activity, tag: String) {
        Prefs.of(activity).edit().putString(Prefs.LANGUAGE, tag).remove(Prefs.WEATHER_CACHE).apply()   // place names follow the language
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            activity.recreate()
        }
    }

    /** The tablet's own language (Android's system setting). */
    fun systemLocale(ctx: Context): Locale {
        if (Build.VERSION.SDK_INT >= 33) ctx.getSystemService(LocaleManager::class.java)?.systemLocales?.let { if (!it.isEmpty) return it[0] }
        return Resources.getSystem().configuration.locales[0]
    }

    /** The supported language closest to [locale] ("en" if none): Chinese = Simplified, Portuguese = Brazilian. */
    fun supported(locale: Locale): String {
        val lang = locale.language
        return when {
            lang == "zh" -> if (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")) "en" else "zh-CN"
            lang == "pt" -> "pt-BR"
            lang == "in" || lang == "iw" -> "en"
            OPTIONS.any { it.first == lang } -> lang
            else -> "en"
        }
    }

    /** The language Media Home is shown in right now (one of [OPTIONS]). */
    fun current(ctx: Context): String {
        val c = chosen(ctx)
        return if (c.isEmpty()) supported(systemLocale(ctx)) else supported(Locale.forLanguageTag(c))
    }

    /** The name of [tag] in its own language. */
    fun name(tag: String): String = OPTIONS.firstOrNull { it.first == tag }?.second ?: tag

    /** Android 11-12 only: each screen opens in the chosen language (Android 13+ does this by itself). */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = Prefs.of(base).getString(Prefs.LANGUAGE, "") ?: ""
        if (tag.isEmpty()) return base
        val loc = Locale.forLanguageTag(tag)
        Locale.setDefault(loc)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocales(LocaleList(loc))
        return base.createConfigurationContext(cfg)
    }

    /** "Thursday 1 January" in the order and words of the current language. */
    fun datePattern(ctx: Context): String = DateFormat.getBestDateTimePattern(ctx.resources.configuration.locales[0], "EEEEdMMMM")

    /** The phone remote's words for [tag] (assets/i18n/remote-<lang>.json), or null for English. */
    fun remoteAsset(tag: String): String? = when (tag) {
        "en" -> null
        "zh-CN" -> "i18n/remote-zh-CN.json"
        "pt-BR" -> "i18n/remote-pt.json"
        else -> "i18n/remote-$tag.json"
    }

    /** The remote page with the current language filled in (tags in <html lang> and the words in #i18n). */
    fun remotePage(ctx: Context, html: ByteArray): ByteArray {
        val tag = current(ctx)
        val words = remoteAsset(tag)?.let { name ->
            try { ctx.assets.open(name).use { it.readBytes().toString(Charsets.UTF_8) } } catch (e: Exception) { null }
        } ?: return html
        return injectRemote(html.toString(Charsets.UTF_8), tag, words).toByteArray(Charsets.UTF_8)
    }

    /** Pure part of [remotePage] (unit-tested): "</" is escaped so the words can't end the script element. */
    fun injectRemote(page: String, tag: String, wordsJson: String): String =
        page.replaceFirst("<html lang=\"en\">", "<html lang=\"$tag\">")
            .replaceFirst("<script type=\"application/json\" id=\"i18n\">{}</script>",
                "<script type=\"application/json\" id=\"i18n\">" + wordsJson.trim().replace("</", "<\\/") + "</script>")
}
