// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client.remote

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * A QR code as a square of modules, true for dark. The screen draws it
 * module by module, so it stays sharp at any size and needs no bitmap.
 */
class QrCode private constructor(val size: Int, private val modules: BooleanArray) {

    operator fun get(x: Int, y: Int): Boolean = modules[y * size + x]

    companion object {
        /** Medium error correction: an invite still scans off a scratched or dim screen. */
        fun of(text: String): QrCode {
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 0,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            ))
            val n = matrix.width
            return QrCode(n, BooleanArray(n * n) { matrix[it % n, it / n] })
        }
    }
}
