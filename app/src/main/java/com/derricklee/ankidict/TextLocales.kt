package com.derricklee.ankidict

import android.widget.TextView

/**
 * Attaches the text's language to TextViews so Android's floating selection toolbar offers the
 * system Translate action (user report 2026-11-13: "the translate button in the android context
 * menu is missing"). From API 33 the suggestion is gated on [TextView.setTextLocales] — an
 * unannotated view gets no Translate option. The app's ACTION_PROCESS_TEXT entry (registers
 * under the app name for dictionary lookup) is a separate action and already works. minSdk is
 * 33, so no version guards are needed.
 */
object TextLocales {
    fun localeFor(tag: LanguageTag): String? = when (tag) {
        LanguageTag.JAPANESE -> "ja-JP"
        LanguageTag.CHINESE -> "zh-CN"
        else -> null
    }

    fun apply(view: TextView, tag: LanguageTag) {
        val locale = localeFor(tag) ?: return
        view.setTextLocales(android.os.LocaleList(java.util.Locale.forLanguageTag(locale)))
    }
}
