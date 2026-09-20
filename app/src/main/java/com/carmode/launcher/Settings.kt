package com.carmode.launcher

import android.content.Context

/** 설정 영속화: 타일 구성, 위젯 위치, 날씨 위치 모드 */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("carmode", Context.MODE_PRIVATE)

    // ── 타일 구성 (6칸, 빈 칸은 빈 문자열) ──
    fun getSlots(): MutableList<String> {
        val raw = prefs.getString("slots", DEFAULT_SLOTS) ?: DEFAULT_SLOTS
        return raw.split(",").toMutableList().also {
            while (it.size < SLOT_COUNT) it.add("")
        }.take(SLOT_COUNT).toMutableList()
    }

    fun saveSlots(slots: List<String>) {
        prefs.edit().putString("slots", slots.joinToString(",")).apply()
    }

    // ── 위젯 패널 위치 (left / right) ──
    var widgetSide: String
        get() = prefs.getString("side", "left") ?: "left"
        set(v) = prefs.edit().putString("side", v).apply()

    // ── 지정 음악 앱 패키지명 ──
    var musicPackage: String
        get() = prefs.getString("musicPkg", "") ?: ""
        set(v) = prefs.edit().putString("musicPkg", v).apply()

    // ── 날씨 표시 항목 선택 ──
    var wxItems: Set<String>
        get() {
            val s = prefs.getString("wxItems", null) ?: return ALL_WX_ITEMS.toSet()
            return s.split(",").filter { it.isNotEmpty() }.toSet()
        }
        set(v) = prefs.edit().putString("wxItems", v.joinToString(",")).apply()

    // ── 화면 항상 켜기 ──
    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keepScreenOn", true)
        set(v) = prefs.edit().putBoolean("keepScreenOn", v).apply()

    // ── 날씨 위치 모드 (gps / city) ──
    var locMode: String
        get() = prefs.getString("locMode", "gps") ?: "gps"
        set(v) = prefs.edit().putString("locMode", v).apply()

    // 고정 도시 (위도, 경도, 이름)
    var cityName: String
        get() = prefs.getString("cityName", "") ?: ""
        set(v) = prefs.edit().putString("cityName", v).apply()

    var cityLat: Double
        get() = prefs.getFloat("cityLat", 0f).toDouble()
        set(v) = prefs.edit().putFloat("cityLat", v.toFloat()).apply()

    var cityLon: Double
        get() = prefs.getFloat("cityLon", 0f).toDouble()
        set(v) = prefs.edit().putFloat("cityLon", v.toFloat()).apply()

    companion object {
        const val SLOT_COUNT = 6
        // 기본 타일: 6칸 모두 빈칸 (쉼표 5개 = 빈 문자열 6개)
        const val DEFAULT_SLOTS = ",,,,,"
        val ALL_WX_ITEMS = listOf("feels","hum","wind","rain","max","min","pm10","pm25","uvi")
        val WX_ITEM_LABELS = mapOf(
            "feels" to "체감온도", "hum" to "습도", "wind" to "바람",
            "rain" to "강수확률", "max" to "최고기온", "min" to "최저기온",
            "pm10" to "미세먼지", "pm25" to "초미세먼지", "uvi" to "자외선"
        )
    }
}
