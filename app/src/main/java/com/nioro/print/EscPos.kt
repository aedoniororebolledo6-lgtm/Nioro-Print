package com.nioro.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.oned.Code128Writer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream
import java.text.Normalizer

/** Generador de comandos ESC/POS (impresoras térmicas de 58/72/80 mm). */
class EscPos(private val s: PrinterSettings) {
    private val out = ByteArrayOutputStream()

    fun toBytes(): ByteArray = out.toByteArray()

    fun raw(vararg b: Int): EscPos {
        for (v in b) out.write(v and 0xFF)
        return this
    }

    fun bytes(b: ByteArray): EscPos {
        out.write(b, 0, b.size)
        return this
    }

    fun init(): EscPos = raw(0x1B, 0x40)

    /** Página de códigos según la codificación elegida. */
    fun codepage(): EscPos {
        val n = when (s.encoding) {
            0 -> 16   // WPC1252
            1 -> 2    // CP850
            else -> 0 // CP437 / ASCII
        }
        return raw(0x1B, 0x74, n)
    }

    /** Densidad / calor / extras. Se aplica tras ESC @. */
    fun heat(): EscPos {
        if (!s.applyOnConnect) return this
        val d = s.density.coerceIn(0, 10)
        val heatTime = 40 + d * 14      // n2 * 10 us
        raw(0x1B, 0x37, 7, heatTime, 2) // ESC 7 n1 n2 n3
        if (s.extraDensityCmd) {
            raw(0x12, 0x23, (8 + d * 2).coerceAtMost(31)) // DC2 # n
        }
        return this
    }

    fun align(a: Int): EscPos = raw(0x1B, 0x61, a.coerceIn(0, 2))
    fun bold(on: Boolean): EscPos = raw(0x1B, 0x45, if (on) 1 else 0)
    fun underline(n: Int): EscPos = raw(0x1B, 0x2D, n.coerceIn(0, 2))
    fun size(mult: Int): EscPos {
        val m = (mult - 1).coerceIn(0, 7)
        return raw(0x1D, 0x21, (m shl 4) or m)
    }

    fun lf(n: Int = 1): EscPos {
        for (i in 0 until n) raw(0x0A)
        return this
    }

    fun feed(n: Int): EscPos = if (n > 0) raw(0x1B, 0x64, n.coerceIn(0, 255)) else this
    fun cut(): EscPos = raw(0x1D, 0x56, 66, 0)

    fun text(t: String): EscPos = bytes(encode(t, s.encoding))

    fun line(t: String): EscPos {
        text(t)
        return lf()
    }

    /** Termina el trabajo: reinicia estilos, avanza papel y corta si corresponde. */
    fun finish(): EscPos {
        align(0).bold(false).underline(0).size(1)
        feed(s.feedAfter)
        if (s.cut) cut()
        return this
    }

    /** Imagen en bandas GS v 0 (raster). */
    fun image(bmp: Bitmap, upscale: Boolean = false, dither: Boolean = s.dither): EscPos {
        val w = s.paperDots
        val fitted = fitToWidth(bmp, w, upscale)
        bytes(rasterBands(fitted, dither))
        return this
    }

