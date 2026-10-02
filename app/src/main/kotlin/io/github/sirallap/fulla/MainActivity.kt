// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import io.github.sirallap.fulla.client.remote.InviteLink
import io.github.sirallap.fulla.ui.FullaRoot
import io.github.sirallap.fulla.ui.LanguageApplier
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.ZonedDateTime

/**
 * The only activity. A FragmentActivity because the biometric prompt needs
 * one; everything on screen is Compose.
 */
class MainActivity : FragmentActivity() {
    private val container get() = (application as FullaApp).container
    private var lastForegroundSync = 0L
    private var recurring: Job? = null

    // Below Android 13 there is no per-app language, so a chosen language is
    // kept outside DataStore (sync, unlike DataStore) and applied here, before
    // any resource is read. On 33+ this is a no-op: LocaleManager already
    // restarts the process with the right configuration.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageApplier.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readInvite(intent)
        setContent { FullaRoot(container, this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readInvite(intent)
    }

    override fun onStart() {
        super.onStart()
        // Fixed costs that came due are written whenever the app is looked at
        // (phone-only households included, nothing here needs a network), and
        // again at midnight if it stays open across it. Never throttled: it
        // reads what is held and writes nothing when nothing is due.
        recurring = lifecycleScope.launch {
            while (true) {
                container.generateRecurring()
                delay(millisUntilTomorrow())
            }
        }
        // Coming back to the app is when somebody wants to see what the other
        // phones did. At most once a minute; the sync never blocks the screen.
        val now = SystemClock.elapsedRealtime()
        if (now - lastForegroundSync > 60_000) {
            lastForegroundSync = now
            lifecycleScope.launch { container.syncAll() }
        }
        // AppContainer.checkForUpdates keeps its own 12-hour throttle in
        // DataStore, so calling it on every foreground is cheap and correct.
        lifecycleScope.launch { container.checkForUpdates() }
        // One RPC, only when signed in to a connected household; cheap enough
        // to run on every foreground too, no throttle needed.
        lifecycleScope.launch { container.checkDbSchema() }
    }

    override fun onStop() {
        recurring?.cancel()
        recurring = null
        super.onStop()
    }

    private fun millisUntilTomorrow(): Long {
        val now = ZonedDateTime.now()
        return Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(now.zone)).toMillis() + 1_000
    }

    private fun readInvite(intent: Intent?) {
        intent?.dataString?.let(InviteLink::parse)?.let { container.pendingInvite.value = it }
    }
}
