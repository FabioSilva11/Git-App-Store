package com.saas.payment.gitappstore

import android.content.Context
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat

object ThemeManager {
    data class ThemeOption(
        val key: String,
        @param:StringRes val labelRes: Int,
        @param:StyleRes val themeRes: Int,
        val nightMode: Int,
    )

    private const val PREFS = "git_app_store_prefs"
    private const val PREF_THEME_KEY = "theme_key"
    private const val LEGACY_PREF_THEME_MODE = "theme_mode"

    val options =
        listOf(
            ThemeOption("system", R.string.theme_system, R.style.Theme_GitAppStore_System, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
            ThemeOption("light", R.string.theme_light, R.style.Theme_GitAppStore_Light, AppCompatDelegate.MODE_NIGHT_NO),
            ThemeOption("dark", R.string.theme_dark, R.style.Theme_GitAppStore_Dark, AppCompatDelegate.MODE_NIGHT_YES),
            ThemeOption("amoled_black", R.string.theme_amoled_black, R.style.Theme_GitAppStore_Amoled_Black, AppCompatDelegate.MODE_NIGHT_YES),
            ThemeOption("amoled_blue", R.string.theme_amoled_blue, R.style.Theme_GitAppStore_Amoled_Blue, AppCompatDelegate.MODE_NIGHT_YES),
            ThemeOption("amoled_pink", R.string.theme_amoled_pink, R.style.Theme_GitAppStore_Amoled_Pink, AppCompatDelegate.MODE_NIGHT_YES),
            ThemeOption("amoled_emerald", R.string.theme_amoled_emerald, R.style.Theme_GitAppStore_Amoled_Emerald, AppCompatDelegate.MODE_NIGHT_YES),
            ThemeOption("amoled_violet", R.string.theme_amoled_violet, R.style.Theme_GitAppStore_Amoled_Violet, AppCompatDelegate.MODE_NIGHT_YES),
            ThemeOption("amoled_orange", R.string.theme_amoled_orange, R.style.Theme_GitAppStore_Amoled_Orange, AppCompatDelegate.MODE_NIGHT_YES),
            ThemeOption("amoled_gold", R.string.theme_amoled_gold, R.style.Theme_GitAppStore_Amoled_Gold, AppCompatDelegate.MODE_NIGHT_YES),
            ThemeOption("amoled_ruby", R.string.theme_amoled_ruby, R.style.Theme_GitAppStore_Amoled_Ruby, AppCompatDelegate.MODE_NIGHT_YES),
            ThemeOption("amoled_cyan", R.string.theme_amoled_cyan, R.style.Theme_GitAppStore_Amoled_Cyan, AppCompatDelegate.MODE_NIGHT_YES),
            ThemeOption("amoled_graphite", R.string.theme_amoled_graphite, R.style.Theme_GitAppStore_Amoled_Graphite, AppCompatDelegate.MODE_NIGHT_YES),
        )

    fun applyTheme(activity: AppCompatActivity) {
        val option = selectedOption(activity)
        AppCompatDelegate.setDefaultNightMode(option.nightMode)
        activity.setTheme(option.themeRes)
    }

    fun selectedOption(context: Context): ThemeOption {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedKey = prefs.getString(PREF_THEME_KEY, null)
        if (!savedKey.isNullOrBlank()) {
            options.firstOrNull { it.key == savedKey }?.let { return it }
        }

        return when (prefs.getInt(LEGACY_PREF_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)) {
            AppCompatDelegate.MODE_NIGHT_NO -> options.first { it.key == "light" }
            AppCompatDelegate.MODE_NIGHT_YES -> options.first { it.key == "dark" }
            else -> options.first { it.key == "system" }
        }
    }

    fun saveSelection(
        context: Context,
        option: ThemeOption,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_THEME_KEY, option.key)
            .putInt(LEGACY_PREF_THEME_MODE, option.nightMode)
            .apply()
    }

    fun resolveColor(
        context: Context,
        @AttrRes attrRes: Int,
    ): Int {
        val themedContext = ContextThemeWrapper(context, selectedOption(context).themeRes)
        val typedValue = TypedValue()
        check(themedContext.theme.resolveAttribute(attrRes, typedValue, true)) {
            "Atributo de tema nao resolvido: $attrRes"
        }
        return if (typedValue.resourceId != 0) {
            ContextCompat.getColor(themedContext, typedValue.resourceId)
        } else {
            typedValue.data
        }
    }
}
