// Shizuku UserService 인터페이스 — shell(uid 2000) 프로세스에서 실행된다.
// shell 은 ADD_TRUSTED_DISPLAY 를 가지므로 "신뢰 가상 디스플레이"를 만들 수 있고,
// 그 위에서는 서드파티 지도앱의 내부 화면 전환까지 카드 안에 머문다.
package com.carmode.launcher;

import android.view.Surface;

interface IMapUserService {
    // Shizuku 서버가 서비스 종료 시 호출하는 예약 트랜잭션.
    void destroy() = 16777114;

    // 앱의 SurfaceView 표면을 받아 신뢰 가상 디스플레이를 생성하고 displayId 를 돌려준다.
    int createTrustedDisplay(in Surface surface, int width, int height, int densityDpi) = 1;

    // 지정 디스플레이 위에 지도앱 액티비티(플랫 컴포넌트명)를 실행한다.
    boolean startOnDisplay(int displayId, String component) = 2;

    // 가상 디스플레이 해제.
    void releaseDisplay() = 3;
}
