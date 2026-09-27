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
 * 테마2 — 좌측 지도 카드 + 우측(상단바·날씨·퀵실행·음악).
 * 지도 카드는 지도앱(기본 티맵)을 자유 창으로 띄워 카드 위치에 붙인다(FreeformDock).
 */
class Theme2Activity : AppCompatActivity() {

    private lateinit var b: ActivityTheme2Binding
    private lateinit var settings: Settings
    private val ui = CoroutineScope(Dispatchers.Main)
    private val clockHandler = Handler(Looper.getMainLooper())
    private val mediaHandler = Handler(Looper.getMainLooper())
    private var activeController: MediaController? = null

    private var editing = false
    private var skipNextDock = false  // 지도앱 '크게 보기' 직후 카드 복귀 1회 건너뛰기
    private var slots = mutableListOf<String>()


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
        setupMap()
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

    // 편집 모드: 타일 길게 눌러 진입, 타일 영역 바깥 터치로 종료
    private val editHelper by lazy { EditModeHelper(this) }

    private fun toggleEdit() {
        editing = !editing
        editHelper.showHint(editing, b.t2Grid)
        renderTiles()
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (editHelper.handleTouch(ev, editing, b.t2Grid) { toggleEdit() }) return true
        return super.dispatchTouchEvent(ev)
    }

