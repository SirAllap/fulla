// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

/** The strings of the app, in the language the person chose (or the browser's). */
object I18n {
    val languages = listOf("en" to "English", "es" to "Español", "de" to "Deutsch", "fr" to "Français", "it" to "Italiano", "pt" to "Português")

    var language: String = "en"
        set(value) { field = if (GENERATED_STRINGS.containsKey(value)) value else "en" }

    /** The best match for a browser language tag such as `es-ES`. */
    fun forTag(tag: String?): String = (tag ?: "en").substringBefore('-').substringBefore('_').lowercase().takeIf { GENERATED_STRINGS.containsKey(it) } ?: "en"

    private val PLACEHOLDER = Regex("%(?:(\\d)\\$[sd]|%)")

    fun format(template: String, args: Array<out Any?>): String =
        PLACEHOLDER.replace(template) { m ->
            val n = m.groupValues[1]
            if (n.isEmpty()) "%" else (args.getOrNull(n.toInt() - 1)?.toString() ?: "")
        }
}

/** The string [key] in the current language, with `%1$s`-style [args] filled in. */
fun t(key: String, vararg args: Any?): String {
    val table = GENERATED_STRINGS[I18n.language] ?: GENERATED_STRINGS.getValue("en")
    val template = table[key] ?: GENERATED_STRINGS.getValue("en")[key] ?: key
    return I18n.format(template, args)
}

/** The plural of [key] for [count] (the Android app's plurals, one or other), with [args] filled in. */
fun tp(key: String, count: Int, vararg args: Any?): String = t(key + if (count == 1) "#one" else "#other", *args)
