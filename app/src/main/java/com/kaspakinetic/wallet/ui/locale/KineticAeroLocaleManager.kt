package com.kaspakinetic.wallet.ui.locale

import android.content.res.Resources
import android.text.TextUtils
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.os.ConfigurationCompat
import java.util.Locale

@Immutable
data class LanguageOption(
    val tag: String,
    val nativeName: String,
    val englishName: String,
    val helloGreeting: String,
)

/**
 * Exposes the active resolved [Locale] across the Kinetic Aero Compose hierarchy.
 */
val LocalActiveLocale = compositionLocalOf<Locale> { Locale.getDefault() }

/**
 * Discovers all Android-supported locales for the mandatory Kinetic Aero All-Languages selector,
 * resolves native greetings for the Welcome screen's typewriter animation, and normalizes OEM
 * locale variants safely.
 */
object KineticAeroLocaleManager {

    private val priorityTags = listOf(
        "en", "es", "zh-CN", "zh-TW", "ja", "ko", "fr", "de", "pt", "it",
        "ru", "ar", "hi", "id", "tr", "vi", "th", "pl", "nl", "uk",
        "sv", "da", "fi", "nb", "cs", "ro", "hu", "el", "he", "fa",
        "ur", "bn", "ms", "fil", "ta", "sw", "ca", "hr", "sr", "sk", "bg",
    )

    private val greetingsByLang = mapOf(
        "en" to "hello",
        "es" to "hola",
        "fr" to "bonjour",
        "de" to "hallo",
        "it" to "ciao",
        "pt" to "olá",
        "nl" to "hallo",
        "pl" to "cześć",
        "ru" to "привет",
        "uk" to "привіт",
        "cs" to "ahoj",
        "ro" to "salut",
        "hu" to "szia",
        "el" to "γεια",
        "sv" to "hej",
        "da" to "hej",
        "fi" to "hei",
        "nb" to "hei",
        "no" to "hei",
        "nn" to "hei",
        "tr" to "merhaba",
        "ar" to "مرحبا",
        "he" to "שלום",
        "iw" to "שלום",
        "fa" to "سلام",
        "ur" to "سلام",
        "hi" to "नमस्ते",
        "bn" to "হ্যালো",
        "ta" to "வணக்கம்",
        "te" to "నమస్తే",
        "mr" to "नमस्कार",
        "gu" to "નમસ્તે",
        "kn" to "ನಮಸ್ಕಾರ",
        "ml" to "നമಸ್കാരം",
        "pa" to "ਸਤ ਸ੍ਰੀ ਅਕਾਲ",
        "id" to "halo",
        "in" to "halo",
        "ms" to "hai",
        "vi" to "xin chào",
        "th" to "สวัสดี",
        "fil" to "kamusta",
        "tl" to "kamusta",
        "zh" to "你好",
        "zh-cn" to "你好",
        "zh-tw" to "你好",
        "ja" to "こんにちは",
        "ko" to "안녕하세요",
        "sw" to "habari",
        "ca" to "hola",
        "hr" to "bok",
        "sr" to "здраво",
        "sk" to "ahoj",
        "bg" to "здравей",
        "lt" to "labas",
        "lv" to "sveiki",
        "et" to "tere",
        "sl" to "živjo",
        "af" to "hallo",
        "am" to "ሰላም",
        "az" to "salam",
        "eu" to "kaixo",
        "gl" to "ola",
        "is" to "halló",
        "ka" to "გამარჯობა",
        "kk" to "сәлем",
        "km" to "សួស្តី",
        "lo" to "ສະບາຍດີ",
        "mk" to "здраво",
        "mn" to "сайн уу",
        "my" to "မင်္ဂလာပါ",
        "ne" to "नमस्ते",
        "si" to "ආයුබෝවන්",
        "sq" to "përshëndetje",
        "uz" to "salom",
        "zu" to "sawubona",
    )

    private val cachedLanguages: List<LanguageOption> by lazy { buildAllLanguages() }

    /**
     * Returns all distinct languages available on the Android device (curated priority languages
     * first, followed alphabetically by every additional system-supported language).
     */
    fun getAllLanguages(): List<LanguageOption> = cachedLanguages

    /**
     * Reads the true OS-level primary locale directly from `Resources.getSystem().configuration`
     * so calling [Locale.setDefault] for in-app overrides never masks the device's system language.
     */
    fun systemDefaultLocale(): Locale {
        val sysLocales = ConfigurationCompat.getLocales(Resources.getSystem().configuration)
        return sysLocales[0] ?: Locale.getDefault()
    }

    fun resolveLocale(languageTag: String): Locale {
        if (languageTag.isBlank()) return systemDefaultLocale()
        val parsed = Locale.forLanguageTag(languageTag.trim())
        return if (parsed.language.isNullOrBlank()) systemDefaultLocale() else parsed
    }

    /**
     * Returns `"hello"` when [languageTag] is blank (System default on initial Welcome launch),
     * or the native greeting for the chosen language tag.
     */
    fun greetingForTag(languageTag: String): String {
        if (languageTag.isBlank()) return "hello"
        val lower = languageTag.lowercase(Locale.ROOT)
        greetingsByLang[lower]?.let { return it }
        val baseLang = Locale.forLanguageTag(languageTag).language.lowercase(Locale.ROOT)
        return greetingsByLang[baseLang] ?: "hello"
    }

