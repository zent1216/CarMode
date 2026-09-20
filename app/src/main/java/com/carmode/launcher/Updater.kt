package com.carmode.launcher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** GitHub Releases 기반 업데이트 확인/다운로드 */
object Updater {

    private const val API = "https://api.github.com/repos/zent1216/CarMode/releases/latest"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class Release(val version: String, val notes: String, val apkUrl: String)

    /** 최신 릴리스 조회 (실패 시 null) */
    suspend fun fetchLatest(): Release? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(API)
                .header("Accept", "application/vnd.github+json").build()
            val body = client.newCall(req).execute().use { it.body?.string() } ?: return@withContext null
            val o = JSONObject(body)
            val tag = o.getString("tag_name").removePrefix("v").trim()
            val notes = o.optString("body", "")
            val assets = o.optJSONArray("assets") ?: return@withContext null
            var apkUrl = ""
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk", true)) {
                    apkUrl = a.getString("browser_download_url"); break
                }
            }
            if (apkUrl.isEmpty()) return@withContext null
            Release(tag, notes, apkUrl)
        } catch (e: Exception) { null }
    }

    /** "1.10" > "1.9" 처럼 숫자 단위로 비교. latest가 current보다 크면 true */
    fun isNewer(current: String, latest: String): Boolean {
        val c = current.split(".").map { it.toIntOrNull() ?: 0 }
        val l = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val n = maxOf(c.size, l.size)
        for (i in 0 until n) {
            val cv = c.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (lv != cv) return lv > cv
        }
        return false
    }

    /** APK 다운로드 → 저장된 File 반환 (실패 시 null) */
    suspend fun downloadApk(cacheDir: File, url: String): File? = withContext(Dispatchers.IO) {
        try {
            val dir = File(cacheDir, "updates").apply { mkdirs() }
            val out = File(dir, "CarMode-update.apk")
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                val bytes = resp.body?.bytes() ?: return@withContext null
                out.writeBytes(bytes)
            }
            out
        } catch (e: Exception) { null }
    }
}
