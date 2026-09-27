package com.carmode.launcher

import android.app.Activity
import android.graphics.Rect
import android.os.Bundle

/**
 * 지도앱 '크게 보기' 중간 화면.
 * 이미 보이는 자유 창은 새 크기를 줘도 화면이 카드 크기로 잘린 채 남는다(이 롬의 데스크톱 창 관리 동작).
 * 그래서 이 빈 화면을 잠깐 띄워 지도앱을 숨긴 뒤, 전체 크기로 다시 띄우고 곧바로 닫는다.
 */
class MapMaximizeActivity : Activity() {

    companion object {
        const val EXTRA_PKG = "pkg"
        const val EXTRA_BOUNDS = "bounds"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra(EXTRA_PKG)
        @Suppress("DEPRECATION")
        val bounds = intent.getParcelableExtra<Rect>(EXTRA_BOUNDS)
        window.decorView.post {
            if (pkg != null && bounds != null) FreeformDock.dockTo(this, pkg, bounds)
            finish()
            overridePendingTransition(0, 0)
        }
    }
}
