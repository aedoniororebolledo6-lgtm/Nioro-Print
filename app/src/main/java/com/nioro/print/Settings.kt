package com.nioro.print

import android.content.Context

/** Ajustes de la impresora y de la app, guardados en SharedPreferences. */
data class PrinterSettings(
    var paperDots: Int = 384,          // 384 = 58 mm, 512 = 72 mm, 576 = 80 mm
    var density: Int = 5,              // 0..10 (oscuridad)
    var sendSpeed: Int = 1,            // 0 lenta, 1 normal, 2 rápida, 3 máxima
    var encoding: Int = 1,             // 0 WPC1252, 1 CP850, 2 CP437, 3 ASCII
    var textAsImage: Boolean = false,  // imprime el texto como imagen (acentos garantizados)
    var fontSize: Int = 0,             // 0..3 => 1x..4x
    var feedAfter: Int = 3,            // líneas de avance al terminar
    var cut: Boolean = false,
    var dither: Boolean = true,
    var applyOnConnect: Boolean = true,
    var extraDensityCmd: Boolean = false, // DC2 # n (algunas impresoras chinas)
    var autoConnect: Boolean = true,
    var force: Boolean = true,
    var retries: Int = 3
) {
    fun copyFrom(o: PrinterSettings) {
        paperDots = o.paperDots; density = o.density; sendSpeed = o.sendSpeed
        encoding = o.encoding; textAsImage = o.textAsImage; fontSize = o.fontSize
        feedAfter = o.feedAfter; cut = o.cut; dither = o.dither
        applyOnConnect = o.applyOnConnect; extraDensityCmd = o.extraDensityCmd
        autoConnect = o.autoConnect; force = o.force; retries = o.retries
    }

    val chunkSize: Int
        get() = intArrayOf(128, 512, 1024, 4096)[sendSpeed.coerceIn(0, 3)]
    val chunkDelayMs: Long
        get() = longArrayOf(40, 20, 8, 0)[sendSpeed.coerceIn(0, 3)]
    val paperMm: String
        get() = when (paperDots) {
            384 -> "58 mm"
            512 -> "72 mm"
            else -> "80 mm"
        }
}

object Store {
    private const val FILE = "nioro_print"

    fun load(ctx: Context): PrinterSettings {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val d = PrinterSettings()
        return PrinterSettings(
            paperDots = p.getInt("paperDots", d.paperDots),
            density = p.getInt("density", d.density),
            sendSpeed = p.getInt("sendSpeed", d.sendSpeed),
            encoding = p.getInt("encoding", d.encoding),
            textAsImage = p.getBoolean("textAsImage", d.textAsImage),
            fontSize = p.getInt("fontSize", d.fontSize),
            feedAfter = p.getInt("feedAfter", d.feedAfter),
            cut = p.getBoolean("cut", d.cut),
            dither = p.getBoolean("dither", d.dither),
            applyOnConnect = p.getBoolean("applyOnConnect", d.applyOnConnect),
            extraDensityCmd = p.getBoolean("extraDensityCmd", d.extraDensityCmd),
            autoConnect = p.getBoolean("autoConnect", d.autoConnect),
            force = p.getBoolean("force", d.force),
            retries = p.getInt("retries", d.retries)
        )
    }

    fun save(ctx: Context, s: PrinterSettings) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt("paperDots", s.paperDots)
            .putInt("density", s.density)
            .putInt("sendSpeed", s.sendSpeed)
            .putInt("encoding", s.encoding)
            .putBoolean("textAsImage", s.textAsImage)
            .putInt("fontSize", s.fontSize)
            .putInt("feedAfter", s.feedAfter)
            .putBoolean("cut", s.cut)
            .putBoolean("dither", s.dither)
            .putBoolean("applyOnConnect", s.applyOnConnect)
            .putBoolean("extraDensityCmd", s.extraDensityCmd)
            .putBoolean("autoConnect", s.autoConnect)
            .putBoolean("force", s.force)
            .putInt("retries", s.retries)
            .apply()
    }

    // ---- Alias de impresoras (renombrar) ----
    fun alias(ctx: Context, addr: String): String? =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("alias_$addr", null)

    fun setAlias(ctx: Context, addr: String, name: String) {
        val e = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
        if (name.isBlank()) e.remove("alias_$addr") else e.putString("alias_$addr", name.trim())
        e.apply()
    }

    fun origBtName(ctx: Context): String? =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("origBtName", null)

    fun saveOrigBtName(ctx: Context, name: String?) {
        val e = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
        if (name == null) e.remove("origBtName") else e.putString("origBtName", name)
        e.apply()
    }

    // ---- Última impresora usada ----
    fun saveLast(ctx: Context, kind: String, a: String, b: String, name: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("lastKind", kind)
            .putString("lastA", a)
            .putString("lastB", b)
            .putString("lastName", name)
            .apply()
    }

    fun lastKind(ctx: Context): String? =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("lastKind", null)

    fun lastA(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("lastA", "") ?: ""

    fun lastB(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("lastB", "") ?: ""

    fun lastName(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("lastName", "") ?: ""
}
