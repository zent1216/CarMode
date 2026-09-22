package com.carmode.launcher

import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.carmode.launcher.databinding.ActivitySettingsBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private lateinit var settings: com.carmode.launcher.Settings
    private val ui = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        settings = com.carmode.launcher.Settings(this)

        b.btnBack.setOnClickListener { finish() }

        setupHomeApp()
        setupTheme()
        setupKeepScreen()
        setupWidgetSide()
        setupWeatherItems()
        setupWeatherLoc()
        setupMusic()
        setupTileGrid()
        setupUpdate()
    }

    // ── 타일 개수(가로/세로) ──
    private fun setupTileGrid() {
        refreshTileLabels()
        b.btnColsMinus.setOnClickListener { settings.tileCols -= 1; refreshTileLabels() }
        b.btnColsPlus.setOnClickListener  { settings.tileCols += 1; refreshTileLabels() }
        b.btnRowsMinus.setOnClickListener { settings.tileRows -= 1; refreshTileLabels() }
        b.btnRowsPlus.setOnClickListener  { settings.tileRows += 1; refreshTileLabels() }
    }

    private fun refreshTileLabels() {
        b.tvCols.text = settings.tileCols.toString()
        b.tvRows.text = settings.tileRows.toString()
    }

    // ── 업데이트 확인 ──
    private fun currentVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }

    private fun setupUpdate() {
        b.tvUpdateSub.text = "현재 버전 ${currentVersion()}"
        b.btnUpdate.setOnClickListener { checkUpdate(manual = true) }
        b.rowUpdate.setOnClickListener { checkUpdate(manual = true) }
    }

    private fun checkUpdate(manual: Boolean) {
        b.tvUpdateSub.text = "확인 중…"
        ui.launch {
            val latest = Updater.fetchLatest()
            if (latest == null) {
                b.tvUpdateSub.text = "현재 버전 ${currentVersion()}"
                if (manual) toast("업데이트 정보를 가져오지 못했습니다")
                return@launch
            }
            val cur = currentVersion()
            if (Updater.isNewer(cur, latest.version)) {
                b.tvUpdateSub.text = "새 버전 v${latest.version} 있음"
                showUpdateDialog(latest)
            } else {
                b.tvUpdateSub.text = "최신 버전 (v$cur)"
                if (manual) toast("최신 버전입니다")
            }
        }
    }

    private fun showUpdateDialog(r: Updater.Release) {
        val msg = if (r.notes.isBlank()) "새 버전 v${r.version} 을(를) 설치할까요?"
                  else "새 버전 v${r.version}\n\n${r.notes.take(500)}"
        AlertDialog.Builder(this, R.style.Theme_CarMode_Dialog)
            .setTitle("업데이트")
            .setMessage(msg)
            .setPositiveButton("다운로드·설치") { _, _ -> downloadAndInstall(r) }
            .setNegativeButton("나중에", null)
            .show()
    }

    private fun downloadAndInstall(r: Updater.Release) {
        b.tvUpdateSub.text = "다운로드 중…"
        toast("다운로드를 시작합니다")
        ui.launch {
            val apk = Updater.downloadApk(cacheDir, r.apkUrl)
            if (apk == null) {
                b.tvUpdateSub.text = "새 버전 v${r.version} 있음"
                toast("다운로드 실패")
                return@launch
            }
            // Android 8+ : 알 수 없는 앱 설치 허용 확인
            if (!packageManager.canRequestPackageInstalls()) {
                toast("설치 권한을 허용해주세요")
                try {
                    startActivity(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:$packageName")))
                } catch (_: Exception) {}
                return@launch
            }
            installApk(apk)
        }
    }

    private fun installApk(apk: java.io.File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", apk)
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(i)
        } catch (e: Exception) {
            toast("설치를 시작할 수 없습니다")
        }
    }

    private fun toast(m: String) =
        android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show()

    // ── 홈 앱 설정 ──
    private fun setupHomeApp() {
        b.btnSetHome.setOnClickListener {
            try {
                startActivity(Intent(AndroidSettings.ACTION_HOME_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(AndroidSettings.ACTION_SETTINGS))
            }
        }
        b.rowSetHome.setOnClickListener { b.btnSetHome.performClick() }
    }

    // ── 테마 (테마1 / 테마2 지도임베드) ──
    private fun setupTheme() {
        refreshThemeButtons()
        b.btnTheme1.setOnClickListener { settings.themeMode = 1; refreshThemeButtons() }
        b.btnTheme2.setOnClickListener { settings.themeMode = 2; refreshThemeButtons() }

        refreshMapAppSub()
        b.rowMapApp.setOnClickListener { showMapAppPicker() }
        b.btnMapApp.setOnClickListener { showMapAppPicker() }

        refreshPrivStatus()
        b.rowPrivSetup.setOnClickListener { showPrivDialog() }
        b.btnPrivSetup.setOnClickListener { showPrivDialog() }
    }

    private fun refreshThemeButtons() {
        val t2 = settings.themeMode == 2
        b.btnTheme1.setBackgroundResource(if (!t2) R.drawable.topbtn_bg_on else R.drawable.topbtn_bg)
        b.btnTheme1.setTextColor(ContextCompat.getColor(this, if (!t2) R.color.bg else R.color.text_dim))
        b.btnTheme2.setBackgroundResource(if (t2) R.drawable.topbtn_bg_on else R.drawable.topbtn_bg)
        b.btnTheme2.setTextColor(ContextCompat.getColor(this, if (t2) R.color.bg else R.color.text_dim))
    }

    private fun refreshMapAppSub() {
        val pkg = settings.mapPackage
        b.tvMapAppSub.text = if (pkg.isEmpty()) "미지정 — 지도 카드에 띄울 앱을 선택하세요" else {
            try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
            catch (e: Exception) { pkg }
        }
    }

    private fun showMapAppPicker() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(intent, 0)
            .sortedBy { it.loadLabel(packageManager).toString() }
        val scroll = android.widget.ScrollView(this)
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        scroll.addView(container)
        val dialog = AlertDialog.Builder(this, R.style.Theme_CarMode_Dialog)
            .setTitle("지도앱 선택").setView(scroll).setNegativeButton("닫기", null).create()
        apps.forEach { app ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_app, container, false)
            row.findViewById<android.widget.ImageView>(R.id.appIcon)
                .setImageDrawable(app.loadIcon(packageManager))
            row.findViewById<android.widget.TextView>(R.id.appName).text = app.loadLabel(packageManager)
            row.setOnClickListener {
                settings.mapPackage = app.activityInfo.packageName
                refreshMapAppSub(); dialog.dismiss()
            }
            container.addView(row, rowLp())
        }
        dialog.show()
    }

    private fun refreshPrivStatus() {
        val (txt, colorRes) = when (PrivShell.mode()) {
            PrivShell.MODE_ROOT -> "루트 사용 가능 ✓" to R.color.teal
            PrivShell.MODE_SHIZUKU -> "Shizuku 연결됨 ✓" to R.color.teal
            else -> "미설정 — 지도 임베드에 루트 또는 Shizuku 필요" to R.color.danger
        }
        b.tvPrivStatus.text = txt
        b.tvPrivStatus.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun showPrivDialog() {
        val alive = PrivShell.shizukuAlive()
        val granted = PrivShell.shizukuGranted()
        val msg = when {
            PrivShell.hasRoot() -> "루트가 감지되었습니다. 별도 설정 없이 지도 임베드를 사용할 수 있습니다."
            granted -> "Shizuku 권한이 이미 허용되어 있습니다."
            alive -> "Shizuku 가 실행 중입니다. 이 앱에 권한을 허용하세요."
            else ->
                "지도를 화면 안에서 구동하려면 루트 또는 Shizuku 가 필요합니다.\n\n" +
                "[Shizuku 설치·실행 방법]\n" +
                "1) Play스토어에서 'Shizuku' 설치\n" +
                "2) 개발자 옵션 → 무선 디버깅 켜기\n" +
                "3) Shizuku 앱에서 '무선 디버깅으로 시작' → 페어링\n" +
                "4) 이 화면으로 돌아와 '권한 요청'\n\n" +
                "* 루팅 불필요, PC 불필요. 폰 재부팅 시 Shizuku 재시작 필요."
        }
        val builder = AlertDialog.Builder(this, R.style.Theme_CarMode_Dialog)
            .setTitle("지도 임베드 권한").setMessage(msg).setNegativeButton("닫기", null)
        if (alive && !granted) {
            builder.setPositiveButton("권한 요청") { _, _ ->
                try {
                    rikka.shizuku.Shizuku.addRequestPermissionResultListener(
                        object : rikka.shizuku.Shizuku.OnRequestPermissionResultListener {
                            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                                rikka.shizuku.Shizuku.removeRequestPermissionResultListener(this)
                                refreshPrivStatus()
                            }
                        })
                    rikka.shizuku.Shizuku.requestPermission(1001)
                } catch (e: Throwable) { toast("Shizuku 권한 요청 실패") }
            }
        }
        builder.show()
    }

    // ── 화면 항상 켜기 ──
    private fun setupKeepScreen() {
        b.switchKeepOn.isChecked = settings.keepScreenOn
        b.switchKeepOn.setOnCheckedChangeListener { _, checked ->
            settings.keepScreenOn = checked
        }
    }

    // ── 위젯 위치 ──
    private fun setupWidgetSide() {
        refreshWidgetSideButtons()
        b.btnWidgetLeft.setOnClickListener {
            settings.widgetSide = "left"; refreshWidgetSideButtons()
        }
        b.btnWidgetRight.setOnClickListener {
            settings.widgetSide = "right"; refreshWidgetSideButtons()
        }
    }

    private fun refreshWidgetSideButtons() {
        val left = settings.widgetSide == "left"
        b.btnWidgetLeft.setBackgroundResource(
            if (left) R.drawable.topbtn_bg_on else R.drawable.topbtn_bg
        )
        b.btnWidgetLeft.setTextColor(
            ContextCompat.getColor(this, if (left) R.color.bg else R.color.text_dim)
        )
        b.btnWidgetRight.setBackgroundResource(
            if (!left) R.drawable.topbtn_bg_on else R.drawable.topbtn_bg
        )
        b.btnWidgetRight.setTextColor(
            ContextCompat.getColor(this, if (!left) R.color.bg else R.color.text_dim)
        )
    }

    // ── 날씨 표시 항목 ──
    private fun setupWeatherItems() {
        b.rowWxItems.setOnClickListener { showWxItemsDialog() }
        refreshWxItemsSub()
    }

    private fun refreshWxItemsSub() {
        val count = settings.wxItems.size
        b.tvWxItemsSub.text = "현재 ${count}개 항목 표시 중"
    }

    private fun showWxItemsDialog() {
        val keys = Settings.ALL_WX_ITEMS
        val labels = keys.map { Settings.WX_ITEM_LABELS[it] ?: it }.toTypedArray()
        val checked = keys.map { it in settings.wxItems }.toBooleanArray()
        AlertDialog.Builder(this, R.style.Theme_CarMode_Dialog)
            .setTitle("날씨 표시 항목")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("확인") { _, _ ->
                val selected = keys.filterIndexed { i, _ -> checked[i] }.toSet()
                settings.wxItems = selected.ifEmpty { Settings.ALL_WX_ITEMS.toSet() }
                refreshWxItemsSub()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ── 날씨 위치 ──
    private fun setupWeatherLoc() {
        refreshWeatherLocSub()
        b.rowWeatherLoc.setOnClickListener { showLocationDialog() }
    }

    private fun refreshWeatherLocSub() {
        b.tvWeatherLocSub.text = when {
            settings.locMode == "city" && settings.cityName.isNotEmpty() ->
                "${settings.cityName} (고정)"
            else -> "GPS (현재 위치)"
        }
    }

    private fun showLocationDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_location, null)
        val dialog = AlertDialog.Builder(this, R.style.Theme_CarMode_Dialog)
            .setView(view).create()

        val btnGps = view.findViewById<TextView>(R.id.modeGps)
        val btnCity = view.findViewById<TextView>(R.id.modeCity)
        val searchBox = view.findViewById<View>(R.id.searchBox)
        val input = view.findViewById<EditText>(R.id.cityInput)
        val results = view.findViewById<ViewGroup>(R.id.results)
        val hint = view.findViewById<TextView>(R.id.hint)

        fun refreshMode() {
            val gps = settings.locMode == "gps"
            btnGps.setBackgroundResource(if (gps) R.drawable.topbtn_bg_on else R.drawable.topbtn_bg)
            btnCity.setBackgroundResource(if (!gps) R.drawable.topbtn_bg_on else R.drawable.topbtn_bg)
            btnGps.setTextColor(ContextCompat.getColor(this, if (gps) R.color.bg else R.color.text_dim))
            btnCity.setTextColor(ContextCompat.getColor(this, if (!gps) R.color.bg else R.color.text_dim))
            searchBox.visibility = if (gps) View.GONE else View.VISIBLE
        }
        refreshMode()

        btnGps.setOnClickListener { settings.locMode = "gps"; refreshMode() }
        btnCity.setOnClickListener { settings.locMode = "city"; refreshMode() }

        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString()?.trim() ?: ""
                searchJob?.cancel()
                results.removeAllViews()
                if (q.length < 2) { hint.text = "두 글자 이상 입력하면 검색됩니다."; return }
                hint.text = "검색 중…"
                searchJob = ui.launch {
                    delay(350)
                    val cities = WeatherApi.searchCity(q)
                    results.removeAllViews()
                    if (cities.isEmpty()) { hint.text = "검색 결과가 없습니다."; return@launch }
                    hint.text = ""
                    cities.forEach { city ->
                        val item = TextView(this@SettingsActivity).apply {
                            text = "${city.name}   ${city.region}"
                            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text))
                            textSize = 15f
                            setPadding(dp(14), dp(12), dp(14), dp(12))
                            setBackgroundResource(R.drawable.topbtn_bg)
                            setOnClickListener {
                                settings.cityName = city.name
                                settings.cityLat = city.lat
                                settings.cityLon = city.lon
                                settings.locMode = "city"
                                refreshWeatherLocSub()
                                dialog.dismiss()
                            }
                        }
                        val lp = ViewGroup.MarginLayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(0, dp(4), 0, dp(4)) }
                        results.addView(item, lp)
                    }
                }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        dialog.setOnDismissListener { refreshWeatherLocSub() }
        dialog.show()
    }

    // ── 음악 ──
    private fun setupMusic() {
        refreshNlsStatus()
        b.btnNlsPerm.setOnClickListener {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }
        b.rowNlsPerm.setOnClickListener { b.btnNlsPerm.performClick() }

        refreshMusicAppSub()
        b.rowMusicApp.setOnClickListener { showMusicAppPicker() }
    }

    private fun refreshNlsStatus() {
        val flat = AndroidSettings.Secure.getString(
            contentResolver, "enabled_notification_listeners")
        val granted = flat?.contains(packageName) == true
        b.tvNlsStatus.text = if (granted) "허용됨 ✓" else "허용 안 됨 — 음악 정보를 읽으려면 허용 필요"
        b.tvNlsStatus.setTextColor(
            ContextCompat.getColor(this, if (granted) R.color.teal else R.color.danger)
        )
        b.btnNlsPerm.visibility = if (granted) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun refreshMusicAppSub() {
        val pkg = settings.musicPackage
        b.tvMusicAppSub.text = if (pkg.isEmpty()) "미지정" else {
            try {
                val info = packageManager.getApplicationInfo(pkg, 0)
                packageManager.getApplicationLabel(info).toString()
            } catch (e: Exception) { pkg }
        }
    }

    private fun showMusicAppPicker() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(intent, 0)
            .sortedBy { it.loadLabel(packageManager).toString() }

        val scroll = android.widget.ScrollView(this)
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        scroll.addView(container)

        val dialog = AlertDialog.Builder(this, R.style.Theme_CarMode_Dialog)
            .setTitle("음악 앱 선택")
            .setView(scroll)
            .setNegativeButton("닫기", null)
            .create()

        // 미지정 항목
        val noneRow = LayoutInflater.from(this).inflate(R.layout.item_app, container, false)
        noneRow.findViewById<android.widget.ImageView>(R.id.appIcon)
            .setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        noneRow.findViewById<android.widget.TextView>(R.id.appName).text = "미지정"
        noneRow.setOnClickListener { settings.musicPackage = ""; refreshMusicAppSub(); dialog.dismiss() }
        container.addView(noneRow, rowLp())

        apps.forEach { app ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_app, container, false)
            row.findViewById<android.widget.ImageView>(R.id.appIcon)
                .setImageDrawable(app.loadIcon(packageManager))
            row.findViewById<android.widget.TextView>(R.id.appName).text = app.loadLabel(packageManager)
            row.setOnClickListener {
                settings.musicPackage = app.activityInfo.packageName
                refreshMusicAppSub()
                dialog.dismiss()
            }
            container.addView(row, rowLp())
        }
        dialog.show()
    }

    private fun rowLp() = android.widget.LinearLayout.LayoutParams(
        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, 0, 0, dp(6)) }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        refreshNlsStatus()
        refreshPrivStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.cancel()
    }
}
