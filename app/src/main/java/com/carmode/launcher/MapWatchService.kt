package com.carmode.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * 테마2 보조 — 지도앱(티맵)이 '다른 앱 위에서' 스스로 앞으로 나오는 경우를 감지해 전체 크기로 키운다.
 *
 * 예) 음악 앱 사용 중 티맵의 플로팅 TBT를 누르면, 티맵이 마지막 크기(지도 카드 크기) 자유 창으로 떠서
 *     음악 화면 일부만 가린다. 이때 곧바로 '크게 보기'(MapMaximizeActivity)로 전체 크기로 만든다.
 *     홈으로 돌아오면 Theme2Activity 가 다시 카드 크기로 붙인다.
 *
 * 화면 내용은 읽지 않고, 어떤 앱 창이 앞으로 나왔는지(창 상태 변경 이벤트)만 본다.
 */
class MapWatchService : AccessibilityService() {

    private var lastTrigger = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return

        val settings = Settings(this)
        if (settings.themeMode != 2) return
        val mapPkg = FreeformDock.mapPackage(this, settings) ?: return
        if (pkg != mapPkg) return

        // 홈(테마2)이 보이는 중이면 카드 상태가 정상 → 건드리지 않음
        if (Theme2Activity.visible) return
        // 이미 크게 보기 상태거나, 방금 처리했으면 무시(연속 이벤트·루프 방지)
        if (Theme2Activity.mapMaximized) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastTrigger < 2500) return
        if (!FreeformDock.isEnabled(this)) return

        val bounds = Theme2Activity.fullBounds ?: screenBounds() ?: return
        lastTrigger = now
        Theme2Activity.mapMaximized = true
        startActivity(Intent(this, MapMaximizeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(MapMaximizeActivity.EXTRA_PKG, mapPkg)
            .putExtra(MapMaximizeActivity.EXTRA_BOUNDS, bounds))
    }

    /** 홈 화면 영역을 아직 모를 때(앱 재시작 등): 화면 전체에서 상태바·내비바를 뺀 영역 */
    private fun screenBounds(): Rect? = try {
        val wm = getSystemService(WindowManager::class.java)
        val m = wm.maximumWindowMetrics
        val ins = m.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val b = m.bounds
        Rect(b.left + ins.left, b.top + ins.top, b.right - ins.right, b.bottom - ins.bottom)
    } catch (e: Exception) { null }

    override fun onInterrupt() {}
}
