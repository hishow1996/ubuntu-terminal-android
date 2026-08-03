package com.linuxterminal.mobile.terminal

/**
 * ANSI terminal color palette.
 * 16 standard colors + 216 color cube + 24 grayscale = 256 colors.
 */
object TerminalColors {
    // Standard 16 colors (0-7 normal, 8-15 bright)
    val STANDARD = intArrayOf(
        0x2B2B2B, 0xCC0000, 0x4E9A06, 0xC4A000,
        0x3465A4, 0x75507B, 0x06989A, 0xD3D7CF,
        0x555753, 0xEF2929, 0x8AE234, 0xFCE94F,
        0x729FCF, 0xAD7FA8, 0x34E2E2, 0xEEEEEC
    )

    // 256-color palette (indexed 0-255)
    val PALETTE: IntArray = IntArray(256).also { palette ->
        // Standard 16 colors
        STANDARD.copyInto(palette)
        // 216 color cube (6x6x6) starting at index 16
        val levels = intArrayOf(0x00, 0x5F, 0x87, 0xAF, 0xD7, 0xFF)
        for (r in 0..5) {
            for (g in 0..5) {
                for (b in 0..5) {
                    val idx = 16 + r * 36 + g * 6 + b
                    palette[idx] = (levels[r] shl 16) or (levels[g] shl 8) or levels[b]
                }
            }
        }
        // 24 grayscale colors starting at index 232
        for (i in 0..23) {
            val v = 0x08 + i * 10
            palette[232 + i] = (v shl 16) or (v shl 8) or v
        }
    }

    /** Convert a 256-color index to an ARGB int */
    fun toArgb(color: Int): Int {
        return if (color < PALETTE.size) PALETTE[color] else 0xFFFFFF
    }

    /** Parse SGR color parameters and return the color index */
    fun parseColor(params: IntArray, offset: Int): Int {
        if (offset >= params.size) return 7
        return when (params[offset]) {
            2 -> {
                // RGB color: CSI 2;R;G;Bm
                if (offset + 3 < params.size) {
                    val r = params[offset + 1].coerceIn(0, 255)
                    val g = params[offset + 2].coerceIn(0, 255)
                    val b = params[offset + 3].coerceIn(0, 255)
                    (r shl 16) or (g shl 8) or b
                } else 7
            }
            5 -> {
                // 256-color: CSI 5;Nm
                if (offset + 1 < params.size) {
                    val n = params[offset + 1].coerceIn(0, 255)
                    PALETTE[n]
                } else 7
            }
            else -> 7
        }
    }
}
