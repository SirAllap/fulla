// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.sirallap.fulla.R

/**
 * One family, Archivo (SIL Open Font License, bundled: no font is ever
 * downloaded). Its width axis narrows the big figures so they fit, and every
 * number that sits in a column uses tabular figures.
 */
private fun archivo(weight: Int, width: Float): FontFamily = runCatching {
    FontFamily(
        Font(
            R.font.archivo_variable,
            weight = FontWeight(weight),
            variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.width(width)),
        ),
    )
}.getOrDefault(FontFamily.SansSerif)

object FullaType {
    val heroFigure = TextStyle(fontFamily = archivo(500, 75f), fontWeight = FontWeight.Medium, fontSize = 52.sp, lineHeight = 56.sp, fontFeatureSettings = "tnum")
    val entryAmount = TextStyle(fontFamily = archivo(500, 75f), fontWeight = FontWeight.Medium, fontSize = 44.sp, lineHeight = 48.sp, fontFeatureSettings = "tnum")
    val screenTitle = TextStyle(fontFamily = archivo(600, 100f), fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp)
    val title = TextStyle(fontFamily = archivo(600, 100f), fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp)
    val body = TextStyle(fontFamily = archivo(400, 100f), fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp)
    val secondary = TextStyle(fontFamily = archivo(400, 100f), fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 19.sp)
    val section = TextStyle(fontFamily = archivo(600, 100f), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 19.sp, letterSpacing = 0.2.sp)
    val label = TextStyle(fontFamily = archivo(500, 100f), fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 15.sp)
    val amount = TextStyle(fontFamily = archivo(500, 88f), fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 22.sp, fontFeatureSettings = "tnum")
    val key = TextStyle(fontFamily = archivo(400, 100f), fontWeight = FontWeight.Normal, fontSize = 26.sp, fontFeatureSettings = "tnum")

    val material = Typography(
        headlineMedium = screenTitle,
        titleLarge = title,
        titleMedium = body.copy(fontWeight = FontWeight.Medium),
        bodyLarge = body,
        bodyMedium = secondary,
        labelLarge = section,
        labelMedium = label,
        labelSmall = label,
    )
}