    companion object {
        // ---------- Codificación de texto ----------
        private val cp850Map = mapOf(
            'Ç' to 0x80, 'ü' to 0x81, 'é' to 0x82, 'â' to 0x83, 'ä' to 0x84, 'à' to 0x85,
            'å' to 0x86, 'ç' to 0x87, 'ê' to 0x88, 'ë' to 0x89, 'è' to 0x8A, 'ï' to 0x8B,
            'î' to 0x8C, 'ì' to 0x8D, 'Ä' to 0x8E, 'Å' to 0x8F, 'É' to 0x90, 'æ' to 0x91,
            'Æ' to 0x92, 'ô' to 0x93, 'ö' to 0x94, 'ò' to 0x95, 'û' to 0x96, 'ù' to 0x97,
            'ÿ' to 0x98, 'Ö' to 0x99, 'Ü' to 0x9A, 'ø' to 0x9B, '£' to 0x9C, 'Ø' to 0x9D,
            '×' to 0x9E, 'á' to 0xA0, 'í' to 0xA1, 'ó' to 0xA2, 'ú' to 0xA3, 'ñ' to 0xA4,
            'Ñ' to 0xA5, 'ª' to 0xA6, 'º' to 0xA7, '¿' to 0xA8, '®' to 0xA9, '¬' to 0xAA,
            '½' to 0xAB, '¼' to 0xAC, '¡' to 0xAD, '«' to 0xAE, '»' to 0xAF, 'Á' to 0xB5,
            'Â' to 0xB6, 'À' to 0xB7, '©' to 0xB8, '¢' to 0xBD, '¥' to 0xBE, 'ã' to 0xC6,
            'Ã' to 0xC7, '¤' to 0xCF, 'Ê' to 0xD2, 'Ë' to 0xD3, 'È' to 0xD4, 'Í' to 0xD6,
            'Î' to 0xD7, 'Ï' to 0xD8, 'Ì' to 0xDE, 'Ó' to 0xE0, 'ß' to 0xE1, 'Ô' to 0xE2,
            'Ò' to 0xE3, 'õ' to 0xE4, 'Õ' to 0xE5, 'µ' to 0xE6, 'Ú' to 0xE9, 'Û' to 0xEA,
            'Ù' to 0xEB, 'ý' to 0xEC, 'Ý' to 0xED, '°' to 0xF8, '±' to 0xF1, '²' to 0xFD
        )

        private val cp437Map = mapOf(
            'Ç' to 0x80, 'ü' to 0x81, 'é' to 0x82, 'â' to 0x83, 'ä' to 0x84, 'à' to 0x85,
            'å' to 0x86, 'ç' to 0x87, 'ê' to 0x88, 'ë' to 0x89, 'è' to 0x8A, 'ï' to 0x8B,
            'î' to 0x8C, 'ì' to 0x8D, 'Ä' to 0x8E, 'Å' to 0x8F, 'É' to 0x90, 'æ' to 0x91,
            'Æ' to 0x92, 'ô' to 0x93, 'ö' to 0x94, 'ò' to 0x95, 'û' to 0x96, 'ù' to 0x97,
            'ÿ' to 0x98, 'Ö' to 0x99, 'Ü' to 0x9A, '¢' to 0x9B, '£' to 0x9C, '¥' to 0x9D,
            'á' to 0xA0, 'í' to 0xA1, 'ó' to 0xA2, 'ú' to 0xA3, 'ñ' to 0xA4, 'Ñ' to 0xA5,
            'ª' to 0xA6, 'º' to 0xA7, '¿' to 0xA8, '¬' to 0xAA, '½' to 0xAB, '¼' to 0xAC,
            '¡' to 0xAD, '«' to 0xAE, '»' to 0xAF, 'ß' to 0xE1, 'µ' to 0xE6, '°' to 0xF8,
            '±' to 0xF1, '²' to 0xFD
        )

        /** Quita acentos para impresoras sin páginas de códigos. */
        fun stripAccents(t: String): String {
            val n = Normalizer.normalize(t, Normalizer.Form.NFD)
            val sb = StringBuilder()
            for (c in n) {
                if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) continue
                sb.append(
                    when (c) {
                        '¿' -> '?'
                        '¡' -> '!'
                        'ß' -> 's'
                        else -> c
                    }
                )
            }
            return sb.toString()
        }

        fun encode(text: String, encoding: Int): ByteArray {
            val t = text.replace("\r\n", "\n").replace('\r', '\n')
            return when (encoding) {
                0 -> t.toByteArray(charset("windows-1252"))
                1 -> mapBytes(t, cp850Map)
                2 -> mapBytes(t, cp437Map)
                else -> mapBytes(stripAccents(t), emptyMap())
            }
        }

        private fun mapBytes(t: String, map: Map<Char, Int>): ByteArray {
            val o = ByteArrayOutputStream()
            for (c in t) {
                when {
                    c.code < 128 -> o.write(c.code)
                    map.containsKey(c) -> o.write(map.getValue(c))
                    c == '€' -> o.write('E'.code)
                    else -> o.write('?'.code)
                }
            }
            return o.toByteArray()
        }

