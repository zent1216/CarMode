# 문제 해결 기록 (Troubleshooting)

## 2026-09-20 — 앱 실행 즉시 크래시 (다른 컴퓨터에서도 동일 재현)

### 증상
- 폰(갤럭시 S20, SM-G981N)에 앱은 설치되는데, 실행하면 켜지자마자 바로 꺼짐(크래시).
- 특정 컴퓨터만의 문제가 아니라 코드 자체 버그라 어느 환경에서 빌드해도 동일하게 재현됨.

### 크래시 로그 (핵심)
```
java.lang.IllegalArgumentException: row indices (start + span) mustn't exceed the row count.
    at androidx.gridlayout.widget.GridLayout.handleInvalidParams(GridLayout.java:811)
    at com.carmode.launcher.MainActivity.renderTiles(MainActivity.kt:175)
    at com.carmode.launcher.MainActivity.onCreate(MainActivity.kt:87)
```

### 원인
`Settings.kt`의 기본 타일 구성 상수에 쉼표가 하나 많았음.

```kotlin
const val DEFAULT_SLOTS = ",,,,,,"   // 쉼표 6개
```

- `",,,,,,".split(",")` → 빈 문자열 **7개** 반환 (쉼표 6개 = 요소 7개).
- `getSlots()`는 `size < SLOT_COUNT(6)`일 때만 빈칸을 채우고, **6개를 초과할 때는 잘라내지 않았음** → 슬롯이 7개가 됨.
- `MainActivity.renderTiles()`는 타일을 `GridLayout`(레이아웃 XML에서 `rowCount=2`, `columnCount=3` 고정, 즉 최대 6칸)에 배치.
- 7번째 타일(인덱스 6)의 행 위치가 `6 / 3 = 2` → 유효 행은 0,1뿐인데 행 인덱스 2를 요구 → `rowCount=2` 초과 → 예외 발생 후 앱 종료.

### 조치 (수정 내용)
파일: `app/src/main/java/com/carmode/launcher/Settings.kt`

1. 기본값의 쉼표 개수를 바로잡음 (쉼표 5개 = 빈 문자열 6개):
   ```kotlin
   const val DEFAULT_SLOTS = ",,,,,"
   ```
2. `getSlots()`가 저장된 값과 무관하게 **항상 정확히 6개**만 반환하도록 방어 코드 추가.
   이미 잘못된 값(7개)이 저장된 기기도 자동 복구됨:
   ```kotlin
   return raw.split(",").toMutableList().also {
       while (it.size < SLOT_COUNT) it.add("")
   }.take(SLOT_COUNT).toMutableList()
   ```

### 검증
- Microsoft OpenJDK 17 + Gradle 8.7로 `assembleDebug` 빌드 성공.
- 폰에 debug APK 설치 후 실행 → **크래시 없이 정상 구동 확인** (FATAL 로그 없음, 프로세스 정상 유지).

---

## 함께 겪을 수 있는 이슈 메모

### 1. 재설치 시 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`
- 기존에 설치된 앱이 **release 키로 서명**되어 있으면, debug 서명 APK로 덮어쓸 수 없음(서명 불일치).
- 해결: 기존 앱을 먼저 삭제 후 설치.
  ```
  adb uninstall com.carmode.launcher
  adb install app/build/outputs/apk/debug/app-debug.apk
  ```

### 2. release 서명 키(`carmode-release.jks`)는 저장소에 없음
- `.gitignore`에서 `*.jks`를 제외하고 있어 키 파일은 git에 올라가지 않음.
- 따라서 다른 컴퓨터에서는 release 빌드를 할 수 없고, 그 컴퓨터에서 만든 debug 빌드는 기존 release 설치본과 서명이 달라 덮어쓰기 불가.
- 정식(release) 서명 빌드를 유지하려면 원본 `carmode-release.jks` 파일을 해당 컴퓨터로 안전하게 옮겨와야 함(git이 아닌 별도 경로로 보관).

### 3. 기기 안드로이드 버전
- README에는 Android 13 기준으로 적혀 있으나, 현재 테스트 기기(S20)는 **Android 16**으로 업데이트된 상태에서 정상 동작 확인됨.
