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

    // ───────────────────── 터치 주입 (shell input) ─────────────────────
    private var downX = 0f; private var downY = 0f; private var downT = 0L
    private var lastX = 0f; private var lastY = 0f

    fun forwardTouch(e: MotionEvent) {
        if (displayId < 0) return
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; downT = System.currentTimeMillis(); lastX = e.x; lastY = e.y }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lastX; val dy = e.y - lastY
                if (dx * dx + dy * dy > 900) { inject("input -d $displayId swipe ${lastX.toInt()} ${lastY.toInt()} ${e.x.toInt()} ${e.y.toInt()} 40"); lastX = e.x; lastY = e.y }
            }
            MotionEvent.ACTION_UP -> {
                val dist = Math.hypot((e.x - downX).toDouble(), (e.y - downY).toDouble())
                val dt = System.currentTimeMillis() - downT
                if (dist < 20 && dt < 400) inject("input -d $displayId tap ${e.x.toInt()} ${e.y.toInt()}")
                else inject("input -d $displayId swipe ${lastX.toInt()} ${lastY.toInt()} ${e.x.toInt()} ${e.y.toInt()} ${dt.coerceIn(60, 800)}")
            }
        }
    }

    private fun inject(cmd: String) { io.launch { PrivShell.exec(cmd) } }

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
