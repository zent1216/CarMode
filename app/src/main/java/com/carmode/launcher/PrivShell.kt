package com.carmode.launcher

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.DataOutputStream

/**
 * 상위 권한 셸 실행기.
 *
 *  - 루트(su)가 있으면 루트로 실행
 *  - 없으면 Shizuku(무선 디버깅 기반 adb/shell 권한)로 실행
 *  - 둘 다 없으면 실패(false) → 호출측이 폴백
 *
 * 지도앱을 가상 디스플레이 위에서 실행(`am start --display`)하고 터치를
 * 주입(`input -d`)하려면 최소한 shell(adb) 수준 권한이 필요하다.
 * 이는 안드로이드의 보안 경계이므로, 완전 무권한으로는 어떤 우회로도 불가능하다.
 * Shizuku 는 그 권한을 "루팅 없이" 얻는 공식적인 방법이다.
 */
object PrivShell {

    const val MODE_NONE = 0
    const val MODE_ROOT = 1
    const val MODE_SHIZUKU = 2

    @Volatile private var rootChecked = false
    @Volatile private var rootAvail = false

    /** 루트 사용 가능 여부 (1회 캐시) */
    fun hasRoot(): Boolean {
        if (!rootChecked) { rootChecked = true; rootAvail = probeRoot() }
        return rootAvail
    }

    private fun probeRoot(): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        p.waitFor() == 0
    } catch (e: Exception) { false }

    /** Shizuku 바인더 살아있음 (서비스 실행 중) */
    fun shizukuAlive(): Boolean = try { Shizuku.pingBinder() } catch (e: Throwable) { false }

    /** Shizuku 권한 승인됨 */
    fun shizukuGranted(): Boolean = try {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) { false }

    /** 현재 사용할 권한 모드 */
    fun mode(): Int = when {
        hasRoot() -> MODE_ROOT
        shizukuGranted() -> MODE_SHIZUKU
        else -> MODE_NONE
    }

    fun available(): Boolean = mode() != MODE_NONE

    /** 셸 명령 실행. 성공 시 true */
    fun exec(cmd: String): Boolean = when (mode()) {
        MODE_ROOT -> rootExec(cmd)
        MODE_SHIZUKU -> shizukuExec(cmd)
        else -> false
    }

    private fun rootExec(cmd: String): Boolean = try {
        val p = Runtime.getRuntime().exec("su")
        DataOutputStream(p.outputStream).use { os ->
            os.writeBytes(cmd + "\n"); os.writeBytes("exit\n"); os.flush()
        }
        p.waitFor() == 0
    } catch (e: Exception) { false }

    /**
     * Shizuku.newProcess 는 숨김 API 라 리플렉션으로 호출.
     * newProcess(String[] cmd, String[] env, String dir): ShizukuRemoteProcess
     */
    private fun shizukuExec(cmd: String): Boolean = try {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        val proc = m.invoke(null, arrayOf("sh", "-c", cmd), null, null)
        val waitFor = proc.javaClass.getMethod("waitFor")
        (waitFor.invoke(proc) as Int) == 0
    } catch (e: Throwable) { false }
}
