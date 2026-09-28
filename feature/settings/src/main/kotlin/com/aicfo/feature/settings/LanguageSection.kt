package com.aicfo.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.aicfo.core.datastore.LanguageSetting
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.theme.CfoDimens

/**
 * Which language the app speaks (issue 10.8; SRS §3.5, NFR-011).
 *
 * Why:    a phone in English owned by someone who reads Kannada is the ordinary case here, so the
 *         choice belongs to the app as well as to the device. Every language is listed **in its own
 *         script** — a list of endonyms is how someone finds their language without first being able
 *         to read the one the app is currently in.
 * Result: the composition. Input: [uiState]; [onEvent]. Output: none.
 * Changelog: 2026-09-28 — Created for issue 10.8, in its own file: `SettingsScreen.kt` was at
 *            detekt's function limit, and a section that lives or dies as a unit is the right
 *            thing to lift out of it.
 */
@Composable
internal fun LanguageSection(
    uiState: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
) {
    CfoCard {
        Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
            Text(text = stringResource(R.string.settings_language_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.settings_language_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LanguageSetting.entries.forEach { language ->
                LanguageRow(
                    label = stringResource(languageLabel(language)),
                    selected = language == uiState.language,
                    onSelect = { onEvent(SettingsEvent.LanguageChosen(language)) },
                )
            }
        }
    }
}

/**
 * One language in the list.
 * Why:    `selectable` with a `RadioButton` rather than a row of buttons, so the whole row is the
 *         target (48dp, ACC-*) and TalkBack announces it as one selectable item in a group rather
 *         than as a label and an unlabelled control.
 * Result: the composition. Input: [label]; [selected]; [onSelect]. Output: none.
 * Changelog: 2026-09-28 — Created for issue 10.8.
 */
@Composable
private fun LanguageRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
                .padding(vertical = CfoDimens.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = CfoDimens.spaceSm),
        )
    }
}

/**
 * Result: a language's name, written in its own script. Input: [language]. Output: a resource id.
 * Why:    a `when` over the enum, so a language added to [LanguageSetting] cannot ship without a
 *         name — it is a compile error rather than a blank row.
 * Changelog: 2026-09-28 — Created for issue 10.8.
 */
internal fun languageLabel(language: LanguageSetting): Int =
    when (language) {
        LanguageSetting.SYSTEM -> R.string.settings_language_system
        LanguageSetting.ENGLISH -> R.string.settings_language_english
        LanguageSetting.HINDI -> R.string.settings_language_hindi
        LanguageSetting.KANNADA -> R.string.settings_language_kannada
        LanguageSetting.TAMIL -> R.string.settings_language_tamil
    }
