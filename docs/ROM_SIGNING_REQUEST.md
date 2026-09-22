# CarMode 런처 특권 서명 요청서 (롬 개발자용)

## 요약
CarMode 런처의 **테마2(지도 임베드)** 기능이 지도앱을 화면 카드 안에서 실제 구동하려면
**신뢰 가상 디스플레이 생성 + 입력 주입** 권한이 필요합니다. 이는 일반 앱이 얻을 수 없는
signature 등급 권한이라, 현재는 사용자가 **매 부팅마다 Shizuku를 재실행**해야 하는 불편이 있습니다.

이 롬의 **nMirror2(`com.legendn.nmirror`)와 완전히 동일한 방식**으로 CarMode 런처를 처리해주시면
Shizuku 없이 부팅 시 자동으로 동작합니다.

## 요청 내용 (nMirror2와 동일 파이프라인)

대상 패키지: **`com.carmode.launcher`**

1. **플랫폼 키로 서명**
   - 현재 확인된 플랫폼/프레임워크 서명: `9525e35f` (nMirror2와 동일)
   - CarMode 런처 APK를 이 플랫폼 키로 서명

2. **권한 화이트리스트 추가** (`privapp-permissions` / `signature-permissions`)
   - nMirror2에 이미 존재하는 `<privapp-permissions package="com.legendn.nmirror">` 항목처럼
     `com.carmode.launcher` 항목을 추가

## 필요한 권한 (핵심 3개)

| 권한 | 용도 |
|------|------|
| `android.permission.ADD_TRUSTED_DISPLAY` | 신뢰 가상 디스플레이 생성 (지도앱이 카드 안에 머물게) |
| `android.permission.INJECT_EVENTS` | 카드 안 지도앱에 터치 전달 |
| `android.permission.INTERNAL_SYSTEM_WINDOW` | 지정 디스플레이에 앱 배치 |

보조(있으면 더 안정적, nMirror2도 보유):
- `android.permission.MANAGE_ACTIVITY_TASKS` — 특정 디스플레이에 액티비티 실행
- `android.permission.REAL_GET_TASKS`

> CAPTURE_VIDEO_OUTPUT, WRITE_SECURE_SETTINGS 등 나머지 nMirror2 권한은 **필요 없습니다.**
> 위 3개(+보조 2개)만 있으면 됩니다.

## 앱 쪽 준비
- CarMode 런처는 위 권한을 `AndroidManifest.xml`에 `<uses-permission>`으로 선언해 둡니다.
- 런처는 실행 시 **위 권한을 네이티브로 보유했는지 검사**하여,
  - 보유 시: Shizuku 없이 **앱 자체 권한으로** 직접 임베드
  - 미보유 시: 기존 Shizuku 경로로 폴백
- 따라서 **서명만 해주시면 코드 수정 없이** 무-Shizuku로 자동 동작합니다.

## 참고: 왜 이 방식이어야 하는가
- `ADD_TRUSTED_DISPLAY`는 `signature|role`, `INJECT_EVENTS`/`INTERNAL_SYSTEM_WINDOW`는 `signature`
  등급이라 `pm grant`·role 부여로는 일반 앱에 부여가 불가함(기기에서 직접 확인됨).
- 루팅은 넷플릭스(Widevine L1/무결성) 문제로 사용자가 원치 않음.
- 따라서 **플랫폼 서명 + 화이트리스트**(= nMirror2와 동일)가 유일하게 깔끔한 해법.

## 확인된 환경 정보
- 롬: phh Treble 기반 차량용 GSI (`me.phh.treble.car.boot.framework` 존재)
- 기기: 삼성 S20 (SM-G981N), Android 16
- nMirror2 서명 = 플랫폼 서명 = `9525e35f` (동일 키)
- nMirror2 flags: `SYSTEM, UPDATED_SYSTEM_APP, PRIVILEGED, PRODUCT`
