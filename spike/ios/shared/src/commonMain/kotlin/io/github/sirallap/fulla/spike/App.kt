// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.spike

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Everything this spike needs to prove: a Compose Multiplatform screen
 * renders on a real device build, shows a version string, and responds to
 * touch. Nothing here talks to `core` or `client` -- see spike/README.md.
 */
const val appVersion = "1.0.0-ios-spike"

@Composable
fun App() {
    MaterialTheme {
        var tapCount by remember { mutableStateOf(0) }
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Fulla", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            Text("iOS test build")
            Spacer(Modifier.height(4.dp))
            Text("v$appVersion")
            Spacer(Modifier.height(24.dp))
            Button(onClick = { tapCount++ }) {
                Text("Tapped $tapCount times")
            }
        }
    }
}