        // ---------- Imágenes ----------
        fun fitToWidth(src: Bitmap, w: Int, upscale: Boolean): Bitmap {
            if (src.width == w) return flatten(src)
            if (src.width > w || upscale) {
                val h = (src.height.toFloat() * w / src.width).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(src, w, h, true)
                return flatten(scaled)
            }
            // Más pequeña que el papel: centrada sobre fondo blanco
            val canvasBmp = Bitmap.createBitmap(w, src.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(canvasBmp)
            c.drawColor(Color.WHITE)
            c.drawBitmap(src, ((w - src.width) / 2).toFloat(), 0f, null)
            return canvasBmp
        }

        /** Compone sobre blanco (transparencias -> blanco). */
        private fun flatten(src: Bitmap): Bitmap {
            val b = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.drawColor(Color.WHITE)
            c.drawBitmap(src, 0f, 0f, null)
            return b
        }

        fun rasterBands(bmp: Bitmap, dither: Boolean): ByteArray {
            val w = bmp.width
            val h = bmp.height
            val wb = (w + 7) / 8
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            val gray = FloatArray(w * h)
            for (i in px.indices) {
                val p = px[i]
                gray[i] = 0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)
            }
            val bits = ByteArray(wb * h)
            if (dither) {
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val i = y * w + x
                        val old = gray[i]
                        val nw = if (old < 128f) 0f else 255f
                        val err = old - nw
                        if (x + 1 < w) gray[i + 1] += err * 7f / 16f
                        if (y + 1 < h) {
                            if (x > 0) gray[i + w - 1] += err * 3f / 16f
                            gray[i + w] += err * 5f / 16f
                            if (x + 1 < w) gray[i + w + 1] += err * 1f / 16f
                        }
                        if (nw == 0f) {
                            val bi = y * wb + (x shr 3)
                            bits[bi] = (bits[bi].toInt() or (0x80 shr (x and 7))).toByte()
                        }
                    }
                }
            } else {
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        if (gray[y * w + x] < 140f) {
                            val bi = y * wb + (x shr 3)
                            bits[bi] = (bits[bi].toInt() or (0x80 shr (x and 7))).toByte()
                        }
                    }
                }
            }
            val o = ByteArrayOutputStream()
            val band = 64
            var y = 0
            while (y < h) {
                val rows = minOf(band, h - y)
                o.write(0x1D); o.write(0x76); o.write(0x30); o.write(0x00)
                o.write(wb and 0xFF); o.write((wb shr 8) and 0xFF)
                o.write(rows and 0xFF); o.write((rows shr 8) and 0xFF)
                o.write(bits, y * wb, rows * wb)
                y += rows
            }
            return o.toByteArray()
        }

        fun textBitmap(text: String, widthDots: Int, sizeMult: Int, bold: Boolean, align: Int): Bitmap {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.BLACK
            paint.textSize = 22f * sizeMult
            paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            val la = when (align) {
                1 -> Layout.Alignment.ALIGN_CENTER
                2 -> Layout.Alignment.ALIGN_OPPOSITE
                else -> Layout.Alignment.ALIGN_NORMAL
            }
            val safe = if (text.isEmpty()) " " else text
            val layout = StaticLayout.Builder.obtain(safe, 0, safe.length, paint, widthDots)
                .setAlignment(la)
                .build()
            val bmp = Bitmap.createBitmap(widthDots, layout.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.WHITE)
            layout.draw(c)
            return bmp
        }

        private fun matrixToBitmap(m: BitMatrix): Bitmap {
            val bmp = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
            for (y in 0 until m.height) {
                for (x in 0 until m.width) {
                    bmp.setPixel(x, y, if (m.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            return bmp
        }

        fun qrBitmap(content: String, size: Int): Bitmap {
            val hints = HashMap<EncodeHintType, Any>()
            hints[EncodeHintType.MARGIN] = 1
            hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
            hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
            val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            return matrixToBitmap(m)
        }

        fun barcodeBitmap(content: String, widthDots: Int, height: Int): Bitmap {
            val hints = HashMap<EncodeHintType, Any>()
            hints[EncodeHintType.MARGIN] = 8
            val m = Code128Writer().encode(content, BarcodeFormat.CODE_128, widthDots, height, hints)
            return matrixToBitmap(m)
        }

        // ---------- Trabajos completos ----------
        fun configJob(s: PrinterSettings): ByteArray =
            EscPos(s).init().codepage().heat().toBytes()

        fun textJob(s: PrinterSettings, text: String, align: Int, bold: Boolean): ByteArray {
            val e = EscPos(s).init().codepage().heat()
            if (s.textAsImage) {
                val bmp = textBitmap(text.replace("\r", ""), s.paperDots, s.fontSize + 1, bold, align)
                e.image(bmp, upscale = false, dither = false)
            } else {
                e.align(align).bold(bold).size(s.fontSize + 1)
                e.text(text)
                e.lf()
            }
            e.finish()
            return e.toBytes()
        }

        fun imageJob(s: PrinterSettings, bmp: Bitmap): ByteArray {
            val e = EscPos(s).init().codepage().heat()
            e.image(bmp, upscale = true)
            e.lf()
            e.finish()
            return e.toBytes()
        }

        fun qrJob(s: PrinterSettings, content: String): ByteArray {
            val size = ((s.paperDots * 0.72f).toInt() / 8) * 8
            val bmp = qrBitmap(content, size)
            val e = EscPos(s).init().codepage().heat()
            e.image(bmp, upscale = false, dither = false)
            e.align(1).line(if (content.length > 40) content.take(37) + "..." else content)
            e.finish()
            return e.toBytes()
        }

        fun barcodeJob(s: PrinterSettings, content: String): ByteArray {
            val bmp = barcodeBitmap(content, s.paperDots - 16, 110)
            val e = EscPos(s).init().codepage().heat()
            e.image(bmp, upscale = false, dither = false)
            e.align(1).line(content)
            e.finish()
            return e.toBytes()
        }

        fun feedJob(s: PrinterSettings, lines: Int): ByteArray =
            EscPos(s).init().feed(lines).toBytes()

        fun cutJob(s: PrinterSettings): ByteArray =
            EscPos(s).init().feed(3).cut().toBytes()

        fun testJob(s: PrinterSettings, printerName: String): ByteArray {
            val e = EscPos(s).init().codepage().heat()
            e.align(1).bold(true).size(2).line("NIORO PRINT").size(1).bold(false)
            e.line("Prueba de impresion")
            e.line(printerName)
            e.align(0)
            e.line("--------------------------------")
            e.line("Papel: ${s.paperMm} (${s.paperDots} puntos)")
            e.line("Oscuridad: ${s.density}/10")
            e.line("12345678901234567890123456789012")
            e.line("Acentos: á é í ó ú ñ Ñ ü ¿? ¡!")
            e.bold(true).line("Texto en negrita").bold(false)
            e.underline(1).line("Texto subrayado").underline(0)
            e.align(1).line("Centrado").align(2).line("Derecha").align(0)
            e.size(2).line("Doble").size(1)
            e.lf()
            // Marco del ancho de papel: sirve para verificar el ancho real
            val frame = Bitmap.createBitmap(s.paperDots, 40, Bitmap.Config.ARGB_8888)
            val c = Canvas(frame)
            c.drawColor(Color.WHITE)
            val p = Paint()
            p.color = Color.BLACK
            p.style = Paint.Style.STROKE
            p.strokeWidth = 4f
            c.drawRect(2f, 2f, (s.paperDots - 3).toFloat(), 37f, p)
            e.image(frame, upscale = false, dither = false)
            // Escala de grises para ajustar la oscuridad
            val grad = Bitmap.createBitmap(s.paperDots, 48, Bitmap.Config.ARGB_8888)
            val gc = Canvas(grad)
            val steps = 8
            val sw = s.paperDots / steps
            for (i in 0 until steps) {
                val v = 255 - (i * 255 / (steps - 1))
                val gp = Paint()
                gp.color = Color.rgb(v, v, v)
                gc.drawRect((i * sw).toFloat(), 0f, ((i + 1) * sw).toFloat(), 48f, gp)
            }
            e.image(grad, upscale = false, dither = true)
            e.lf()
            e.image(qrBitmap("https://github.com/aedoniororebolledo6-lgtm/Nioro-Print", 200), upscale = false, dither = false)
            e.align(1).line("Nioro Print OK")
            e.finish()
            return e.toBytes()
        }
    }
}
