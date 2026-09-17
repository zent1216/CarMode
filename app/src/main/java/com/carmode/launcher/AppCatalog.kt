package com.carmode.launcher

/** 타일에 넣을 수 있는 앱 후보 정의 */
data class AppEntry(
    val key: String,
    val name: String,
    val icon: String,        // 이모지 아이콘 (packageName이 있으면 무시됨)
    val sub: String,         // 분류 설명
    val accent: Long,        // 강조색 (0xFFRRGGBB)
    val uri: String,         // 실행 딥링크
    val packageName: String = ""  // 설치된 앱 패키지명 (설정 시 실제 아이콘 사용)
)

object AppCatalog {
    val apps: List<AppEntry> = listOf(
        AppEntry("tmap",    "티맵",       "🧭", "내비게이션", 0xFFFFB000, "tmap://"),
        AppEntry("knavi",   "카카오내비", "🚗", "내비게이션", 0xFFFFB000, "kakaonavi-sdk://"),
        AppEntry("melon",   "멜론",       "🎵", "음악",       0xFF1DB954, "melonapp://"),
        AppEntry("ytmusic", "유튜브뮤직", "🎧", "음악",       0xFFFF0000, "youtubemusic://"),
        AppEntry("spotify", "스포티파이", "🟢", "음악",       0xFF1DB954, "spotify://"),
        AppEntry("kmap",    "카카오맵",   "🗺️", "지도",       0xFF4C8BF5, "kakaomap://look"),
        AppEntry("gmap",    "구글지도",   "📍", "지도",       0xFF4C8BF5, "comgooglemaps://"),
        AppEntry("youtube", "유튜브",     "▶️", "영상",       0xFFFF4D4D, "vnd.youtube://"),
        AppEntry("netflix", "넷플릭스",   "🎬", "영상",       0xFFE50914, "nflx://"),
        AppEntry("phone",   "전화",       "📞", "통화",       0xFF3FD0C9, "tel:"),
        AppEntry("message", "메시지",     "💬", "문자",       0xFF3FD0C9, "sms:"),
        AppEntry("radio",   "라디오",     "📻", "방송",       0xFFCAA24A, "")
    )

    fun byKey(key: String?): AppEntry? = apps.firstOrNull { it.key == key }

    // 멜론/유튜브뮤직처럼 스킴이 불안정한 앱은 패키지명으로도 실행 시도
    val packageFallback: Map<String, String> = mapOf(
        "melon"   to "com.iloen.melon",
        "ytmusic" to "com.google.android.apps.youtube.music",
        "radio"   to "com.skb.smartfree"
    )
}
