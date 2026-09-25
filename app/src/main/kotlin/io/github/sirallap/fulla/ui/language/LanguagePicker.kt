// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.language

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sirallap.fulla.R
import io.github.sirallap.fulla.core.text.AppLanguage
import io.github.sirallap.fulla.ui.components.ActionBar
import io.github.sirallap.fulla.ui.components.PrimaryButton
import io.github.sirallap.fulla.ui.theme.FullaTheme
import io.github.sirallap.fulla.ui.theme.FullaType

/**
 * The six languages Fulla ships, each row full width and written in its own
 * language, so it reads correctly whatever the phone is currently set to.
 * Shared by the first-run picker and the in-app chooser in Settings.
 */
@Composable
fun LanguageChoices(selected: AppLanguage, onChoose: (AppLanguage) -> Unit, modifier: Modifier = Modifier) {
    val c = FullaTheme.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (language in AppLanguage.ALL) {
            val isSelected = language == selected
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isSelected) c.highlight else c.paperHigh)
                    .border(1.dp, if (isSelected) c.highlight else c.line, RoundedCornerShape(16.dp))
                    .clickable { onChoose(language) }
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(language.nativeName, style = FullaType.body, color = if (isSelected) c.onHighlight else c.ink)
                if (isSelected) Icon(Icons.Outlined.Check, null, tint = c.onHighlight)
            }
        }
    }
}

/**
 * Shown once, before the welcome screen, on a fresh install: no household
 * exists yet and no language has been chosen. Never shown again, including to
 * somebody upgrading from a version without this screen.
 */
@Composable
fun LanguagePickerScreen(initial: AppLanguage, onDone: (AppLanguage) -> Unit) {
    val c = FullaTheme.colors
    var chosen by remember { mutableStateOf(initial) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.weight(1f).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(28.dp))
            Text(stringResource(R.string.language_picker_title), style = FullaType.title, color = c.ink)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.language_picker_text), style = FullaType.body, color = c.inkMuted)
            Spacer(Modifier.height(20.dp))
            LanguageChoices(chosen, onChoose = { chosen = it })
        }
        ActionBar { PrimaryButton(stringResource(R.string.continue_action), { onDone(chosen) }) }
    }
}
