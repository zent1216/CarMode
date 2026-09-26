package com.carmode.launcher

import android.app.Application
import android.util.Log
import com.kakao.vectormap.KakaoMapSdk

/** 앱 시작 시 카카오맵 SDK 초기화 (네이티브 앱 키는 BuildConfig 로 주입, 공개 저장소엔 미포함) */
class CarModeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            KakaoMapSdk.init(this, BuildConfig.KAKAO_NATIVE_KEY)
        } catch (e: Throwable) {
            Log.e("CarModeApp", "KakaoMapSdk init 실패: ${e.message}", e)
        }
    }
}
