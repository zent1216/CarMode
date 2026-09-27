package com.carmode.launcher

import android.app.Activity
import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * 타일 편집 모드 공통 처리(테마1/2).
 * - 편집 중 화면 오른쪽 아래에 안내 문구를 띄운다(레이아웃에 끼우지 않아 타일 배치가 흔들리지 않음).
 * - 편집 중 타일 영역 바깥을 누르면 편집을 끝낸다([handleTouch]를 dispatchTouchEvent 에서 호출).
 */
class EditModeHelper(private val activity: Activity) {

    private var hint: TextView? = null

    /** 안내 문구 표시/숨김. [anchor](타일 영역)의 하단 가운데에 띄운다. */
    fun showHint(show: Boolean, anchor: View) {
        if (show) {
            if (hint != null) return
            val root = activity.findViewById<FrameLayout>(android.R.id.content)
            val d = activity.resources.displayMetrics.density
            val tv = TextView(activity).apply {
                text = "편집을 끝내려면 다른 곳을 터치하세요"
                setTextColor(ContextCompat.getColor(activity, R.color.text))
                textSize = 14f
                setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
                setBackgroundResource(R.drawable.topbtn_bg)
                elevation = 8 * d
            }
            tv.visibility = View.INVISIBLE
            root.addView(tv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ))
            hint = tv
            // 크기가 정해진 뒤 타일 영역 하단 가운데로 이동(다른 위젯을 가리지 않게)
            tv.post {
                val a = IntArray(2); anchor.getLocationInWindow(a)
                val r = IntArray(2); root.getLocationInWindow(r)
                tv.x = (a[0] - r[0] + (anchor.width - tv.width) / 2f)
                tv.y = (a[1] - r[1] + anchor.height - tv.height - 10 * d)
                tv.visibility = View.VISIBLE
            }
        } else {
            hint?.let { (it.parent as? FrameLayout)?.removeView(it) }
            hint = null
        }
    }

    /**
     * 편집 중이고 타일 영역 바깥을 눌렀으면 [exitEdit]를 호출하고 true(터치 소비)를 돌려준다.
     */
    fun handleTouch(ev: MotionEvent, editing: Boolean, tileArea: View, exitEdit: () -> Unit): Boolean {
        if (!editing || ev.actionMasked != MotionEvent.ACTION_DOWN) return false
        val r = Rect()
        tileArea.getGlobalVisibleRect(r)
        if (r.contains(ev.rawX.toInt(), ev.rawY.toInt())) return false
        exitEdit()
        return true
    }
}
