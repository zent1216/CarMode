package com.carmode.launcher

import android.widget.TextView

/**
 * 글자가 실제로 바뀔 때만 넣는다.
 * 같은 글자라도 다시 넣으면 흐르는(marquee) 제목이 처음으로 되감기므로,
 * 매초 갱신하는 음악 위젯 제목에 사용한다.
 */
fun TextView.setIfChanged(value: CharSequence) {
    if (text?.toString() != value.toString()) text = value
}
