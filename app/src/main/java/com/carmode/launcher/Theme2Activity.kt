package com.carmode.launcher

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.telephony.TelephonyManager
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.gridlayout.widget.GridLayout
import com.carmode.launcher.databinding.ActivityTheme2Binding
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 테마2 — 좌측 카드(날씨·시계·퀵실행·음악) + 우측 지도 임베드.
 * 지도 카드는 MapEmbedder 로 지정 지도앱을 화면 안에서 실제 구동한다.
 */
class Theme2Activity : AppCompatActivity() {

    private lateinit var b: ActivityTheme2Binding
    private lateinit var settings: Settings
    private val ui = CoroutineScope(Dispatchers.Main)
    private val clockHandler = Handler(Looper.getMainLooper())
    private val mediaHandler = Handler(Looper.getMainLooper())
    private var activeController: MediaController? = null

    private var editing = false
    private var slots = mutableListOf<String>()

    private val embedder by lazy { MapEmbedder(this) }
    private val shizukuEmbedder by lazy { ShizukuMapEmbedder(this) }
    private var surfaceReady = false

    /** Shizuku(무권한, 신뢰 디스플레이) 경로를 쓸지. 루트가 있으면 기존 경로. */
    private fun useShizuku() = PrivShell.mode() == PrivShell.MODE_SHIZUKU
    private fun embedRunning() = shizukuEmbedder.isRunning || embedder.isRunning
    private fun forwardTouchToActive(e: MotionEvent) {
        if (shizukuEmbedder.isRunning) shizukuEmbedder.forwardTouch(e)
        else if (embedder.isRunning) embedder.forwardTouch(e)
    }
    private fun stopEmbed() { shizukuEmbedder.stop(); embedder.stop() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityTheme2Binding.inflate(layoutInflater)
        setContentView(b.root)
        settings = Settings(this)

        if (settings.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        setupTopButtons()
        setupStatusToggles()
        setupMusicControls()
        setupMapSurface()
        setupMapButtons()
        b.t2Title.isSelected = true

        startClock()
        startMediaPoll()
        refreshWeather()
    }

    // ───────────────────── 상단 버튼 / 테마 전환 ─────────────────────
    private fun setupTopButtons() {
        b.t2Settings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        b.t2AllApps.setOnClickListener {
            startActivity(Intent(this, AllAppsActivity::class.java))
        }
        // 테마2에는 편집 버튼이 없다. 타일을 길게 누르면 편집(삭제) 모드로 전환된다.
    }

    private fun toggleEdit() {
        editing = !editing
        renderTiles()
    }

    override fun onResume() {
        super.onResume()
        // 설정에서 테마1로 바꿨다면 즉시 홈으로 되돌림
        if (settings.themeMode != 2) {
            startActivity(Intent(this, MainActivity::class.java))
            finish(); return
        }
        renderTiles()
        refreshStatusIcons()
        refreshWeather()
        // 임베드가 꺼져 있고 조건이 되면 재개
        maybeStartEmbed()
    }

    // ───────────────────── 상태 토글 ─────────────────────
    private fun setupStatusToggles() {
        b.t2Wifi.setOnClickListener {
            try { startActivity(Intent(AndroidSettings.Panel.ACTION_WIFI)) } catch (_: Exception) {}
        }
        b.t2Bt.setOnClickListener {
            try { startActivity(Intent(AndroidSettings.ACTION_BLUETOOTH_SETTINGS)) } catch (_: Exception) {}
        }
        b.t2Data.setOnClickListener {
            try { startActivity(Intent(AndroidSettings.ACTION_NETWORK_OPERATOR_SETTINGS)) } catch (_: Exception) {}
        }
        refreshStatusIcons()
    }

    private fun refreshStatusIcons() {
        tint(b.t2Wifi, isWifiOn()); tint(b.t2Bt, isBtOn()); tint(b.t2Data, isDataOn())
    }

    private fun tint(v: ImageView, on: Boolean) =
        v.setColorFilter(ContextCompat.getColor(this, if (on) R.color.amber else R.color.text_dim))

    private fun isWifiOn() = try {
        (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
    } catch (e: Exception) { false }

    private fun isBtOn() = try {
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        bm.adapter?.isEnabled == true
    } catch (e: Exception) { false }

    private fun isDataOn() = try {
        (getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).isDataEnabled
    } catch (e: Exception) { false }

    // ───────────────────── 시계 ─────────────────────
    private fun startClock() {
        val tick = object : Runnable {
            override fun run() {
                val c = Calendar.getInstance()
                val t = SimpleDateFormat("a h:mm", Locale.KOREA).format(c.time)
                b.t2TopTime.text = t
                refreshStatusIcons()
                clockHandler.postDelayed(this, 1000)
            }
        }
        clockHandler.post(tick)
    }

    // ───────────────────── 날씨 ─────────────────────
    private fun refreshWeather() {
        if (settings.locMode == "city" && settings.cityName.isNotEmpty()) {
            loadWeather(settings.cityLat, settings.cityLon, "${settings.cityName}")
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            refreshWeatherByGps()
        } else {
            loadWeather(37.5665, 126.9780, "서울")
        }
    }

    private fun refreshWeatherByGps() {
        try {
            val fused = LocationServices.getFusedLocationProviderClient(this)
            fused.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) loadWeather(loc.latitude, loc.longitude, "현재 위치")
                else loadWeather(37.5665, 126.9780, "서울")
            }.addOnFailureListener { loadWeather(37.5665, 126.9780, "서울") }
        } catch (e: SecurityException) { loadWeather(37.5665, 126.9780, "서울") }
    }

