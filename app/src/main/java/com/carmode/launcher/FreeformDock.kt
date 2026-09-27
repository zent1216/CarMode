package com.carmode.launcher

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.provider.Settings as AndroidSettings
import android.view.View

/**
 * 지도앱을 안드로이드 기본 '자유 창(freeform)'으로 띄워 지도 카드 위치에 붙인다.
 * 가상 디스플레이·터치 주입이 아니라 진짜 창이라 특수 권한(Shizuku/루트/서명)이 필요 없다.
 * 단, 기기에서 자유 창이 켜져 있어야 한다(개발자 옵션 '자유 형식 창 사용' 또는
 * adb: settings put global enable_freeform_support 1 → 재부팅).
 */
object FreeformDock {

    const val DEFAULT_MAP_PKG = "com.skt.tmap.ku"
    private const val WINDOWING_MODE_FREEFORM = 5

    fun isEnabled(ctx: Context): Boolean =
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT) ||
            AndroidSettings.Global.getInt(ctx.contentResolver, "enable_freeform_support", 0) == 1

    /** 설정의 지도앱, 없으면 설치된 티맵 */
    fun mapPackage(ctx: Context, settings: Settings): String? {
        val pm = ctx.packageManager
        val chosen = settings.mapPackage
        if (chosen.isNotEmpty() && pm.getLaunchIntentForPackage(chosen) != null) return chosen
        return if (pm.getLaunchIntentForPackage(DEFAULT_MAP_PKG) != null) DEFAULT_MAP_PKG else null
    }

    /** 카드 뷰의 화면상 영역에 지도앱 창을 띄운다(이미 실행 중이면 재시작 없이 앞으로). */
    fun dock(activity: Activity, pkg: String, card: View): Boolean {
        val intent = activity.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        val loc = IntArray(2)
        card.getLocationOnScreen(loc)
        if (card.width <= 0 || card.height <= 0) return false
        val bounds = Rect(loc[0], loc[1], loc[0] + card.width, loc[1] + card.height)
        val opts = ActivityOptions.makeBasic().setLaunchBounds(bounds)
        // 창 위치만으론 전체화면으로 뜨는 롬이 있어, 자유 창 모드(5)를 명시한다(숨김 API).
        try {
            org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(
                ActivityOptions::class.java, opts, "setLaunchWindowingMode", WINDOWING_MODE_FREEFORM)
        } catch (_: Throwable) {}
        return try {
            activity.startActivity(intent, opts.toBundle()); true
        } catch (e: Exception) { false }
    }
}
