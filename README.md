# AutoTube - 안드로이드 오토 유튜브 재생 앱

안드로이드 오토(Android Auto) 차량 헤드유닛 화면에서 유튜브 동영상과 음악을 편리하게 시청 및 조작할 수 있는 개인용 앱입니다.

---

## 📌 주요 기능
1. **차량 화면 맞춤 유튜브 플레이어 (`AutoPresentation`)**:
   - 차량 디스플레이 해상도에 최적화된 전체 화면 렌더링
   - 대형 터치 친화적 상단 내비게이션 바 (뒤로 가기, 앞으로 가기, 홈, 새로고침, 주소 확인)
   - 몰입 모드 지원 (상단 바 숨기기 / 미니 토글 버튼)
   - 유튜브 자체 전체화면(Full-screen) 비디오 재생 지원
   - 상단 모바일 앱 전환 유도 배너 자동 숨김 및 자동 음소거 해제 최적화 스크립트 내장
2. **미디어 세션 및 스티어링 휠 리모컨 연동 (`AutoMediaBrowserService`)**:
   - Android Auto 미디어 런처에 앱 아이콘 노출
   - 자동차 핸들 리모컨의 재생/일시정지/다음곡/이전곡 버튼 조작 연동
3. **스마트폰 동기화 및 사전 로그인 (`MainActivity`)**:
   - 스마트폰 화면에서 미리 구글/유튜브 계정에 로그인하면 차량 화면에서도 동일한 로그인 세션, 구독 채널, 추천 영상이 유지됨
   - 안드로이드 오토 헤드유닛 연결 상태 실시간 모니터링

---

## 🛠️ PC에 구성 및 설치 완료된 테스트 환경
모든 필수 빌드 및 테스트 도구가 PC에 자동 설치되어 즉시 사용 가능합니다:
- **Java**: Microsoft OpenJDK 17 (`C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot`)
- **Android SDK & Build-Tools**: Android API 34, Build-Tools 34.0.0 (`C:\Android\Sdk`)
- **ADB & Platform-Tools**: 최신 ADB 플랫폼 도구 설치 완료
- **Desktop Head Unit (DHU)**: 구글 공식 안드로이드 오토 시뮬레이터 (`tools\dhu\desktop-head-unit.exe`)
- **빌드된 APK**: `app\build\outputs\apk\debug\app-debug.apk`

---

## 🚀 테스트 진행 가이드 (PC 시뮬레이터 DHU)

### 1단계: 스마트폰 개발자 설정 (최초 1회)
1. 스마트폰의 **[설정] > [휴대전화 정보] > [소프트웨어 정보]**에서 **'빌드번호'**를 7번 연속 터치하여 스마트폰 개발자 옵션을 켭니다.
2. **[설정] > [개발자 옵션]**에서 **'USB 디버깅'**을 활성화합니다.
3. 스마트폰의 **[설정] > [연결된 기기] > [Android Auto]** (또는 앱 목록에서 Android Auto 검색)로 이동합니다.
4. Android Auto 화면 맨 아래로 스크롤하여 **'버전'** 영역을 **10번 연속 탭**합니다.
5. "개발자 설정을 사용하도록 설정하시겠습니까?" 팝업이 뜨면 **[확인]**을 누릅니다.
6. 우측 상단 **점 3개 메뉴 [⋮] > [개발자 설정]**으로 들어갑니다.
7. **[알 수 없는 소스]** 항목에 **체크(허용)**합니다.

### 2단계: 스마트폰에 앱 설치하기
1. 스마트폰을 USB 케이블로 PC에 연결합니다.
2. 스마트폰 화면에 "USB 디버깅을 허용하시겠습니까?" 창이 뜨면 **[항상 허용]**을 선택합니다.
3. 프로젝트 폴더의 **`install-app.bat`**를 더블 클릭하여 실행합니다.
   - *팁: Google Play 스토어 설치자 속성(`-i com.android.vending`)으로 설치되어 안드로이드 오토 런처에서 아이콘이 정상 노출됩니다.*
4. 스마트폰에서 설치된 **AutoTube** 앱을 한 번 실행하여 정상 실행되는지 확인합니다. (원하시는 경우 구글 계정 로그인)

### 3단계: PC 모니터에서 안드로이드 오토 시뮬레이터(DHU) 띄우기
1. 스마트폰의 Android Auto 설정 화면으로 이동합니다.
2. 우측 상단 **점 3개 메뉴 [⋮]**를 누르고 **[헤드유닛 서버 시작]**을 터치합니다.
   - *상단 알림창에 "헤드유닛 서버가 실행 중입니다"라는 알림이 뜹니다.*
3. 프로젝트 폴더의 **`start-dhu.bat`**를 더블 클릭하여 실행합니다.
4. PC 화면에 실제 차량 모니터와 동일한 **Desktop Head Unit(DHU)** 창이 열립니다!
5. 헤드유닛 화면의 앱 서랍에서 **AutoTube** 아이콘을 클릭하면 유튜브 화면이 즉시 표시됩니다.

---

## 🚗 실제 차량에서 사용할 때
1. 스마트폰의 Android Auto 개발자 설정에서 **'알 수 없는 소스'**가 켜져 있는지 확인합니다.
2. 스마트폰을 차량의 USB 포트(또는 무선 안드로이드 오토 지원 차량)에 연결합니다.
3. 차량 내비게이션/디스플레이의 안드로이드 오토 런처에서 **AutoTube**를 선택하여 실행합니다.
4. 주차(P) 또는 정차 상태에서 영상을 안전하게 감상하실 수 있습니다.

---

## 📂 파일 구조
- `app/src/main/java/com/personal/andautoutube/AutoPresentation.kt`: 차량 디스플레이 렌더링
- `app/src/main/java/com/personal/andautoutube/AutoPresentationService.kt`: 디스플레이 감지 및 서비스 관리
- `app/src/main/java/com/personal/andautoutube/AutoMediaBrowserService.kt`: 안드로이드 오토 런처 및 미디어 세션
- `app/src/main/java/com/personal/andautoutube/MainActivity.kt`: 폰 제어 및 계정 연동 액티비티
- `tools/dhu/desktop-head-unit.exe`: 구글 안드로이드 오토 공식 에뮬레이터
- `install-app.bat`: 원클릭 스마트폰 앱 설치 스크립트
- `start-dhu.bat`: 원클릭 PC 안드로이드 오토 시뮬레이터 실행기
- `build-apk.bat`: APK 재빌드 스크립트