    private fun loadWeather(lat: Double, lon: Double, label: String) {
        b.t2WxLoc.text = label
        ui.launch {
            val w = WeatherApi.fetch(lat, lon) ?: run { b.t2WxDesc.text = "날씨 불러오기 실패"; return@launch }
            val (icon, desc) = WeatherApi.describe(w.code)
            b.t2WxIcon.text = icon
            b.t2WxTemp.text = "${w.temp}°"
            b.t2WxDesc.text = "$desc · 체감 ${w.feels}°"
        }
    }

    // ───────────────────── 퀵실행 타일 ─────────────────────
    private fun renderTiles() {
        val grid = b.t2Grid
        grid.removeAllViews()
        // 테마2 퀵실행은 항상 한 줄. 칸 수만 조정(설정의 가로 개수 사용).
        val cols = settings.tileCols.coerceIn(1, Settings.MAX_COLS)
        grid.columnCount = cols
        grid.rowCount = 1
        slots = settings.getSlots()
        for (i in 0 until cols) {
            val key = slots.getOrElse(i) { "" }
            val entry = AppCatalog.byKey(key) ?: if (key.contains('.')) entryFromPackage(key) else null
            val view = if (entry != null) buildTile(entry, i) else buildEmpty(i)
            val lp = GridLayout.LayoutParams().apply {
                width = 0; height = 0
                columnSpec = GridLayout.spec(i, 1f)
                rowSpec = GridLayout.spec(0)
                setMargins(dp(5), dp(5), dp(5), dp(5))
            }
            grid.addView(view, lp)
        }
        // 셀을 최대한 정사각형에 맞춘다(셀 너비에 맞춰 높이 지정, 과하게 크지 않게 상한).
        grid.post {
            val cw = if (cols > 0) grid.width / cols else 0
            if (cw <= 0) return@post
            val side = (cw - dp(10)).coerceIn(dp(52), dp(104))
            for (idx in 0 until grid.childCount) {
                val child = grid.getChildAt(idx)
                val lp = child.layoutParams as GridLayout.LayoutParams
                lp.height = side
                child.layoutParams = lp
            }
        }
    }