    override fun onPause() {
        super.onPause()
        if (editing) toggleEdit()  // 다른 화면으로 가면 편집 종료
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
        // 홈 복귀 시 자유 창이 숨겨지므로 카드 크기가 정해진 뒤 지도앱을 다시 붙인다.
        // 단, '크게 보기' 직후 중간 화면이 닫히며 돌아온 경우는 건너뛴다(다음 홈 버튼부터 카드 복귀).
        if (skipNextDock) { skipNextDock = false; return }
        b.t2MapCard.post { dockMap() }
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
                if (loc != null) ui.launch {
                    loadWeather(loc.latitude, loc.longitude, placeName(loc.latitude, loc.longitude))
                }
                else loadWeather(37.5665, 126.9780, "서울")
            }.addOnFailureListener { loadWeather(37.5665, 126.9780, "서울") }
        } catch (e: SecurityException) { loadWeather(37.5665, 126.9780, "서울") }
    }

    /** GPS 좌표 → 동/구 이름 */
    @Suppress("DEPRECATION")
    private suspend fun placeName(lat: Double, lon: Double): String =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                val a = android.location.Geocoder(this@Theme2Activity, Locale.KOREA)
                    .getFromLocation(lat, lon, 1)?.firstOrNull()
                listOfNotNull(a?.subLocality, a?.locality, a?.subAdminArea, a?.adminArea)
                    .firstOrNull() ?: "현재 위치"
            } catch (e: Exception) { "현재 위치" }
        }

    /** 날씨 카드: 현재 / 내일 / 모레 3칸 */
    private fun loadWeather(lat: Double, lon: Double, label: String) {
        b.t2WxLoc.text = label
        ui.launch {
            val w = WeatherApi.fetch(lat, lon)
            val days = WeatherApi.fetchWeekly(lat, lon)
            if (w == null && days.isEmpty()) { b.t2WxLoc.text = "$label · 날씨 불러오기 실패"; return@launch }
            b.t2WxDays.removeAllViews()
            // 현재
            addDayCol("현재",
                WeatherApi.describe(w?.code ?: days.getOrNull(0)?.code ?: 0).first,
                w?.let { "${it.temp}°" } ?: "--°",
                days.getOrNull(0)?.rainProb ?: w?.rainProb)
            // 내일, 모레
            listOf(1 to "내일", 2 to "모레").forEach { (i, name) ->
                val d = days.getOrNull(i)
                addDayCol(name,
                    d?.let { WeatherApi.describe(it.code).first } ?: "—",
                    d?.let { "${it.tMax}°/${it.tMin}°" } ?: "--°",
                    d?.rainProb)
            }
        }
    }

    private fun addDayCol(label: String, icon: String, temp: String, rain: Int?) {
        val col = LayoutInflater.from(this).inflate(R.layout.wx_day_col, b.t2WxDays, false)
        col.findViewById<TextView>(R.id.dayLabel).text = label
        col.findViewById<TextView>(R.id.dayIcon).text = icon
        col.findViewById<TextView>(R.id.dayTemp).text = temp
        col.findViewById<TextView>(R.id.dayRain).text = "💧${rain ?: "--"}%"
        b.t2WxDays.addView(col)
    }

    // ───────────────────── 퀵실행 타일 ─────────────────────
    private fun renderTiles() {
        val grid = b.t2Grid
        val cols = settings.tileCols.coerceIn(1, Settings.MAX_COLS)
        // 그리드 실제 크기를 측정한 뒤(post) 세로 공간에 맞춰 줄 수를 자동 결정한다.
        grid.post {
            val gw = grid.width
            val gh = grid.height
            if (gw <= 0 || gh <= 0) return@post
            val cell = gw / cols                              // 한 칸 너비(정사각형 기준)
            if (cell <= 0) return@post
            // 세로 공간을 채우도록 줄 수 결정(칸이 정사각형에 가깝게, 화면 클수록 줄 증가)
            // 내림: 정사각형 칸이 온전히 들어가는 줄 수만 사용(반올림하면 칸이 납작해져 아이콘이 잘림)
            val rows = (gh / cell).coerceIn(1, Settings.MAX_ROWS)
            val n = cols * rows
            slots = settings.getSlots(n)
            grid.removeAllViews()
            grid.columnCount = cols
            grid.rowCount = rows
            for (i in 0 until n) {
                val key = slots.getOrElse(i) { "" }
                val entry = AppCatalog.byKey(key) ?: if (key.contains('.')) entryFromPackage(key) else null
                val view = if (entry != null) buildTile(entry, i) else buildEmpty(i)
                val lp = GridLayout.LayoutParams().apply {
                    width = 0; height = 0
                    columnSpec = GridLayout.spec(i % cols, 1f)   // 가로 균등 분할
                    rowSpec = GridLayout.spec(i / cols, 1f)      // 세로 균등 분할(공간 채움)
                    setMargins(dp(5), dp(5), dp(5), dp(5))
                }
                grid.addView(view, lp)
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
        // 지도 카드엔 지도앱 자유 창이 떠 있어 다이얼로그를 가리므로, 카드와 안 겹치는 오른쪽에 띄운다
        val loc = IntArray(2); b.t2MapCard.getLocationOnScreen(loc)
        val cardRight = loc[0] + b.t2MapCard.width
        val w = (resources.displayMetrics.widthPixels - cardRight - dp(8)).coerceAtLeast(dp(280))
        dialog.window?.apply {
            setGravity(android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL)
            setBackgroundDrawableResource(R.color.bg)  // 제목/버튼 줄도 배경을 채워 뒤 화면과 안 겹치게
            setLayout(w, (resources.displayMetrics.heightPixels * 0.92).toInt())
        }
    }

    private fun launchApp(entry: AppEntry) {
        // 지도앱 타일: 자유 창을 유지한 채 화면 전체 크기로 키움(홈으로 오면 onResume 에서 카드로 복귀).
        // 전체화면 모드로 열면 자유 창으로 되돌릴 수 없어서 이렇게 한다.
        if (entry.packageName.isNotEmpty() &&
            entry.packageName == FreeformDock.mapPackage(this, settings) &&
            FreeformDock.isEnabled(this)) {
            FreeformDock.boundsOf(b.root)?.let {
                skipNextDock = true
                startActivity(Intent(this, MapMaximizeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .putExtra(MapMaximizeActivity.EXTRA_PKG, entry.packageName)
                    .putExtra(MapMaximizeActivity.EXTRA_BOUNDS, it))
            }
            return
        }
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

    // ───────────────────── 지도 카드 (지도앱 자유 창) ─────────────────────
    private fun setupMap() {
        b.t2MapCard.setOnClickListener { dockMap() }
        b.t2MapReload.setOnClickListener { dockMap() }
    }

    private fun dockMap() {
        if (!FreeformDock.isEnabled(this)) {
            b.t2MapHintText.text = "자유 창이 꺼져 있습니다\n설정 → 테마 → 자유 창에서 켜는 방법을 확인하세요"
            return
        }
        val pkg = FreeformDock.mapPackage(this, settings)
        if (pkg == null) {
            b.t2MapHintText.text = "지도앱이 없습니다\n설정 → 테마2 지도앱에서 선택하세요"
            return
        }
        b.t2MapHintText.text = "지도를 불러오는 중…\n(탭하면 다시 띄움)"
        if (!FreeformDock.dock(this, pkg, b.t2MapCard)) {
            b.t2MapHintText.text = "지도앱을 띄우지 못했습니다\n탭해서 다시 시도"
        }
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
    }
}
