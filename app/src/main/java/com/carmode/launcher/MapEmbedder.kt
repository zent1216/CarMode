package com.carmode.launcher

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 서드파티 지도앱을 "화면 안에서 실제로 구동"시키기 위한 우회 임베더.
 *
 * 원리:
 *  1) DisplayManager 로 가상 디스플레이(VirtualDisplay)를 만들고, 그 출력면(Surface)을
 *     테마2의 지도 카드 SurfaceView 에 연결한다.
 *  2) 지도앱 액티비티를 그 가상 디스플레이 위에서 실행 → 지도앱이 우리 카드 안에 렌더링된다.
 *     - 일반 경로: ActivityOptions.setLaunchDisplayId() (신뢰 디스플레이일 때만 동작)
 *     - OS 가 서드파티 앱의 비신뢰 디스플레이 실행을 막으면 루트 `am start --display <id>` 로 우회
 *  3) 터치는 우리 SurfaceView 가 먹은 뒤 좌표를 가상 디스플레이로 재주입한다.
 *     - 루트 `input -d <id>` (tap/swipe) 로 전달
 *
 * 루트가 없으면 렌더링까지는 될 수 있으나(신뢰 디스플레이 실패 시) 서드파티 앱 실행/터치가
 * 막힐 수 있어, 호출측에서 실패를 감지하면 분할화면/전체실행으로 폴백한다.
 */
class MapEmbedder(private val context: Context) {

    companion object {
        private const val TAG = "MapEmbedder"
        // android.hardware.display.DisplayManager 의 숨김 플래그 상수 값 (AOSP 고정값)
        private const val FLAG_PUBLIC = 1 shl 0                 // VIRTUAL_DISPLAY_FLAG_PUBLIC
        private const val FLAG_PRESENTATION = 1 shl 1           // VIRTUAL_DISPLAY_FLAG_PRESENTATION
        private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3       // VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        private const val FLAG_SHOW_SYSTEM_DECORATIONS = 1 shl 9 // 숨김: SHOULD_SHOW_SYSTEM_DECORATIONS
        private const val FLAG_TRUSTED = 1 shl 10               // 숨김: VIRTUAL_DISPLAY_FLAG_TRUSTED
    }

    private var virtualDisplay: VirtualDisplay? = null
    private val io = CoroutineScope(Dispatchers.IO)

    var displayId: Int = -1
        private set

    val isRunning: Boolean get() = virtualDisplay != null

    /**
     * 가상 디스플레이 생성 + 지도앱 실행.
     * @return 렌더링 파이프라인이 뜨면 true. (실제 서드파티 앱 실행 성공까지 보장하진 않음)
     */
    fun start(surface: Surface, width: Int, height: Int, densityDpi: Int, mapPackage: String): Boolean {
        if (width <= 0 || height <= 0 || mapPackage.isEmpty()) return false
        stop()  // 재시작 안전

        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

        // 신뢰 디스플레이를 먼저 시도(루트/시그니처면 통과), 실패 시 공개 디스플레이로 폴백
        val trustedFlags = FLAG_PUBLIC or FLAG_PRESENTATION or FLAG_OWN_CONTENT_ONLY or
            FLAG_TRUSTED or FLAG_SHOW_SYSTEM_DECORATIONS
        val publicFlags = FLAG_PUBLIC or FLAG_PRESENTATION or FLAG_OWN_CONTENT_ONLY

        virtualDisplay = try {
            dm.createVirtualDisplay("CarModeMap", width, height, densityDpi, surface, trustedFlags)
        } catch (e: Throwable) {
            Log.w(TAG, "trusted VD 실패, public 으로 폴백: ${e.message}")
            try {
                dm.createVirtualDisplay("CarModeMap", width, height, densityDpi, surface, publicFlags)
            } catch (e2: Throwable) {
                Log.e(TAG, "가상 디스플레이 생성 실패: ${e2.message}")
                null
            }
        } ?: return false

        displayId = virtualDisplay!!.display.displayId
        launchMapOnDisplay(mapPackage, displayId)
        return true
    }

    private fun launchMapOnDisplay(mapPackage: String, dispId: Int) {
        val launch = context.packageManager.getLaunchIntentForPackage(mapPackage)?.apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
        }
        val comp = launch?.component

        // 1) 표준 경로: setLaunchDisplayId (신뢰 디스플레이면 그 위에 뜸)
        var standardOk = false
        if (launch != null) {
            try {
                val opts = ActivityOptions.makeBasic().setLaunchDisplayId(dispId)
                context.startActivity(launch, opts.toBundle())
                standardOk = true
            } catch (e: Throwable) {
                Log.w(TAG, "표준 실행 실패: ${e.message}")
            }
        }

        // 2) 루트 우회: am start --display <id> (비신뢰 디스플레이여도 강제 실행)
        //    표준 경로가 됐어도, 비신뢰 디스플레이면 실제로는 기본 화면에 떴을 수 있어
        //    루트가 있으면 항상 한 번 더 지정 디스플레이로 못 박는다.
        if (comp != null && PrivShell.available()) {
            io.launch {
                // 표준 실행이 반영될 시간을 살짝 준 뒤 상위 권한으로 확정
                Thread.sleep(if (standardOk) 400 else 0)
                val cn = comp.flattenToShortString()
                PrivShell.exec(
                    "am start --display $dispId -n $cn " +
                        "--activity-multiple-task --activity-new-task"
                )
            }
        }
    }

    /** 지도앱을 지정 디스플레이로 (재)실행 — 사용자가 재시작 버튼 눌렀을 때 */
    fun relaunch(mapPackage: String) {
        if (displayId >= 0 && mapPackage.isNotEmpty()) launchMapOnDisplay(mapPackage, displayId)
    }

    // ───────────────────── 터치 전달 ─────────────────────

    /**
     * SurfaceView 좌표(뷰 로컬 px)를 받아 가상 디스플레이로 주입.
     * DOWN→MOVE...→UP 을 swipe 로 묶어 드래그(지도 이동/확대)를 최대한 재현.
     */
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var lastX = 0f
    private var lastY = 0f

    fun forwardTouch(e: MotionEvent) {
        if (displayId < 0) return
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; downT = System.currentTimeMillis()
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                // 이동 중간에도 짧은 swipe 로 흘려보내 드래그 느낌을 살림
                val dx = e.x - lastX; val dy = e.y - lastY
                if (dx * dx + dy * dy > 900) { // 30px 이상 이동 시
                    injectSwipe(lastX, lastY, e.x, e.y, 40)
                    lastX = e.x; lastY = e.y
                }
            }
            MotionEvent.ACTION_UP -> {
                val dist = Math.hypot((e.x - downX).toDouble(), (e.y - downY).toDouble())
                val dt = System.currentTimeMillis() - downT
                if (dist < 20 && dt < 400) {
                    injectTap(e.x, e.y)
                } else {
                    injectSwipe(lastX, lastY, e.x, e.y, dt.coerceIn(60, 800).toInt())
                }
            }
        }
    }

    private fun injectTap(x: Float, y: Float) {
        val id = displayId
        io.launch { PrivShell.exec("input -d $id tap ${x.toInt()} ${y.toInt()}") }
    }

    private fun injectSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durMs: Int) {
        val id = displayId
        io.launch {
            PrivShell.exec(
                "input -d $id swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} $durMs"
            )
        }
    }

    fun stop() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        displayId = -1
    }
}
