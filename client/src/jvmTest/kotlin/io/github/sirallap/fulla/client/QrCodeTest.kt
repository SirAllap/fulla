// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client

import io.github.sirallap.fulla.client.remote.Endpoint
import kotlin.test.Test
import kotlin.test.assertEquals

class QrCodeTest {
    @Test
    fun `an invite link survives becoming a QR code and being read back`() {
        val link = io.github.sirallap.fulla.client.remote.InviteLink(Endpoint.parse("abcdefghijklmnopqrst.supabase.co", "anon.key.value")!!, "K7QD-M2XP").toUri()
        val qr = io.github.sirallap.fulla.client.remote.QrCode.of(link)
        // Render with a quiet zone, 4 pixels per module, and decode it as a camera would.
        val scale = 4
        val side = (qr.size + 8) * scale
        val pixels = IntArray(side * side) { i ->
            val x = i % side / scale - 4
            val y = i / side / scale - 4
            if (x in 0 until qr.size && y in 0 until qr.size && qr[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bitmap = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(com.google.zxing.RGBLuminanceSource(side, side, pixels)))
        assertEquals(link, com.google.zxing.qrcode.QRCodeReader().decode(bitmap).text)
    }
}