    private fun entryFromPackage(pkg: String): AppEntry? {
        if (pkg.isEmpty()) return null
        return try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            AppEntry(pkg, packageManager.getApplicationLabel(info).toString(), "", "", 0xFF607D8B, "", pkg)
        } catch (e: Exception) { null }
    }

    private fun buildTile(entry: AppEntry, index: Int): View {
        val v = LayoutInflater.from(this).inflate(R.layout.tile, b.t2Grid, false)
        val iconTv = v.findViewById<TextView>(R.id.tileIcon)
        val iconImg = v.findViewById<ImageView>(R.id.tileIconImage)
        if (entry.packageName.isNotEmpty()) {
            iconTv.visibility = View.GONE; iconImg.visibility = View.VISIBLE
            try { iconImg.setImageDrawable(packageManager.getApplicationIcon(entry.packageName)) }
            catch (e: Exception) { iconImg.setImageResource(android.R.drawable.sym_def_app_icon) }
        } else {
            iconTv.visibility = View.VISIBLE; iconImg.visibility = View.GONE; iconTv.text = entry.icon
        }
        // 테마2 퀵실행은 아이콘만 표시(라벨/서브 숨김)
        v.findViewById<TextView>(R.id.tileLabel).visibility = View.GONE
        v.findViewById<TextView>(R.id.tileSub).visibility = View.GONE
        val del = v.findViewById<TextView>(R.id.tileDelete)
        del.visibility = if (editing) View.VISIBLE else View.GONE
        del.setOnClickListener { slots[index] = ""; settings.saveSlots(slots); renderTiles() }
        v.setOnClickListener { if (editing) openAppPicker(index) else launchApp(entry) }
        v.setOnLongClickListener { if (!editing) toggleEdit(); true }
        return v
    }

    private fun buildEmpty(index: Int): View {
        val v = LayoutInflater.from(this).inflate(R.layout.tile_empty, b.t2Grid, false)
        v.setOnClickListener { openAppPicker(index) }
        v.setOnLongClickListener { if (!editing) toggleEdit(); true }
        return v
    }

    private fun openAppPicker(slotIndex: Int) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val installed = packageManager.queryIntentActivities(intent, 0)
            .sortedBy { it.loadLabel(packageManager).toString() }
        val scroll = android.widget.ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setBackgroundColor(ContextCompat.getColor(this@Theme2Activity, R.color.bg))
        }
        scroll.addView(container)
        val dialog = AlertDialog.Builder(this, R.style.Theme_CarMode_Dialog)
            .setTitle("앱 선택").setView(scroll).setNegativeButton("닫기", null).create()
        installed.chunked(4).forEach { rowApps ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            rowApps.forEach { app ->
                val cell = LayoutInflater.from(this).inflate(R.layout.item_app_grid, row, false)
                cell.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                cell.findViewById<ImageView>(R.id.appIcon).setImageDrawable(app.loadIcon(packageManager))
                cell.findViewById<TextView>(R.id.appName).text = app.loadLabel(packageManager)
                cell.setOnClickListener {
                    slots[slotIndex] = app.activityInfo.packageName
                    settings.saveSlots(slots); renderTiles(); dialog.dismiss()
                }
                row.addView(cell)
            }
            repeat(4 - rowApps.size) {
                row.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
            }
            container.addView(row)
        }
        dialog.show()
        dialog.window?.setLayout(resources.displayMetrics.widthPixels * 2 / 3,
            WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun launchApp(entry: AppEntry) {
        if (entry.packageName.isNotEmpty()) {
            packageManager.getLaunchIntentForPackage(entry.packageName)?.let { startActivity(it); return }
            Toast.makeText(this, "${entry.name} 앱을 열 수 없습니다", Toast.LENGTH_SHORT).show(); return
        }
        if (entry.uri.isNotEmpty()) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.uri))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return
            } catch (_: Exception) {}
        }
        val pkg = AppCatalog.packageFallback[entry.key]
        if (pkg != null) {
            packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it); return }
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))); return }
            catch (_: Exception) {}
        }
        Toast.makeText(this, "${entry.name} 앱을 열 수 없습니다", Toast.LENGTH_SHORT).show()
    }

    // ───────────────────── 지도 임베드 ─────────────────────
    private fun setupMapSurface() {
        b.t2MapSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true }
            override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {
                surfaceReady = true
                if (!embedRunning()) maybeStartEmbed()
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false; stopEmbed()
            }
        })
        // 지도 카드 터치 → 가상 디스플레이로 전달
        b.t2MapSurface.setOnTouchListener { _, e ->
            if (embedRunning()) { forwardTouchToActive(e); true } else false
        }
    }

    private fun setupMapButtons() {
        b.t2MapStart.setOnClickListener { startEmbedOrGuide() }
        b.t2MapReload.setOnClickListener {
            if (shizukuEmbedder.isRunning) shizukuEmbedder.relaunch(settings.mapPackage)
            else embedder.relaunch(settings.mapPackage)
        }
    }

    private fun maybeStartEmbed() {
        if (embedRunning()) {              // 이미 임베드 중이면 재생성 금지(튕김 방지)
            b.t2MapHint.visibility = View.GONE
            b.t2MapReload.visibility = View.VISIBLE
            return
        }
        if (!settings.mapAutoStart) { showHint(); return }
        if (settings.mapPackage.isEmpty()) { showHint(); return }
        if (!PrivShell.available()) { showHint(); return }
        startEmbed()
    }

    private fun startEmbedOrGuide() {
        when {
            settings.mapPackage.isEmpty() -> {
                Toast.makeText(this, "설정 > 테마2 지도앱을 먼저 지정하세요", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            !PrivShell.available() -> showPrivGuide()
            else -> startEmbed()
        }
    }

    private fun startEmbed() {
        if (embedRunning()) return          // 중복 시작 방지
        val pkg = settings.mapPackage
        if (pkg.isEmpty() || !surfaceReady) return
        val surface = b.t2MapSurface.holder.surface
        val w = b.t2MapSurface.width; val h = b.t2MapSurface.height
        if (!surface.isValid || w <= 0 || h <= 0) return
        val dpi = resources.displayMetrics.densityDpi

        if (useShizuku()) {
            // Shizuku: shell 프로세스에서 신뢰 디스플레이 생성(비동기)
            b.t2MapHintText.text = "지도 불러오는 중…"
            shizukuEmbedder.start(surface, w, h, dpi, pkg) { ok ->
                if (ok) {
                    b.t2MapHint.visibility = View.GONE
                    b.t2MapReload.visibility = View.VISIBLE
                } else showHint()
            }
        } else {
            val ok = embedder.start(surface, w, h, dpi, pkg)
            if (ok) {
                b.t2MapHint.visibility = View.GONE
                b.t2MapReload.visibility = View.VISIBLE
            } else showHint()
        }
    }

    private fun showHint() {
        b.t2MapHint.visibility = View.VISIBLE
        b.t2MapReload.visibility = View.GONE
        b.t2MapHintText.text = when {
            settings.mapPackage.isEmpty() ->
                "설정 > 테마2 에서 지도앱을 먼저 지정하세요."
            !PrivShell.available() ->
                "지도앱을 화면 안에서 구동하려면 루트 또는 Shizuku 권한이 필요합니다.\n'지도 실행'을 눌러 안내를 확인하세요."
            else -> "‘지도 실행’을 누르면 지정한 지도앱이 이 카드 안에서 실행됩니다."
        }
    }

    /** 루트·Shizuku 둘 다 없을 때 안내 + 분할화면 폴백 */
    private fun showPrivGuide() {
        val alive = PrivShell.shizukuAlive()
        val msg = if (alive)
            "Shizuku 가 실행 중이지만 이 앱에 권한이 없습니다.\n권한을 허용하시겠습니까?"
        else
            "화면 안 지도 구동에는 루트 또는 Shizuku 가 필요합니다.\n\n" +
            "Shizuku(무료)를 설치하고 '무선 디버깅'으로 실행하면 루팅 없이 사용할 수 있습니다.\n\n" +
            "지금은 지도앱을 분할화면으로 실행할 수 있습니다."
        val builder = AlertDialog.Builder(this, R.style.Theme_CarMode_Dialog)
            .setTitle("지도 임베드 권한")
            .setMessage(msg)
            .setNegativeButton("분할화면으로 실행") { _, _ -> launchMapAdjacent() }
        if (alive) {
            builder.setPositiveButton("권한 요청") { _, _ ->
                try { rikka.shizuku.Shizuku.requestPermission(1001) } catch (_: Throwable) {}
            }
        } else {
            builder.setPositiveButton("확인", null)
        }
        builder.show()
    }

    /** 폴백: 분할화면 인접칸으로 지도앱 실행 */
    private fun launchMapAdjacent() {
        val pkg = settings.mapPackage
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: run {
            Toast.makeText(this, "지도앱을 열 수 없습니다", Toast.LENGTH_SHORT).show(); return
        }
        launch.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
            Intent.FLAG_ACTIVITY_MULTIPLE_TASK
        )
        try { startActivity(launch) }
        catch (e: Exception) { Toast.makeText(this, "분할화면 실행 실패", Toast.LENGTH_SHORT).show() }
    }

    // ───────────────────── 음악 위젯 ─────────────────────
    private fun setupMusicControls() {
        b.t2Play.setOnClickListener {
            val ctrl = activeController ?: run { openMusicApp(); return@setOnClickListener }
            if (ctrl.playbackState?.state == PlaybackState.STATE_PLAYING)
                ctrl.transportControls.pause() else ctrl.transportControls.play()
        }
        b.t2Prev.setOnClickListener { activeController?.transportControls?.skipToPrevious() ?: openMusicApp() }
        b.t2Next.setOnClickListener { activeController?.transportControls?.skipToNext() ?: openMusicApp() }
        val open = View.OnClickListener {
            val ctrl = activeController
            if (ctrl != null) packageManager.getLaunchIntentForPackage(ctrl.packageName)?.let { startActivity(it) }
            else openMusicApp()
        }
        b.t2Art.setOnClickListener(open); b.t2Title.setOnClickListener(open); b.t2Artist.setOnClickListener(open)
    }

    private fun startMediaPoll() {
        val tick = object : Runnable {
            override fun run() { updateMusicWidget(); mediaHandler.postDelayed(this, 1000) }
        }
        mediaHandler.post(tick)
    }

    private fun updateMusicWidget() {
        if (!isNlsGranted()) {
            b.t2Title.text = "알림 접근 권한 필요"; b.t2Artist.text = "설정 → 음악"
            b.t2Play.setImageResource(R.drawable.ic_play); b.t2Art.setImageDrawable(null); return
        }
        try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val controllers = msm.getActiveSessions(ComponentName(this, NLService::class.java))
            val ctrl = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: controllers.firstOrNull()
            activeController = ctrl
            if (ctrl == null) {
                b.t2Title.text = "재생 중인 곡 없음"; b.t2Artist.text = "음악 앱을 실행하세요"
                b.t2Art.setImageDrawable(null); b.t2Play.setImageResource(R.drawable.ic_play); return
            }
            val meta = ctrl.metadata; val state = ctrl.playbackState
            b.t2Title.text = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "제목 없음"
            b.t2Artist.text = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
            val art = meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            if (art != null) b.t2Art.setImageBitmap(art) else b.t2Art.setImageDrawable(null)
            b.t2Play.setImageResource(
                if (state?.state == PlaybackState.STATE_PLAYING) R.drawable.ic_pause else R.drawable.ic_play)
        } catch (e: SecurityException) {
            b.t2Title.text = "알림 접근 권한 필요"; b.t2Artist.text = "설정 → 음악"
        }
    }

    private fun isNlsGranted(): Boolean {
        val flat = AndroidSettings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(packageName) == true
    }

    private fun openMusicApp() {
        val pkg = settings.musicPackage
        if (pkg.isNotEmpty()) packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it); return }
        Toast.makeText(this, "설정에서 음악 앱을 지정하세요", Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        clockHandler.removeCallbacksAndMessages(null)
        mediaHandler.removeCallbacksAndMessages(null)
        stopEmbed()
    }
}
