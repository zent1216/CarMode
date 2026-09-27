package com.carmode.launcher

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.gridlayout.widget.GridLayout
import com.carmode.launcher.databinding.ActivityAllAppsBinding

class AllAppsActivity : AppCompatActivity() {

    private lateinit var b: ActivityAllAppsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityAllAppsBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnBack.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        buildGrid()  // 앱 삭제 후 돌아오면 목록 갱신
    }

    private fun buildGrid() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .sortedBy { it.loadLabel(pm).toString() }

        val grid = b.appsGrid
        grid.removeAllViews()
        val cols = 5
        grid.columnCount = cols

        apps.forEachIndexed { i, app ->
            val cell = LayoutInflater.from(this).inflate(R.layout.item_app_grid, grid, false)
            cell.findViewById<ImageView>(R.id.appIcon).setImageDrawable(app.loadIcon(pm))
            cell.findViewById<TextView>(R.id.appName).text = app.loadLabel(pm)
            cell.setOnClickListener {
                pm.getLaunchIntentForPackage(app.activityInfo.packageName)?.let { FreeformDock.startFullscreen(this, it) }
            }
            cell.setOnLongClickListener { showAppMenu(it, app.activityInfo.packageName); true }
            val lp = GridLayout.LayoutParams().apply {
                columnSpec = GridLayout.spec(i % cols, 1f)
                rowSpec = GridLayout.spec(i / cols)
                width = 0
                setMargins(dp(6), dp(6), dp(6), dp(6))
            }
            grid.addView(cell, lp)
        }
    }

    /** 길게 누르기 메뉴: 앱 정보 / 삭제(삭제 가능한 앱만) */
    private fun showAppMenu(anchor: android.view.View, pkg: String) {
        val menu = android.widget.PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "앱 정보")
        if (canUninstall(pkg)) menu.menu.add(0, 2, 1, "삭제")
        menu.setOnMenuItemClickListener { item ->
            val uri = android.net.Uri.parse("package:$pkg")
            try {
                when (item.itemId) {
                    1 -> startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
                    2 -> startActivity(Intent(Intent.ACTION_DELETE, uri))
                }
            } catch (_: Exception) {}
            true
        }
        menu.show()
    }

    /** 기본 설치(시스템) 앱은 삭제 불가 — 업데이트만 된 시스템 앱도 제외 */
    private fun canUninstall(pkg: String): Boolean = try {
        val info = packageManager.getApplicationInfo(pkg, 0)
        (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 && pkg != packageName
    } catch (e: Exception) { false }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
