package com.zephirtv.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import java.util.Random
import kotlin.math.sin

/**
 * Genera paesaggi naturali stilizzati (montagne a strati, sole/luna, foschia) senza immagini esterne.
 * Usato come sfondo di default; se in cartella wallpapers ci sono foto vere, hanno la precedenza.
 */
object Scenery {
    private class Palette(
        val skyTop: Int,
        val skyBottom: Int,
        val sun: Int?,
        val ridges: IntArray,
        val stars: Boolean = false
    )

    private fun c(v: Long) = v.toInt()

    private val palettes = listOf(
        // Alba sulle montagne
        Palette(c(0xFF22305A), c(0xFFF4A261), c(0xFFFFE8B0),
            intArrayOf(c(0xFF8A6F8E), c(0xFF5E5578), c(0xFF3A3F63), c(0xFF202645))),
        // Prato dorato
        Palette(c(0xFF4C8DC9), c(0xFFF7E3B0), c(0xFFFFF6D5),
            intArrayOf(c(0xFF9DB7A3), c(0xFF6F9A72), c(0xFF4A7A52), c(0xFF2E5A3C))),
        // Alpi, mattino freddo
        Palette(c(0xFF2F5D8C), c(0xFFBFD8EA), null,
            intArrayOf(c(0xFF9FB6CC), c(0xFF6D8BA8), c(0xFF46627F), c(0xFF2A3F57))),
        // Tramonto viola
        Palette(c(0xFF2B1B4D), c(0xFFFF7E6B), c(0xFFFFC38A),
            intArrayOf(c(0xFF8F4F7A), c(0xFF633A6B), c(0xFF3E2A56), c(0xFF211838))),
        // Foresta nella nebbia
        Palette(c(0xFF6F8F86), c(0xFFDDE7DF), null,
            intArrayOf(c(0xFFA7BDB1), c(0xFF7C9A8C), c(0xFF547565), c(0xFF33503F))),
        // Notte stellata
        Palette(c(0xFF050B1E), c(0xFF22406E), c(0xFFEAF0FF),
            intArrayOf(c(0xFF1B3358), c(0xFF142848), c(0xFF0D1B33), c(0xFF070F20)), stars = true)
    )

    fun render(w: Int, h: Int, seed: Long): Bitmap {
        val rnd = Random(seed)
        val pal = palettes[rnd.nextInt(palettes.size)]
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // Cielo
        p.shader = LinearGradient(0f, 0f, 0f, h * 0.8f, pal.skyTop, pal.skyBottom, Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null

        if (pal.stars) {
            p.color = Color.WHITE
            repeat(220) {
                p.alpha = 80 + rnd.nextInt(170)
                cv.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h * 0.55f, 0.6f + rnd.nextFloat() * 1.4f, p)
            }
            p.alpha = 255
        }

        // Sole / luna con alone
        pal.sun?.let { sunColor ->
            val sx = w * (0.2f + 0.6f * rnd.nextFloat())
            val sy = h * (0.26f + 0.18f * rnd.nextFloat())
            val r = h * 0.07f
            val glow = (sunColor and 0x00FFFFFF) or 0x66000000
            p.shader = RadialGradient(sx, sy, r * 6f, glow, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            cv.drawCircle(sx, sy, r * 6f, p)
            p.shader = null
            p.color = sunColor
            cv.drawCircle(sx, sy, r, p)
        }

        // Strati di montagne dal piu' lontano al piu' vicino
        val layers = pal.ridges.size
        for (i in 0 until layers) {
            val base = h * (0.50f + 0.11f * i)
            val amp = h * (0.17f - 0.025f * i)
            val f1 = 0.0016f + rnd.nextFloat() * 0.0012f
            val f2 = f1 * (2.1f + rnd.nextFloat())
            val f3 = f2 * (2.3f + rnd.nextFloat())
            val p1 = rnd.nextFloat() * 6.28f
            val p2 = rnd.nextFloat() * 6.28f
            val p3 = rnd.nextFloat() * 6.28f

            val path = Path()
            path.moveTo(0f, h.toFloat())
            var x = 0f
            while (x <= w + 8) {
                val y = base - amp * (0.55f * sin(x * f1 + p1) + 0.30f * sin(x * f2 + p2) + 0.15f * sin(x * f3 + p3))
                path.lineTo(x, y)
                x += 8f
            }
            path.lineTo(w.toFloat(), h.toFloat())
            path.close()

            val col = pal.ridges[i]
            val dark = darken(col, 0.78f)
            p.shader = LinearGradient(0f, base - amp, 0f, h.toFloat(), col, dark, Shader.TileMode.CLAMP)
            cv.drawPath(path, p)
            p.shader = null

            // Foschia sopra gli strati lontani
            if (i < layers - 1) {
                p.shader = LinearGradient(
                    0f, base - amp * 0.3f, 0f, base + amp * 1.3f,
                    Color.TRANSPARENT, 0x30FFFFFF, Shader.TileMode.CLAMP
                )
                cv.drawRect(0f, base - amp * 0.3f, w.toFloat(), base + amp * 1.3f, p)
                p.shader = null
            }
        }
        return bmp
    }

    private fun darken(color: Int, f: Float): Int {
        val r = (Color.red(color) * f).toInt()
        val g = (Color.green(color) * f).toInt()
        val b = (Color.blue(color) * f).toInt()
        return Color.rgb(r, g, b)
    }
}
