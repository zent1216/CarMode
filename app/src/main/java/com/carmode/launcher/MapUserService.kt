package com.carmode.launcher

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.Surface

/**
 * Shizuku UserService — shell(uid 2000) 프로세스에서 구동된다.
 *
 * shell 은 ADD_TRUSTED_DISPLAY 권한을 가지므로 "신뢰(trusted) 가상 디스플레이"를 만들 수 있다.
 * 앱이 만든 비신뢰 디스플레이와 달리, 신뢰 디스플레이 위에서는 서드파티 지도앱의
 * 내부 화면 전환(Intro→Main 등)까지 카드 안에 머문다.
 *
 * 앱 프로세스에서 이 클래스를 직접 인스턴스화하지 않는다. Shizuku 가 별도 프로세스에서 생성한다.
 */
class MapUserService : IMapUserService.Stub {

    companion object {
        private const val TAG = "MapUserService"
        // DisplayManager 가상 디스플레이 플래그(AOSP 고정값; 일부는 숨김 상수)
        private const val FLAG_PUBLIC = 1 shl 0
        private const val FLAG_PRESENTATION = 1 shl 1
        private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
        private const val FLAG_SHOW_SYSTEM_DECORATIONS = 1 shl 9
        private const val FLAG_TRUSTED = 1 shl 10
    }

    private var vd: VirtualDisplay? = null

    @Suppress("unused")
    constructor() { Log.i(TAG, "MapUserService 생성(no-arg)") }

    @Suppress("unused")
    constructor(context: Context) { Log.i(TAG, "MapUserService 생성(context)") }

    /** system_server 밖(shell 프로세스)에서 Context 를 얻는다. */
    private fun systemContext(): Context {
        val at = Class.forName("android.app.ActivityThread")
        val thread = try {
            at.getMethod("currentActivityThread").invoke(null)
                ?: at.getMethod("systemMain").invoke(null)
        } catch (e: Throwable) {
            at.getMethod("systemMain").invoke(null)
        }
        return at.getMethod("getSystemContext").invoke(thread) as Context
    }

    /**
     * shell(uid 2000) 프로세스이므로, 가상 디스플레이 생성 시 packageName 이 uid 와 일치해야 한다.
     * 시스템 컨텍스트(package="android")로는 uid 불일치가 나므로 com.android.shell 컨텍스트를 쓴다.
     */
    private fun shellContext(): Context = try {
        systemContext().createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY)
    } catch (e: Throwable) {
        Log.w(TAG, "shell 컨텍스트 생성 실패, 시스템 컨텍스트 사용: ${e.message}")
        systemContext()
    }

    override fun createTrustedDisplay(surface: Surface, width: Int, height: Int, densityDpi: Int): Int {
        return try {
            releaseDisplay()
            val dm = shellContext().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            // 풀스크린 룩을 위해 SHOW_SYSTEM_DECORATIONS 는 끈다(상단 알림바/하단 내비바 없음).
            // 대신 중복 생성/다중 태스크를 막아 지도앱이 카드에 머물게 한다.
            val flags = FLAG_PUBLIC or FLAG_PRESENTATION or FLAG_OWN_CONTENT_ONLY or FLAG_TRUSTED
            val d = dm.createVirtualDisplay("CarModeMapTrusted", width, height, densityDpi, surface, flags)
            vd = d
            val id = d?.display?.displayId ?: -1
            Log.i(TAG, "신뢰 디스플레이 생성 id=$id (${width}x$height @${densityDpi})")
            id
        } catch (e: Throwable) {
            Log.e(TAG, "신뢰 디스플레이 생성 실패: ${e.message}", e)
            -1
        }
    }

    override fun startOnDisplay(displayId: Int, component: String): Boolean {
        if (displayId < 0 || component.isEmpty()) return false
        return try {
            // shell 프로세스이므로 am 을 직접 실행.
            // --activity-multiple-task 를 빼서 지도앱 태스크가 새로 쌓이지 않고 재사용되게 한다
            // (기존 태스크를 지정 디스플레이로 이동). NEW_TASK 는 am 기본.
            val p = Runtime.getRuntime().exec(arrayOf(
                "am", "start", "--display", displayId.toString(), "-n", component
            ))
            val code = p.waitFor()
            if (code != 0) {
                val err = p.errorStream.bufferedReader().readText().trim()
                Log.w(TAG, "am start code=$code err=$err")
            }
            code == 0
        } catch (e: Throwable) {
            Log.e(TAG, "startOnDisplay 실패: ${e.message}", e)
            false
        }
    }

    // InputManager (shell 은 INJECT_EVENTS 보유) 로 MotionEvent 직접 주입
    private val inputManager: Any? by lazy {
        try { shellContext().getSystemService("input") } catch (e: Throwable) { null }
    }
    private val injectMethod by lazy {
        try {
            inputManager?.javaClass?.getMethod(
                "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType
            )?.apply { isAccessible = true }
        } catch (e: Throwable) { null }
    }
    private val setDisplayIdMethod by lazy {
        try {
            MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
        } catch (e: Throwable) { null }
    }

    override fun injectMotion(displayId: Int, action: Int, x: Float, y: Float, downTime: Long): Boolean {
        return try {
            val now = SystemClock.uptimeMillis()
            val dt = if (downTime <= 0L) now else downTime
            val ev = MotionEvent.obtain(dt, now, action, x, y, 0)
            ev.source = InputDevice.SOURCE_TOUCHSCREEN
            setDisplayIdMethod?.invoke(ev, displayId)
            val im = inputManager; val inj = injectMethod
            val ok = if (im != null && inj != null)
                (inj.invoke(im, ev, 0) as? Boolean) ?: false else false   // 0 = ASYNC
            ev.recycle()
            ok
        } catch (e: Throwable) {
            Log.w(TAG, "injectMotion 실패: ${e.message}")
            false
        }
    }

    override fun releaseDisplay() {
        try { vd?.release() } catch (_: Throwable) {}
        vd = null
    }

    override fun destroy() {
        Log.i(TAG, "destroy")
        releaseDisplay()
        // shell 프로세스 종료
        System.exit(0)
    }
}
