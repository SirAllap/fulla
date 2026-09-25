// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.spike

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController { App() }
