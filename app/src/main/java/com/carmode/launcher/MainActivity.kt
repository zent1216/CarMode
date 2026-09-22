package com.carmode.launcher

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.location.Geocoder
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.net.wifi.WifiManager
import android.telephony.TelephonyManager
import android.provider.Settings as AndroidSettings
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout as WLinearLayout
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.gridlayout.widget.GridLayout
import androidx.viewpager.widget.ViewPager
import androidx.viewpager.widget.PagerAdapter
import com.carmode.launcher.databinding.ActivityMainBinding
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var settings: Settings
    private var editing = false
    private var slots = mutableListOf<String>()

    // 날씨 셀의 값 TextView 보관 (코드 생성)
    private val wxValues = HashMap<String, TextView>()

    // 날씨 페이지(현재/주간) 뷰 참조 — ViewPager 안에 있어 뷰바인딩 대신 직접 보관
    private lateinit var wxNowPage: View
    private lateinit var wxWeekPage: View
    private lateinit var wxIcon: TextView
    private lateinit var wxTemp: TextView
    private lateinit var wxLoc: TextView
    private lateinit var wxDesc: TextView
    private lateinit var wxGrid: WLinearLayout
    private lateinit var wxWeek: WLinearLayout

    private val ui = CoroutineScope(Dispatchers.Main)
    private val clockHandler = Handler(Looper.getMainLooper())
    private val mediaHandler = Handler(Looper.getMainLooper())
    private var activeController: MediaController? = null

    // 위치 권한 요청
    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) refreshWeatherByGps() else loadDefaultCityWeather() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settings = Settings(this)

        // 테마2 선택 시 전용 액티비티로 전환 (홈 진입점은 MainActivity 유지)
        if (settings.themeMode == 2) {
            startActivity(Intent(this, Theme2Activity::class.java))
            finish()
            return
        }

        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        slots = settings.getSlots()

        setupTopButtons()
        setupStatusToggles()
        setupWeatherPager()
        setupWeatherCellLabels()
        setupMusicControls()
        applyScreenFlags()
        applyWidgetSide()
        renderTiles()
        startClock()
        startMediaPoll()
        refreshWeather()

        // 첫 실행 시 필요한 권한 안내
        maybeShowFirstRunPermissions()

        // 새 버전 확인 (있으면 안내)
        checkUpdateOnLaunch()
    }

    private fun checkUpdateOnLaunch() {
        ui.launch {
            val latest = Updater.fetchLatest() ?: return@launch
            val cur = try {
                packageManager.getPackageInfo(packageName, 0).versionName ?: ""
            } catch (e: Exception) { "" }
            if (Updater.isNewer(cur, latest.version)) {
                Toast.makeText(
                    this@MainActivity,
                    "새 버전 v${latest.version} 있음 · 설정 > 업데이트 확인",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ───────────────────── 상단 버튼 ─────────────────────
    private fun setupTopButtons() {
        b.btnEdit.setOnClickListener { toggleEdit() }
        b.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        b.btnAllApps.setOnClickListener {
            startActivity(Intent(this, AllAppsActivity::class.java))
        }
    }

    private fun toggleEdit() {
        editing = !editing
        b.btnEdit.text = if (editing) "완료" else "편집"
        b.btnEdit.setBackgroundResource(
            if (editing) R.drawable.topbtn_bg_on else R.drawable.topbtn_bg
        )
        b.btnEdit.setTextColor(
            ContextCompat.getColor(this, if (editing) R.color.bg else R.color.text_dim)
        )
        renderTiles()
    }

    private fun applyScreenFlags() {
        if (settings.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        // 설정에서 테마2로 바꿨다면 전환
        if (settings.themeMode == 2) {
            startActivity(Intent(this, Theme2Activity::class.java))
            finish(); return
        }
        applyScreenFlags()
        applyWidgetSide()
        setupWeatherCellLabels()
        refreshWeather()
        refreshStatusIcons()
        renderTiles()  // 설정에서 타일 개수 변경 시 반영
        // 권한 설정 화면 다녀온 뒤 체크리스트 갱신
        permRoot?.let { refreshPermRows(it) }
    }

    // ───────────────────── 상태 토글 (와이파이/블루투스/데이터) ─────────────────────
    private fun setupStatusToggles() {
        b.btnWifi.setOnClickListener { toggleWifi() }
        // 블루투스는 기기 연결이 목적이라 블루투스 페이지로 이동
        b.btnBt.setOnClickListener { openBluetoothPage() }
        b.btnData.setOnClickListener { toggleData() }
        refreshStatusIcons()
    }

    private fun refreshStatusIcons() {
        tintIcon(b.btnWifi, isWifiOn())
        tintIcon(b.btnBt, isBtOn())
        tintIcon(b.btnData, isDataOn())
    }

    private fun tintIcon(v: ImageView, on: Boolean) {
        v.setColorFilter(ContextCompat.getColor(this, if (on) R.color.amber else R.color.text_dim))
    }

    private fun isWifiOn(): Boolean = try {
        (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
    } catch (e: Exception) { false }

    private fun isBtOn(): Boolean = try {
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        bm.adapter?.isEnabled == true
    } catch (e: Exception) { false }

    private fun isDataOn(): Boolean = try {
        (getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).isDataEnabled
    } catch (e: Exception) { false }

    /** su 로 명령 실행. 루트 없거나 실패하면 false */
    private fun runRoot(vararg cmds: String): Boolean = try {
        val p = Runtime.getRuntime().exec("su")
        java.io.DataOutputStream(p.outputStream).use { os ->
            cmds.forEach { os.writeBytes(it + "\n") }
            os.writeBytes("exit\n"); os.flush()
        }
        p.waitFor() == 0
    } catch (e: Exception) { false }

    private fun openBluetoothPage() {
        // 이 ROM은 블루투스 전용 페이지가 비공개라, 표준 블루투스/연결된 기기 설정으로 이동
        try { startActivity(Intent(AndroidSettings.ACTION_BLUETOOTH_SETTINGS)) }
        catch (_: Exception) {}
    }

    private fun openPanel(primary: String, fallback: String) {
        try { startActivity(Intent(primary)) }
        catch (_: Exception) { try { startActivity(Intent(fallback)) } catch (_: Exception) {} }
    }

    private fun toggleWifi() {
        val target = !isWifiOn()
        ui.launch {
            val ok = kotlinx.coroutines.withContext(Dispatchers.IO) {
                runRoot("svc wifi ${if (target) "enable" else "disable"}")
            }
            if (!ok) {
                Toast.makeText(this@MainActivity, "루트 없음 · 설정에서 변경", Toast.LENGTH_SHORT).show()
                openPanel(AndroidSettings.Panel.ACTION_WIFI, AndroidSettings.ACTION_WIFI_SETTINGS)
            } else {
                kotlinx.coroutines.delay(700); refreshStatusIcons()
            }
        }
    }

    private fun toggleData() {
        val target = !isDataOn()
        ui.launch {
            val ok = kotlinx.coroutines.withContext(Dispatchers.IO) {
                runRoot("svc data ${if (target) "enable" else "disable"}")
            }
            if (!ok) {
                Toast.makeText(this@MainActivity, "루트 없음 · 설정에서 변경", Toast.LENGTH_SHORT).show()
                openPanel(AndroidSettings.ACTION_NETWORK_OPERATOR_SETTINGS, AndroidSettings.ACTION_SETTINGS)
            } else {
                kotlinx.coroutines.delay(700); refreshStatusIcons()
            }
        }
    }

    // ───────────────────── 첫 실행 권한 안내 ─────────────────────
    private var permRoot: WLinearLayout? = null

    private fun hasLocationPerm() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasNlsAccess(): Boolean {
        val flat = AndroidSettings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        ) ?: return false
        return flat.split(":").any { it.contains(packageName) }
    }

    private fun isDefaultHome(): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val res = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return res?.activityInfo?.packageName == packageName
    }

    private fun maybeShowFirstRunPermissions() {
        if (settings.firstRunDone) return
        showPermissionDialog()
    }

    private fun showPermissionDialog() {
        val root = WLinearLayout(this).apply {
            orientation = WLinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.bg))
        }
        permRoot = root
        refreshPermRows(root)

        val dialog = AlertDialog.Builder(this, R.style.Theme_CarMode_Dialog)
            .setTitle("필요한 권한 설정")
            .setView(root)
            .setPositiveButton("완료", null)
            .setOnDismissListener {
                permRoot = null
                settings.firstRunDone = true
            }
            .create()
        dialog.show()
        val screenW = resources.displayMetrics.widthPixels
        dialog.window?.setLayout((screenW * 0.55).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun refreshPermRows(container: WLinearLayout) {
        container.removeAllViews()
        // 안내 문구
        container.addView(TextView(this).apply {
            text = "CarMode를 제대로 쓰려면 아래 권한이 필요합니다."
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_dim))
            textSize = 13f
            setPadding(0, 0, 0, dp(6))
        })
        addPermRow(
            container, "위치", "GPS로 현재 위치 날씨 표시", hasLocationPerm()
        ) { locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
        addPermRow(
            container, "알림 접근", "재생 중인 음악 정보 표시", hasNlsAccess()
        ) {
            try { startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            catch (_: Exception) {}
        }
        addPermRow(
            container, "홈 앱 지정", "홈 버튼 시 CarMode 실행", isDefaultHome()
        ) {
            try { startActivity(Intent(AndroidSettings.ACTION_HOME_SETTINGS)) }
            catch (_: Exception) {
                try { startActivity(Intent(AndroidSettings.ACTION_SETTINGS)) } catch (_: Exception) {}
            }
        }
    }

    private fun addPermRow(
        container: WLinearLayout, label: String, desc: String,
        granted: Boolean, onGrant: () -> Unit
    ) {
        val row = WLinearLayout(this).apply {
            orientation = WLinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val textCol = WLinearLayout(this).apply {
            orientation = WLinearLayout.VERTICAL
            layoutParams = WLinearLayout.LayoutParams(0, WLinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(this).apply {
            text = (if (granted) "✓ " else "• ") + label
            setTextColor(ContextCompat.getColor(
                this@MainActivity, if (granted) R.color.amber else R.color.text))
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        textCol.addView(TextView(this).apply {
            text = desc
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_dim))
            textSize = 12f
        })
        val btn = TextView(android.view.ContextThemeWrapper(this, R.style.TopButton)).apply {
            text = if (granted) "완료됨" else "허용"
            if (granted) {
                alpha = 0.5f
            } else {
                setOnClickListener { onGrant() }
            }
        }
        row.addView(textCol)
        row.addView(btn)
        container.addView(row)
    }

    /** 위젯 패널을 좌/우로 재배치 (자식 뷰 순서 교체) */
    private fun applyWidgetSide() {
        val stage = b.stage
        val widget = b.widgetPanel
        val grid = b.tileGrid
        stage.removeAllViews()
        val gridLp = grid.layoutParams as android.widget.LinearLayout.LayoutParams
        if (settings.widgetSide == "left") {
            stage.addView(widget); stage.addView(grid)
            gridLp.marginStart = dp(16); gridLp.marginEnd = 0
        } else {
            stage.addView(grid); stage.addView(widget)
            gridLp.marginStart = 0; gridLp.marginEnd = dp(16)
        }
        grid.layoutParams = gridLp
    }

    // ───────────────────── 타일 ─────────────────────
    private fun entryFromPackage(pkg: String): AppEntry? {
        if (pkg.isEmpty()) return null
        return try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            val name = packageManager.getApplicationLabel(info).toString()
            AppEntry(key = pkg, name = name, icon = "", sub = "",
                accent = 0xFF607D8B, uri = "", packageName = pkg)
        } catch (e: Exception) { null }
    }

    private fun renderTiles() {
        val grid = b.tileGrid
        grid.removeAllViews()
        val cols = settings.tileCols; val rows = settings.tileRows
        // ★ 자식 추가 전에 행/열 수를 먼저 지정 (초과 시 GridLayout 예외 방지)
        grid.columnCount = cols
        grid.rowCount = rows
        slots = settings.getSlots()  // 그리드 크기에 맞춰 항상 재로딩
        slots.forEachIndexed { i, key ->
            val entry = AppCatalog.byKey(key)
                ?: if (key.contains('.')) entryFromPackage(key) else null
            val view = if (entry != null) buildTile(entry, i) else buildEmpty(i)

            val lp = GridLayout.LayoutParams().apply {
                width = 0; height = 0
                columnSpec = GridLayout.spec(i % cols, 1f)
                rowSpec = GridLayout.spec(i / cols, 1f)
                setMargins(dp(8), dp(8), dp(8), dp(8))
            }
            grid.addView(view, lp)
        }
    }

    private fun buildTile(entry: AppEntry, index: Int): View {
        val v = LayoutInflater.from(this).inflate(R.layout.tile, b.tileGrid, false)
        val iconTv = v.findViewById<TextView>(R.id.tileIcon)
        val iconImg = v.findViewById<ImageView>(R.id.tileIconImage)
        if (entry.packageName.isNotEmpty()) {
            iconTv.visibility = View.GONE
            iconImg.visibility = View.VISIBLE
            try {
                iconImg.setImageDrawable(packageManager.getApplicationIcon(entry.packageName))
            } catch (e: Exception) {
                iconImg.setImageResource(android.R.drawable.sym_def_app_icon)
            }
        } else {
            iconTv.visibility = View.VISIBLE
            iconImg.visibility = View.GONE
            iconTv.text = entry.icon
        }
        v.findViewById<TextView>(R.id.tileLabel).text = entry.name
        val subTv = v.findViewById<TextView>(R.id.tileSub)
        if (entry.sub.isNotEmpty()) {
            subTv.text = entry.sub
            subTv.visibility = View.VISIBLE
        } else {
            subTv.visibility = View.GONE
        }
        // 강조색 점
        val dot = v.findViewById<View>(R.id.tileCorner)
        (dot.background as? GradientDrawable)?.setColor(entry.accent.toInt())
            ?: dot.setBackgroundColor(entry.accent.toInt())
        // 삭제 버튼
        val del = v.findViewById<TextView>(R.id.tileDelete)
        del.visibility = if (editing) View.VISIBLE else View.GONE
        del.setOnClickListener {
            slots[index] = ""; settings.saveSlots(slots); renderTiles()
        }
        v.setOnClickListener {
            if (editing) openAppPicker(index) else launchApp(entry)
        }
        v.setOnLongClickListener {
            if (!editing) { toggleEdit() }
            true
        }
        return v
    }

    private fun buildEmpty(index: Int): View {
        val v = LayoutInflater.from(this).inflate(R.layout.tile_empty, b.tileGrid, false)
        v.setOnClickListener { openAppPicker(index) }
        v.setOnLongClickListener {
            if (!editing) { toggleEdit() }
            true
        }
        return v
    }

    // ───────────────────── 앱 선택 다이얼로그 ─────────────────────
    private fun openAppPicker(slotIndex: Int) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val installed = packageManager.queryIntentActivities(intent, 0)
            .sortedBy { it.loadLabel(packageManager).toString() }

        val scroll = android.widget.ScrollView(this)
        val container = WLinearLayout(this).apply {
            orientation = WLinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.bg))
        }
        scroll.addView(container)

        val dialog = AlertDialog.Builder(this, R.style.Theme_CarMode_Dialog)
            .setTitle("앱 선택")
            .setView(scroll)
            .setNegativeButton("닫기", null)
            .create()

        installed.chunked(4).forEach { rowApps ->
            val row = WLinearLayout(this).apply {
                orientation = WLinearLayout.HORIZONTAL
                layoutParams = WLinearLayout.LayoutParams(
                    WLinearLayout.LayoutParams.MATCH_PARENT,
                    WLinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            rowApps.forEach { app ->
                val cell = LayoutInflater.from(this).inflate(R.layout.item_app_grid, row, false)
                cell.layoutParams = WLinearLayout.LayoutParams(0, WLinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                cell.findViewById<ImageView>(R.id.appIcon).setImageDrawable(app.loadIcon(packageManager))
                cell.findViewById<TextView>(R.id.appName).text = app.loadLabel(packageManager)
                cell.setOnClickListener {
                    slots[slotIndex] = app.activityInfo.packageName
                    settings.saveSlots(slots)
                    renderTiles()
                    dialog.dismiss()
                }
                row.addView(cell)
            }
            // 마지막 행이 4개 미만이면 빈 셀로 채워 정렬 유지
            repeat(4 - rowApps.size) {
                val empty = View(this).apply {
                    layoutParams = WLinearLayout.LayoutParams(0, WLinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                row.addView(empty)
            }
            container.addView(row)
        }
        dialog.show()
        val screenW = resources.displayMetrics.widthPixels
        dialog.window?.setLayout((screenW * 2 / 3), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
    }

    // ───────────────────── 앱 실행 (딥링크 + 패키지 폴백) ─────────────────────
    private fun launchApp(entry: AppEntry) {
        // 0) 설치된 앱 (패키지명으로 저장된 경우)
        if (entry.packageName.isNotEmpty()) {
            val launch = packageManager.getLaunchIntentForPackage(entry.packageName)
            if (launch != null) { startActivity(launch); return }
            Toast.makeText(this, "${entry.name} 앱을 열 수 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        // 1) 딥링크 시도
        if (entry.uri.isNotEmpty()) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(entry.uri))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return
            } catch (_: Exception) { /* 폴백으로 */ }
        }
        // 2) 패키지명으로 실행 시도
        val pkg = AppCatalog.packageFallback[entry.key]
        if (pkg != null) {
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) { startActivity(launch); return }
            // 미설치 → 플레이스토어로
            try {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=$pkg")))
                return
            } catch (_: Exception) {}
        }
        Toast.makeText(this, "${entry.name} 앱을 열 수 없습니다", Toast.LENGTH_SHORT).show()
    }

    // ───────────────────── 시계 ─────────────────────
    private fun startClock() {
        val tick = object : Runnable {
            override fun run() {
                val c = Calendar.getInstance()
                val fmt = SimpleDateFormat("a h:mm", Locale.KOREA)
                b.tvTopTime.text = fmt.format(c.time)
                val days = arrayOf("일","월","화","수","목","금","토")
                val d = c.get(Calendar.DAY_OF_WEEK) - 1
                b.tvTopDate.text = "${c.get(Calendar.MONTH)+1}월 ${c.get(Calendar.DAY_OF_MONTH)}일 (${days[d]})"
                refreshStatusIcons()  // 와이파이/블루투스/데이터 상태 실시간 반영
                clockHandler.postDelayed(this, 1000)
            }
        }
        clockHandler.post(tick)
    }

    // ───────────────────── 날씨 페이지(현재/주간 스와이프) ─────────────────────
    private fun setupWeatherPager() {
        val inflater = LayoutInflater.from(this)
        wxNowPage = inflater.inflate(R.layout.wx_page_now, b.wxPager, false)
        wxWeekPage = inflater.inflate(R.layout.wx_page_week, b.wxPager, false)
        wxIcon = wxNowPage.findViewById(R.id.wxIcon)
        wxTemp = wxNowPage.findViewById(R.id.wxTemp)
        wxLoc = wxNowPage.findViewById(R.id.wxLoc)
        wxDesc = wxNowPage.findViewById(R.id.wxDesc)
        wxGrid = wxNowPage.findViewById(R.id.wxGrid)
        wxWeek = wxWeekPage.findViewById(R.id.wxWeek)

        // 날씨 새로고침 버튼
        wxNowPage.findViewById<View>(R.id.wxRefresh).setOnClickListener { v ->
            v.animate().rotationBy(360f).setDuration(500).start()
            Toast.makeText(this, "날씨 새로고침", Toast.LENGTH_SHORT).show()
            refreshWeather()
        }

        val pages = listOf(wxNowPage, wxWeekPage)
        b.wxPager.adapter = object : PagerAdapter() {
            override fun getCount() = pages.size
            override fun isViewFromObject(view: View, obj: Any) = view === obj
            override fun instantiateItem(container: ViewGroup, position: Int): Any {
                val v = pages[position]
                if (v.parent == null) container.addView(v)
                return v
            }
            // 페이지는 항상 유지 (제거하지 않아 참조 안정)
            override fun destroyItem(container: ViewGroup, position: Int, obj: Any) {}
        }
        b.wxPager.offscreenPageLimit = 1

        // 점 인디케이터
        updateDots(0)
        b.wxPager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                updateDots(position)
                b.wxPager.requestLayout()  // 현재 페이지 높이에 맞춰 재측정
            }
        })
    }

    private fun updateDots(selected: Int) {
        b.wxDots.removeAllViews()
        val size = dp(8)
        for (i in 0 until 2) {
            val dot = View(this).apply {
                layoutParams = WLinearLayout.LayoutParams(size, size).apply {
                    setMargins(dp(4), 0, dp(4), 0)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ContextCompat.getColor(
                        this@MainActivity,
                        if (i == selected) R.color.amber else R.color.line
                    ))
                }
            }
            b.wxDots.addView(dot)
        }
    }

    private fun renderWeekly(days: List<WeatherApi.DayForecast>) {
        wxWeek.removeAllViews()
        days.forEach { d ->
            val row = LayoutInflater.from(this).inflate(R.layout.wx_week_row, wxWeek, false)
            val (icon, _) = WeatherApi.describe(d.code)
            row.findViewById<TextView>(R.id.dowLabel).text = d.dow
            row.findViewById<TextView>(R.id.dowIcon).text = icon
            row.findViewById<TextView>(R.id.dowRain).text = "💧${d.rainProb}%"
            row.findViewById<TextView>(R.id.dowTemp).text = "${d.tMax}° / ${d.tMin}°"
            wxWeek.addView(row)
        }
    }

    // ───────────────────── 날씨 ─────────────────────
    private fun setupWeatherCellLabels() {
        // 전체 항목 순서/라벨 (설정에서 선택한 것만 표시, 3개씩 한 행)
        val allItems = listOf(
            "wind" to "🌬 바람", "rain" to "☔ 강수확률", "hum" to "💧 습도",
            "feels" to "🌡 체감", "max" to "⬆ 최고", "min" to "⬇ 최저",
            "pm10" to "😷 미세먼지", "pm25" to "😷 초미세먼지", "uvi" to "☀️ 자외선"
        )
        val visible = allItems.filter { (key, _) -> key in settings.wxItems }
        val rows = visible.chunked(3)
        val container = wxGrid
        container.removeAllViews()
        wxValues.clear()

        rows.forEach { pair ->
            val row = WLinearLayout(this).apply {
                orientation = WLinearLayout.HORIZONTAL
                layoutParams = WLinearLayout.LayoutParams(
                    WLinearLayout.LayoutParams.MATCH_PARENT,
                    WLinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, dp(4)) }
            }
            pair.forEach { (key, label) ->
                val cell = LayoutInflater.from(this).inflate(R.layout.wx_cell, row, false)
                cell.layoutParams = WLinearLayout.LayoutParams(0, WLinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                cell.findViewById<TextView>(R.id.cellKey).text = label
                wxValues[key] = cell.findViewById(R.id.cellValue)
                row.addView(cell)
            }
            container.addView(row)
        }
    }

    private fun setVal(key: String, value: String, color: Long? = null) {
        val tv = wxValues[key] ?: return
        tv.text = value
        if (color != null) tv.setTextColor(color.toInt())
    }

    private fun refreshWeather() {
        if (settings.locMode == "city" && settings.cityName.isNotEmpty()) {
            loadWeather(settings.cityLat, settings.cityLon, "${settings.cityName} (고정)")
        } else {
            requestGpsOrAsk()
        }
    }

    private fun requestGpsOrAsk() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            refreshWeatherByGps()
        } else {
            locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun refreshWeatherByGps() {
        wxLoc.text = "위치 확인 중…"
        try {
            val fused = LocationServices.getFusedLocationProviderClient(this)
            fused.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    ui.launch {
                        val name = locationNameFromGps(loc.latitude, loc.longitude)
                        loadWeather(loc.latitude, loc.longitude, name)
                    }
                } else loadDefaultCityWeather()
            }.addOnFailureListener { loadDefaultCityWeather() }
        } catch (e: SecurityException) { loadDefaultCityWeather() }
    }

    @Suppress("DEPRECATION")
    private suspend fun locationNameFromGps(lat: Double, lon: Double): String =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                val addresses = Geocoder(this@MainActivity, Locale.KOREA).getFromLocation(lat, lon, 1)
                val addr = addresses?.firstOrNull() ?: return@withContext "현재 위치"
                listOfNotNull(addr.subLocality, addr.locality, addr.subAdminArea, addr.adminArea)
                    .firstOrNull() ?: "현재 위치"
            } catch (e: Exception) { "현재 위치" }
        }

    private fun loadDefaultCityWeather() {
        loadWeather(37.5665, 126.9780, "서울 (기본)")
    }

    private fun loadWeather(lat: Double, lon: Double, label: String) {
        wxLoc.text = label  // API 성공 여부와 무관하게 먼저 표시
        ui.launch {
            val w = WeatherApi.fetch(lat, lon)
            if (w == null) { wxDesc.text = "날씨를 불러오지 못했습니다"; return@launch }
            val (icon, desc) = WeatherApi.describe(w.code)
            wxIcon.text = icon
            wxTemp.text = "${w.temp}°"
            wxDesc.text = desc
            setVal("feels", "${w.feels}°")
            setVal("hum", "${w.humidity}%")
            setVal("wind", String.format("%.1f m/s", w.wind))
            setVal("rain", "${w.rainProb}%")
            setVal("max", "${w.tMax}°")
            setVal("min", "${w.tMin}°")
            val (pm10txt, pm10col) = WeatherApi.pmGrade("pm10", w.pm10)
            val (pm25txt, pm25col) = WeatherApi.pmGrade("pm25", w.pm25)
            val (uvitxt, uvicol) = WeatherApi.uviGrade(w.uvi)
            setVal("pm10", pm10txt, pm10col)
            setVal("pm25", pm25txt, pm25col)
            setVal("uvi", uvitxt, uvicol)
        }
        // 주간 예보도 병렬로 로드
        ui.launch {
            val days = WeatherApi.fetchWeekly(lat, lon)
            if (days.isNotEmpty()) renderWeekly(days)
        }
    }

    // ───────────────────── 음악 위젯 ─────────────────────
    private fun setupMusicControls() {
        b.plPlay.setOnClickListener {
            val ctrl = activeController
            if (ctrl == null) { openMusicApp(); return@setOnClickListener }
            if (ctrl.playbackState?.state == PlaybackState.STATE_PLAYING)
                ctrl.transportControls.pause()
            else
                ctrl.transportControls.play()
        }
        b.plPrev.setOnClickListener {
            activeController?.transportControls?.skipToPrevious() ?: openMusicApp()
        }
        b.plNext.setOnClickListener {
            activeController?.transportControls?.skipToNext() ?: openMusicApp()
        }
        val openCurrent = android.view.View.OnClickListener {
            val ctrl = activeController
            if (ctrl != null) {
                packageManager.getLaunchIntentForPackage(ctrl.packageName)?.let { startActivity(it) }
            } else {
                openMusicApp()
            }
        }
        b.plArt.setOnClickListener(openCurrent)
        b.plTitle.setOnClickListener(openCurrent)
        b.plArtist.setOnClickListener(openCurrent)
        b.plTitle.isSelected = true  // marquee 활성화
    }

    private fun startMediaPoll() {
        val tick = object : Runnable {
            override fun run() {
                updateMusicWidget()
                mediaHandler.postDelayed(this, 1000)
            }
        }
        mediaHandler.post(tick)
    }

    private fun updateMusicWidget() {
        if (!isNlsGranted()) {
            b.plTitle.text = "알림 접근 권한 필요"
            b.plArtist.text = "설정 → 음악 → 권한 허용"
            b.plPlay.setImageResource(R.drawable.ic_play)
            b.plArt.setImageDrawable(null)
            return
        }
        try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val nlsComp = ComponentName(this, NLService::class.java)
            val controllers = msm.getActiveSessions(nlsComp)

            val ctrl = controllers.firstOrNull {
                it.playbackState?.state == PlaybackState.STATE_PLAYING
            } ?: controllers.firstOrNull()
            activeController = ctrl

            if (ctrl == null) {
                b.plTitle.text = "재생 중인 곡 없음"
                val pkg = settings.musicPackage
                b.plArtist.text = if (pkg.isNotEmpty()) {
                    try {
                        val info = packageManager.getApplicationInfo(pkg, 0)
                        "${packageManager.getApplicationLabel(info)} 열기"
                    } catch (e: Exception) { "음악 앱을 실행하세요" }
                } else "음악 앱을 실행하세요"
                b.plArt.setImageDrawable(null)
                b.plPlay.setImageResource(R.drawable.ic_play)
                return
            }

            val meta = ctrl.metadata
            val state = ctrl.playbackState
            b.plTitle.text = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "제목 없음"
            b.plArtist.text = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
            val art = meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            if (art != null) b.plArt.setImageBitmap(art) else b.plArt.setImageDrawable(null)
            val isPlaying = state?.state == PlaybackState.STATE_PLAYING
            b.plPlay.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)

        } catch (e: SecurityException) {
            b.plTitle.text = "알림 접근 권한 필요"
            b.plArtist.text = "설정 → 음악 → 권한 허용"
            b.plPlay.setImageResource(R.drawable.ic_play)
        }
    }

    private fun isNlsGranted(): Boolean {
        val flat = AndroidSettings.Secure.getString(
            contentResolver, "enabled_notification_listeners")
        return flat?.contains(packageName) == true
    }

    private fun openMusicApp() {
        val pkg = settings.musicPackage
        if (pkg.isNotEmpty()) {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) { startActivity(intent); return }
        }
        Toast.makeText(this, "설정에서 음악 앱을 지정하세요", Toast.LENGTH_SHORT).show()
    }

    // ───────────────────── 유틸 ─────────────────────
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        clockHandler.removeCallbacksAndMessages(null)
        mediaHandler.removeCallbacksAndMessages(null)
    }

    // 홈 버튼으로 이미 떠있는 상태에서 다시 홈 누르면 최상단 유지
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
    }
}
