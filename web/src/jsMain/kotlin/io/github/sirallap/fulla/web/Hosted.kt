// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.web

import io.github.sirallap.fulla.client.remote.Endpoint

/** The project this build was made for, if any (HostedConfig is generated from the build's environment). */
object Hosted {
    val endpoint: Endpoint? = Endpoint.parse(HostedConfig.URL, HostedConfig.KEY)
}
