package com.carmode.launcher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Open-Meteo API (무료, 키 불필요): 날씨 + 대기질 + 도시 검색 */
object WeatherApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    data class Weather(
        val temp: Int, val feels: Int, val humidity: Int,
        val wind: Double, val rainProb: Int,
        val tMax: Int, val tMin: Int,
        val code: Int,
        val pm10: Double?, val pm25: Double?,
        val uvi: Double?
    )

    data class City(val name: String, val region: String, val lat: Double, val lon: Double)

    /** 주간 예보 하루치 */
    data class DayForecast(
        val dow: String,      // 요일 (월/화/…), 오늘은 "오늘"
        val code: Int,
        val tMax: Int, val tMin: Int,
        val rainProb: Int
    )

    /** 7일 주간 예보 조회 */
    suspend fun fetchWeekly(lat: Double, lon: Double): List<DayForecast> = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&timezone=auto&forecast_days=7"
            val daily = JSONObject(get(url)).getJSONObject("daily")
            val times = daily.getJSONArray("time")
            val codes = daily.getJSONArray("weather_code")
            val maxs = daily.getJSONArray("temperature_2m_max")
            val mins = daily.getJSONArray("temperature_2m_min")
            val rains = daily.getJSONArray("precipitation_probability_max")
            val dowNames = arrayOf("일", "월", "화", "수", "목", "금", "토")
            val cal = java.util.Calendar.getInstance()
            (0 until times.length()).map { i ->
                val dateStr = times.getString(i)  // yyyy-MM-dd
                val parts = dateStr.split("-")
                val dowLabel = if (i == 0) "오늘" else {
                    cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
                    dowNames[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]
                }
                DayForecast(
                    dow = dowLabel,
                    code = codes.getInt(i),
                    tMax = maxs.getDouble(i).toInt(),
                    tMin = mins.getDouble(i).toInt(),
                    rainProb = if (rains.isNull(i)) 0 else rains.getInt(i)
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    /** 날씨 + 대기질을 함께 조회 */
    suspend fun fetch(lat: Double, lon: Double): Weather? = withContext(Dispatchers.IO) {
        try {
            val wUrl = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,precipitation_probability" +
                "&daily=temperature_2m_max,temperature_2m_min,uv_index_max&timezone=auto"
            val wJson = JSONObject(get(wUrl))
            val cur = wJson.getJSONObject("current")
            val daily = wJson.getJSONObject("daily")

            // 대기질은 별도 엔드포인트 (실패해도 날씨는 표시)
            var pm10: Double? = null
            var pm25: Double? = null
            try {
                val aUrl = "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=$lat&longitude=$lon" +
                    "&current=pm10,pm2_5&timezone=auto"
                val aCur = JSONObject(get(aUrl)).getJSONObject("current")
                pm10 = if (aCur.isNull("pm10")) null else aCur.getDouble("pm10")
                pm25 = if (aCur.isNull("pm2_5")) null else aCur.getDouble("pm2_5")
            } catch (_: Exception) { /* 대기질 실패 무시 */ }

            Weather(
                temp = cur.getDouble("temperature_2m").toInt(),
                feels = cur.getDouble("apparent_temperature").toInt(),
                humidity = cur.getInt("relative_humidity_2m"),
                wind = cur.getDouble("wind_speed_10m"),
                rainProb = cur.optInt("precipitation_probability", 0),
                tMax = daily.getJSONArray("temperature_2m_max").getDouble(0).toInt(),
                tMin = daily.getJSONArray("temperature_2m_min").getDouble(0).toInt(),
                code = cur.getInt("weather_code"),
                pm10 = pm10, pm25 = pm25,
                uvi = runCatching { daily.getJSONArray("uv_index_max").getDouble(0) }.getOrNull()
            )
        } catch (e: Exception) { null }
    }

    /** 도시 이름으로 검색 (지오코딩) */
    suspend fun searchCity(query: String): List<City> = withContext(Dispatchers.IO) {
        try {
            val url = "https://geocoding-api.open-meteo.com/v1/search?name=" +
                java.net.URLEncoder.encode(query, "UTF-8") + "&count=6&language=ko"
            val arr = JSONObject(get(url)).optJSONArray("results") ?: return@withContext emptyList()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val region = listOfNotNull(
                    o.optString("admin1").ifEmpty { null },
                    o.optString("country").ifEmpty { null }
                ).joinToString(", ")
                City(o.getString("name"), region, o.getDouble("latitude"), o.getDouble("longitude"))
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            return resp.body?.string() ?: throw Exception("empty body")
        }
    }

    // ── 날씨 코드 → 이모지 + 한글 ──
    fun describe(code: Int): Pair<String, String> = when (code) {
        0 -> "☀️" to "맑음"
        1 -> "🌤️" to "대체로 맑음"
        2 -> "⛅" to "구름 조금"
        3 -> "☁️" to "흐림"
        45, 48 -> "🌫️" to "안개"
        51 -> "🌦️" to "약한 이슬비"
        61, 63 -> "🌧️" to "비"
        65 -> "🌧️" to "강한 비"
        71, 73 -> "🌨️" to "눈"
        75 -> "❄️" to "강한 눈"
        80, 81 -> "🌦️" to "소나기"
        95 -> "⛈️" to "뇌우"
        else -> "🌡️" to "—"
    }

    // ── 자외선 등급 (WHO 기준) → (라벨, 색) ──
    fun uviGrade(v: Double?): Pair<String, Long> {
        if (v == null) return "--" to 0xFF8B94A3
        val label = when {
            v < 3  -> "${v.toInt()} 낮음"
            v < 6  -> "${v.toInt()} 보통"
            v < 8  -> "${v.toInt()} 높음"
            v < 11 -> "${v.toInt()} 매우높음"
            else   -> "${v.toInt()} 위험"
        }
        val color = when {
            v < 3  -> 0xFF4C8BF5L
            v < 6  -> 0xFF1DB954L
            v < 8  -> 0xFFFFB000L
            v < 11 -> 0xFFFF7A00L
            else   -> 0xFFFF4D4DL
        }
        return label to color
    }

    // ── 미세먼지 등급 (한국 환경부 기준) → (라벨, 색) ──
    fun pmGrade(type: String, v: Double?): Pair<String, Long> {
        if (v == null) return "--" to 0xFF8B94A3
        val table = if (type == "pm10")
            listOf(30.0 to ("좋음" to 0xFF4C8BF5L), 80.0 to ("보통" to 0xFF1DB954L),
                   150.0 to ("나쁨" to 0xFFFFB000L), Double.MAX_VALUE to ("매우나쁨" to 0xFFFF4D4DL))
        else
            listOf(15.0 to ("좋음" to 0xFF4C8BF5L), 35.0 to ("보통" to 0xFF1DB954L),
                   75.0 to ("나쁨" to 0xFFFFB000L), Double.MAX_VALUE to ("매우나쁨" to 0xFFFF4D4DL))
        for ((lim, pair) in table) if (v <= lim) return "${v.toInt()} ${pair.first}" to pair.second
        return "--" to 0xFF8B94A3
    }
}
