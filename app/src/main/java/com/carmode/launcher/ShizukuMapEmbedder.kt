package com.carmode.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * Shizuku UserService(신뢰 디스플레이) 기반 지도 임베더.
 *
 * 앱이 아니라 shell(uid 2000) 프로세스에서 신뢰 가상 디스플레이를 만들기 때문에,
 * 서드파티 지도앱의 내부 화면 전환까지 카드 안에 유지된다.
 */
class ShizukuMapEmbedder(private val context: Context) {

    companion object { private const val TAG = "ShizukuMapEmbedder" }

    private val io = CoroutineScope(Dispatchers.IO)
    private var service: IMapUserService? = null
    private var bound = false

    var displayId: Int = -1
        private set

    val isRunning: Boolean get() = service != null && displayId >= 0

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, MapUserService::class.java.name)
    ).daemon(false)
        .processNameSuffix("mapsvc")
        .debuggable(false)
        .version(1)

    private var pending: (() -> Unit)? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = if (binder != null && binder.pingBinder())
                IMapUserService.Stub.asInterface(binder) else null
            bound = service != null
            Log.i(TAG, "UserService 연결됨: bound=$bound")
            pending?.invoke(); pending = null
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "UserService 연결 해제")
            service = null; bound = false; displayId = -1
        }
    }

    /**
     * 지도앱을 카드 안에서 실행. 바인딩이 비동기라 준비되면 onResult(성공여부) 로 알린다.
     */
    fun start(
        surface: Surface, width: Int, height: Int, densityDpi: Int,
        mapPackage: String, onResult: (Boolean) -> Unit
    ) {
        if (width <= 0 || height <= 0 || mapPackage.isEmpty() || !surface.isValid) {
            onResult(false); return
        }
        val component = resolveComponent(mapPackage)
        if (component == null) { onResult(false); return }
        // 좌표 보정용 크기 기록(디스플레이 해상도 = SurfaceView 픽셀 크기)
        viewW = width; viewH = height; dispW = width; dispH = height

        val doStart: () -> Unit = {
            io.launch {
                val svc = service
                if (svc == null) { postResult(onResult, false); return@launch }
                try {
                    val id = svc.createTrustedDisplay(surface, width, height, densityDpi)
                    if (id < 0) { postResult(onResult, false); return@launch }
                    displayId = id
                    val ok = svc.startOnDisplay(id, component)
                    Log.i(TAG, "start id=$id component=$component ok=$ok")
                    postResult(onResult, ok)
                } catch (e: Throwable) {
                    Log.e(TAG, "start 실패: ${e.message}", e)
                    postResult(onResult, false)
                }
            }
        }

        if (service != null) doStart()
        else {
            pending = doStart
            try { Shizuku.bindUserService(userServiceArgs, connection) }
            catch (e: Throwable) { Log.e(TAG, "bindUserService 실패: ${e.message}", e); onResult(false) }
        }
    }

    fun relaunch(mapPackage: String) {
        val svc = service ?: return
        val component = resolveComponent(mapPackage) ?: return
        if (displayId < 0) return
        io.launch { try { svc.startOnDisplay(displayId, component) } catch (_: Throwable) {} }
    }

    // ───────────────────── 터치 주입 (InputManager 직접 주입) ─────────────────────
    // 이벤트 순서 보장 + UI 스레드 차단 방지를 위해 단일 스레드에서 순차 주입.
    private val touchExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var gestureDown = 0L
    // 디스플레이 해상도(가상 디스플레이) / SurfaceView 픽셀 크기가 다르면 좌표 보정
    private var dispW = 0; private var dispH = 0
    private var viewW = 0; private var viewH = 0

    fun forwardTouch(e: MotionEvent) {
        val id = displayId
        val svc = service
        if (id < 0 || svc == null) return
        val action = e.actionMasked
        if (action == MotionEvent.ACTION_DOWN) gestureDown = android.os.SystemClock.uptimeMillis()
        val dt = gestureDown
        // 좌표 보정(현재는 1:1 이지만 안전하게 스케일)
        val sx = if (viewW > 0) dispW.toFloat() / viewW else 1f
        val sy = if (viewH > 0) dispH.toFloat() / viewH else 1f
        val x = (e.x * sx).coerceIn(0f, (dispW - 1).coerceAtLeast(0).toFloat())
        val y = (e.y * sy).coerceIn(0f, (dispH - 1).coerceAtLeast(0).toFloat())
        touchExec.execute {
            try { svc.injectMotion(id, action, x, y, dt) } catch (_: Throwable) {}
        }
    }

    fun stop() {
        try { service?.releaseDisplay() } catch (_: Throwable) {}
        displayId = -1
        if (bound) { try { Shizuku.unbindUserService(userServiceArgs, connection, true) } catch (_: Throwable) {} }
        service = null; bound = false
    }

    private fun resolveComponent(mapPackage: String): String? {
        val launch: Intent = context.packageManager.getLaunchIntentForPackage(mapPackage) ?: return null
        return launch.component?.flattenToShortString()
    }

    private fun postResult(onResult: (Boolean) -> Unit, v: Boolean) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(v) }
    }
}