    fun displaySummaryForSelection(languageTag: String, systemDefaultLabel: String): String {
        if (languageTag.isBlank()) {
            val sys = systemDefaultLocale()
            val native = sys.getDisplayName(sys).replaceFirstChar { it.titlecase(sys) }
            return "$systemDefaultLabel ($native)"
        }
        val match = cachedLanguages.firstOrNull { it.tag.equals(languageTag, ignoreCase = true) }
        if (match != null) return "${match.nativeName} (${match.englishName})"
        val loc = resolveLocale(languageTag)
        return loc.getDisplayName(loc).replaceFirstChar { it.titlecase(loc) }
    }

    private fun buildAllLanguages(): List<LanguageOption> {
        val byCanonicalKey = linkedMapOf<String, LanguageOption>()

        fun toOption(loc: Locale, explicitTag: String? = null): Pair<String, LanguageOption>? {
            val lang = loc.language?.lowercase(Locale.ROOT).orEmpty()
            if (lang.isBlank() || lang == "und" || lang.length > 3) return null
            val normalizedLang = when (lang) {
                "iw" -> "he"
                "in" -> "id"
                "ji" -> "yi"
                else -> lang
            }

            val tag = explicitTag ?: when {
                normalizedLang == "zh" && (
                    loc.country.equals("TW", true) ||
                        loc.country.equals("HK", true) ||
                        loc.script.equals("Hant", true)
                    ) -> "zh-TW"
                normalizedLang == "zh" -> "zh-CN"
                loc.script.isNotBlank() &&
                    normalizedLang !in setOf("en", "es", "fr", "de", "pt", "it", "ru", "ja", "ko", "ar") ->
                    "$normalizedLang-${loc.script}"
                else -> normalizedLang
            }

            val key = tag.lowercase(Locale.ROOT)
            val displayLoc = Locale.forLanguageTag(tag)
            val rawNative = when (key) {
                "zh-cn" -> "简体中文"
                "zh-tw" -> "繁體中文"
                else -> displayLoc.getDisplayName(displayLoc)
            }.trim()
            val rawEnglish = when (key) {
                "zh-cn" -> "Chinese (Simplified)"
                "zh-tw" -> "Chinese (Traditional)"
                else -> displayLoc.getDisplayName(Locale.ENGLISH)
            }.trim()

            if (rawNative.isBlank() || rawEnglish.isBlank() || rawNative.equals(tag, ignoreCase = true)) {
                return null
            }

            val nativeCap = rawNative.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(displayLoc) else it.toString()
            }
            val englishCap = rawEnglish.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.ENGLISH) else it.toString()
            }
            val hello = greetingsByLang[key] ?: greetingsByLang[normalizedLang] ?: "hello"

            return key to LanguageOption(
                tag = tag,
                nativeName = nativeCap,
                englishName = englishCap,
                helloGreeting = hello,
            )
        }

        // 1. Register curated priority tags first
        priorityTags.forEach { tag ->
            toOption(Locale.forLanguageTag(tag), explicitTag = tag)?.let { (key, opt) ->
                if (!byCanonicalKey.containsKey(key)) {
                    byCanonicalKey[key] = opt
                }
            }
        }

        // 2. Discover every additional language shipped on the Android device
        val extrasByKey = linkedMapOf<String, LanguageOption>()
        Locale.getAvailableLocales().forEach { loc ->
            toOption(loc)?.let { (key, opt) ->
                if (!byCanonicalKey.containsKey(key) && !extrasByKey.containsKey(key)) {
                    extrasByKey[key] = opt
                }
            }
        }

        val sortedExtras = extrasByKey.values.sortedBy { it.englishName.lowercase(Locale.ENGLISH) }
        // Case-insensitive deduplication prevents LazyColumn duplicate-key crashes on OEM ROMs
        return (byCanonicalKey.values.toList() + sortedExtras)
            .distinctBy { it.tag.lowercase(Locale.ROOT) }
    }
}

/**
 * Provides reactive [LocalKineticStrings], [LocalActiveLocale], and [LocalLayoutDirection] (`Ltr`/`Rtl`).
 *
 * CRITICAL ANDROID / HILT RULE:
 * Do NOT override `LocalContext` with `baseContext.createConfigurationContext(config)` here!
 * `createConfigurationContext` returns a bare `android.app.ContextImpl` that strips the host
 * `ComponentActivity` / `@AndroidEntryPoint` wrapper, causing `hiltViewModel()` (`Contexts.getActivity`)
 * and `rememberLauncherForActivityResult` to crash immediately on Frame 1!
 */
@Composable
fun ProvideKineticAeroLocale(
    languageTag: String,
    content: @Composable () -> Unit,
) {
    val baseConfig = LocalConfiguration.current

    val resolvedLocale = remember(languageTag, baseConfig) {
        if (languageTag.isBlank()) {
            ConfigurationCompat.getLocales(Resources.getSystem().configuration)[0]
                ?: Locale.getDefault()
        } else {
            KineticAeroLocaleManager.resolveLocale(languageTag)
        }
    }

    val layoutDirection = remember(resolvedLocale) {
        if (TextUtils.getLayoutDirectionFromLocale(resolvedLocale) == View.LAYOUT_DIRECTION_RTL) {
            LayoutDirection.Rtl
        } else {
            LayoutDirection.Ltr
        }
    }

    val strings = remember(resolvedLocale) {
        resolveKineticAeroStrings(resolvedLocale)
    }

    SideEffect {
        Locale.setDefault(resolvedLocale)
    }

    CompositionLocalProvider(
        LocalLayoutDirection provides layoutDirection,
        LocalActiveLocale provides resolvedLocale,
        LocalKineticStrings provides strings,
        content = content,
    )
}
