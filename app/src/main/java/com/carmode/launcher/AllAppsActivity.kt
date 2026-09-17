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
        buildGrid()
    }

    private fun buildGrid() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .sortedBy { it.loadLabel(pm).toString() }

        val grid = b.appsGrid
        val cols = 5
        grid.columnCount = cols

        apps.forEachIndexed { i, app ->
            val cell = LayoutInflater.from(this).inflate(R.layout.item_app_grid, grid, false)
            cell.findViewById<ImageView>(R.id.appIcon).setImageDrawable(app.loadIcon(pm))
            cell.findViewById<TextView>(R.id.appName).text = app.loadLabel(pm)
            cell.setOnClickListener {
                pm.getLaunchIntentForPackage(app.activityInfo.packageName)?.let { startActivity(it) }
            }
            val lp = GridLayout.LayoutParams().apply {
                columnSpec = GridLayout.spec(i % cols, 1f)
                rowSpec = GridLayout.spec(i / cols)
                width = 0
                setMargins(dp(6), dp(6), dp(6), dp(6))
            }
            grid.addView(cell, lp)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
