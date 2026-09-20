package com.carmode.launcher

import android.content.Context
import android.util.AttributeSet
import androidx.viewpager.widget.ViewPager

/**
 * 현재 표시 중인 페이지의 높이에 맞춰 자기 높이를 조절하는 ViewPager.
 * 페이지마다 높이가 다를 때(예: 현재 날씨 vs 주간 예보) 빈 공간이 생기지 않도록 한다.
 */
class WrapHeightViewPager @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ViewPager(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 먼저 폭을 확정하기 위해 기본 측정
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val child = getChildAt(currentItem)
        if (child != null) {
            child.measure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            val h = child.measuredHeight
            val newHeightSpec = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
            super.onMeasure(widthMeasureSpec, newHeightSpec)
        }
    }
}
