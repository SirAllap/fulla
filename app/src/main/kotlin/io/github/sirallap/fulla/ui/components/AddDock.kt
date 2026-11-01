// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.ui.components

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Where Add stands, as the round button in the middle of the tab bar shows it. */
enum class AddPhase {
    /** Nothing written yet: the round "+". */
    IDLE,
    /** A category is chosen, the amount is missing. */
    NEEDS_AMOUNT,
    /** The amount is there, the category is missing. */
    NEEDS_CATEGORY,
    /** Everything is there: the button is "Save · amount". */
    READY,
    /** Saved: the button gathers into a check before the screen moves on. */
    SAVED,
}

/**
 * What Add hands to the tab bar: the state of the entry being written, the
 * words on the button, and what the button and the little red drop beside it
 * do. Add publishes it while it is on screen and clears it when it leaves.
 */
class AddDock {
    var phase by mutableStateOf(AddPhase.IDLE)
        private set
    var label by mutableStateOf("")
        private set
    /** Counts up each time the button is pressed while something is missing: the bar shakes it. */
    var nudges by mutableIntStateOf(0)
        private set
    private var save: () -> Unit = {}
    private var discard: () -> Unit = {}
    private var missing: () -> Unit = {}

    fun publish(phase: AddPhase, label: String, onSave: () -> Unit, onDiscard: () -> Unit, onMissing: () -> Unit) {
        if (this.phase != phase) this.phase = phase
        if (this.label != label) this.label = label
        save = onSave; discard = onDiscard; missing = onMissing
    }

    fun clear() {
        phase = AddPhase.IDLE
        label = ""
        save = {}; discard = {}; missing = {}
    }

    fun onSave() = save()
    fun onDiscard() = discard()
    fun onMissing() { nudges++; missing() }
}

/** The colour the person chose for Save (Settings › Appearance); null: the app's primary button. */
val LocalSaveColor = compositionLocalOf<io.github.sirallap.fulla.core.design.Accent?> { null }
