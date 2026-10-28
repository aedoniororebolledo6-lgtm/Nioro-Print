package com.nioro.print

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Busca la última versión publicada en GitHub Releases. */
object Updater {
    private const val API = "https://api.github.com/repos/aedoniororebolledo6-lgtm/Nioro-Print/releases/latest"

    class Result(val ok: Boolean, val message: String, val newer: Boolean = false, val url: String = "")

    @Suppress("DEPRECATION")
    fun currentVersionCode(ctx: Context): Long {
        return try {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            info.longVersionCode
        } catch (e: Exception) {
            0L
        }
    }

    fun currentVersionName(ctx: Context): String {
        return try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
    }

    fun check(ctx: Context, cb: (Result) -> Unit) {
        Thread {
            val res = try {
                val c = URL(API).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", "NioroPrint")
                if (c.responseCode != 200) {
                    Result(false, "GitHub respondió ${c.responseCode}. ¿Ya hay una versión publicada?")
                } else {
                    val body = c.inputStream.bufferedReader().use { it.readText() }
                    val j = JSONObject(body)
                    val tag = j.optString("tag_name", "")
                    val n = tag.filter { it.isDigit() }.toLongOrNull() ?: 0L
                    var url = ""
                    val assets = j.optJSONArray("assets")
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val a = assets.getJSONObject(i)
                            if (a.optString("name").endsWith(".apk")) {
                                url = a.optString("browser_download_url")
                                break
                            }
                        }
                    }
                    val cur = currentVersionCode(ctx)
                    if (n > cur && url.isNotEmpty()) {
                        Result(true, "Hay una versión nueva ($tag). Instalada: ${currentVersionName(ctx)}", true, url)
                    } else {
                        Result(true, "Ya tienes la última versión (${currentVersionName(ctx)})")
                    }
                }
            } catch (e: Exception) {
                Result(false, "No se pudo comprobar: ${e.message}")
            }
            cb(res)
        }.start()
    }
}
