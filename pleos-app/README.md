# 도로주행도우미

**0.5.8 · versionCode 24 · Pleos Connect용 Android 앱.** 정차·P단에서 출발을 준비하고, 현재 위치에서 한 바퀴 도는 코스를 기본 내비로 안내받으며 연습합니다. 패키지는 기존 `kr.juhyu.licensenote`를 유지합니다.

- **내 차 빠르게 익히기**: 속도·기어·연료·배터리를 한 화면에서 확인하고 깜빡이와 와이퍼를 직접 작동합니다. 실습 진입 후 SDK에서 새 OFF→ON 변화를 받아야 자동 진행합니다. 이미 켜진 상태나 미지원은 완료로 만들지 않습니다.
- **음성 질문**: 주행 화면 우측 하단 마이크 FAB를 누릅니다. 실제 SpeechToText의 중간 인식 문장을 중앙에 크게 표시하고 공식 설명서 문답으로 답합니다. 별도 호출 이름은 없습니다.
- **음성 출력**: Pleos TextToSpeech 3.3.1 `ON_DEVICE`입니다. 약어 발음과 안내 문장을 다듬었지만 음성 엔진을 바꿨다고 주장하지 않습니다. 서버 기반 HYBRID/SERVER는 프로젝트 등록 자격 증명이 필요합니다.
- **코스**: 신선한 FusedLocation 좌표에서 주변 도로 경로를 찾아 같은 출발지로 복귀합니다. 10·20·30분 또는 직접 정한 시간을 요청할 수 있고 장소를 지정하면 현재 위치에서 다녀오는 길을 찾습니다. 위치·네트워크·자동차 경로 응답이 없으면 고정된 서울 코스로 대체하지 않습니다. 거리·시간은 모델 예상이며 특정 회전·차로 수 조건은 보장하지 않습니다.
- **기본 내비 연동**: 앱 내 지도와 타일 요청을 제거했습니다. NaviHelper로 경유지와 출발지 복귀 목적지를 전달하고, 실제 길 안내는 기본 내비에서 확인합니다. Pleos의 차량 Driving view / 기본 내비 / 우리 앱 두 창을 함께 사용합니다. 첫 분할은 시스템 창 손잡이로 조정하며 공개 SDK가 이 배치를 강제하는 것은 확인되지 않았습니다.
- **복습**: 주차 후 기록 종료를 누르면 차량 신호를 정리한 결과가 열립니다. 관측된 사실과 신호 부족을 구별하며 동승자 답변을 새로 요구하지 않습니다. 종전 기록과 답변은 보존합니다.

주변 도로 탐색은 Valhalla 공개 데모의 OpenStreetMap 도로 그래프를 사용하는 시제품입니다. 실서비스에는 운영 가능한 경로 공급자가 필요합니다. 앱 안에 OSM 지도를 표시하지 않는 것과 도로 그래프의 출처는 별개입니다. 실제 통행 조건과 연습 적합성은 현장에서 확인해야 합니다.

최종 설치 파일은 `apk/오주현_도로주행도우미_release.apk`이며 검사 기록은 `outputs/release-058/verification.json`입니다. 내비 취소 상태의 복구와 목표 코스 시간 보정을 수정했습니다. 이전 음성 시연은 `outputs/revision-20261115*`에 보관합니다. 아래의 0.2.x~0.5.3 자료는 과거 버전에서 확인한 범위입니다.

## 이전 버전의 검증 기록

**0.2.1 · 양재 좌·우회전 연습.** 한 회차에 약 413m를 달리며 첫 번째 좌회전과 두 번째 우회전의 깜빡이를 기록한다. 주차하면 동승자가 각 회전을 실제로 봤는지 답한다. 다시 연습할 구간을 저장하면 다음 회차에서 같은 구간의 지난번·이번 기록을 비교할 수 있다. 코스는 OpenStreetMap 도로 형상을 따라 만든 에뮬레이터용 경로다. 실제 도로의 회전 허용 여부와 통행 조건은 검수 전이다. [개발 요구사항](<../면허연습노트/개발 요구사항.md>) · [Figma 원안](https://www.figma.com/design/AabvIHA3LX6ZfKEd4rtKHi?node-id=25-2)

## 주차 중 운전 설명서

시작 화면, 출발 준비, 경로 미리보기, 연습 결과 또는 오답노트 상단의 **설명서**를 누르면 **내 차 익히기**가 열린다. 13개 주제를 출발 준비, 비·야간, 표시등·경고, 주차·정차로 찾아본다. 그림은 학습용 예시이며 각 단계를 다시 재생할 수 있다. 좌우 거울, 앞뒤 유리, 상·하향등, 타이어 경고 패턴 등을 눌러 비교한다. 새 주제 8개의 **상황 문제**에서는 답을 선택하면 이유를 읽고 다시 선택할 수 있다. 차량 기능은 조회만 한다. 정차·P단·화면 조작 허용 조건을 벗어나면 설명과 문제를 닫고 다시 주차하면 목록으로 돌아간다.

차량은 공개 Vehicle SDK 2.3.2의 `CAR_SAFETY` 권한으로 운전석 미착용 경고와 워셔액 부족 경고를 각각 조회한다. 차량이 지원하는 경우에만 켜짐·꺼짐을 표시한다. 이 프로젝트의 Pleos 에뮬레이터에서는 두 속성 모두 `ERROR_NOT_SUPPORTED_PROPERTY`를 반환했다. 벨트 카드의 버클 상태는 Seat 도메인에서 별도로 읽으며, 깜빡이 설명에서 내 차의 방향은 TurnSignal에서 읽는다. 두 경고의 실차 점등·해제는 검증 전이다. [미착용 경고 API](https://document.pleos.ai/api-reference/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/has-seat-belt-not-buckled-warning) · [워셔액 부족 API](https://document.pleos.ai/api-reference/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/safety/has-low-washer-fluid-warning) · [SDK 권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission)

조작 그림은 표시와 원리를 익히는 예시다. 새 주제는 **국내 2026 쏘나타(DN8) 취급설명서**를 기준으로 하며, 장착 사양과 차량별 스위치는 내 차 설명서에서 확인한다. 기존 깜빡이·와이퍼 자료도 열어볼 수 있다. [방향지시등](https://ownersmanual.hyundai.com/full_webhelp/DN8/2026/ko_KR/ideb4a90bce9f.html) · [앞유리 와이퍼](https://ownersmanual.hyundai.com/full_webhelp/NH2/2026/ko_KR/id23C6B060A4Y.html)

| 새 주제 | 화면에서 확인하는 내용 | 현대자동차 취급설명서 |
| --- | --- | --- |
| 사이드미러 | L·R 선택, 볼록거울에서 보이는 거리 | [실외 미러](https://ownersmanual.hyundai.com/full_webhelp/DN8/2026/ko_KR/idce3017f48fe.html) |
| 문 열림 위치 | 차체도 읽기, 좌석 네 문과 보닛·트렁크 확인 범위 | [클러스터 표시창 정보](https://ownersmanual.hyundai.com/full_webhelp/DN8/2026/ko_KR/id872310cc350.html) |
| 유리 습기 제거 | 앞유리 송풍·외기·에어컨, 뒷유리 열선의 쓰임 | [앞유리 안쪽 습기](https://ownersmanual.hyundai.com/full_webhelp/DN8/2026/ko_KR/idc0853a3fd8c.html) · [뒷유리 열선](https://ownersmanual.hyundai.com/full_webhelp/DN8/2026/ko_KR/ide9dfd643271.html) |
| 상향등과 하향등 | 빛의 방향, 앞차가 있거나 마주 오는 차가 있을 때의 선택 | [전조등 전환](https://ownersmanual.hyundai.com/full_webhelp/DN8/2026/ko_KR/id10d5e300236.html) |
| 비상등 | 삼각형 버튼과 좌우 동시 점멸 | [비상 경고등](https://ownersmanual.hyundai.com/full_webhelp/DN8/2026/ko_KR/id71c0f13c980.html) |
| 타이어 경고등 | 저압과 시스템 이상 패턴, 냉간 점검, 권장값 라벨 위치 | [점등과 점멸](https://ownersmanual.hyundai.com/full_webhelp/DN8/2026/ko_KR/idefa1f466587.html) · [냉간 점검](https://ownersmanual.hyundai.com/full_webhelp/DN8/2026/ko_KR/id5b95d2f6eff.html) · [라벨 위치](https://ownersmanual.hyundai.com/full_webhelp/DN8/2026/ko_KR/id224QH90095Z.html) |
| 전자식 주차 브레이크 | 정차·페달·스위치 순서, P단과 체결 확인 | [전자식 파킹 브레이크](https://ownersmanual.hyundai.com/full_webhelp/DN8/2026/ko_KR/id76e01aea2d0.html) |
| 오토홀드 | 흰색 대기, 초록색 정차 유지, 노란색 이상 표시 | [자동 정차 기능](https://ownersmanual.hyundai.com/full_webhelp/DN8/2026/ko_KR/id390901fe6c7.html) |

문 열림 설명에서는 공개 Door 신호를 좌석 문별로 확인한다. 일부 문에서 값을 받지 못하면 해당 범위를 미확인으로 남긴다. 전조등 설명은 Light 도메인의 현재 상향등과 하향등 상태를 각각 읽고, 주간주행등 상태를 따로 표시한다. TPMS 그림·오토홀드·EPB·열선 상태는 학습용 예시다. [Pleos Light API](https://document.pleos.ai/en/api-reference/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/entry)

Pleos 에뮬레이터에서 13개 주제와 상황 문제 8개의 정오답·재시도를 확인했다. 운전석 문 열림, 하향등 켜짐, 상향등 켜짐·꺼짐이 각각 공개 SDK로 수신됐다. 문제를 푸는 중에 D단으로 전환하면 조작이 잠기고, P단 복귀 후 목록을 닫으면 원래 연습 결과가 열린다. 독립 빌드의 단위 검사 49개가 통과했고 lint는 오류 0개, 경고 4개였다. [설치 APK](../outputs/guide-expansion-20261114/면허연습노트-운전설명서.apk) · [문 신호와 학습 그림](../outputs/guide-expansion-20261114/door-state.png) · [상황 문제 화면](../outputs/guide-expansion-20261114/defog-answer.png) · [실행 기록](../outputs/guide-expansion-20261114/verification.json) · [빌드 검사](../outputs/guide-expansion-20261114/build-checks.json)

## 과거 버전 검증 상태

| 구분 | 확인한 범위 |
| --- | --- |
| 단위 검사 | 0.2.1 최종 빌드에서 43개 통과. lint 오류0개·경고7개. 조향·위치·관찰·깜빡이 이력과 다음 회차 목표를 합성 입력으로 검사한다. [실행 기록](../outputs/turn-prototype-20261114/WORKLOG.md) |
| 에뮬레이터 조향 | Virtual Console 입력 −90, +90, 중립에 대해 Vehicle SDK에서 각각 +90, −90, 0을 수신했다. 회전 추정에는 조향 방향을 사용한다. 실차의 앞바퀴 물리각은 미교정이다. [조향 검사 원자료](../outputs/turn-prototype-20261114/steering-final/steering-checks.json) |
| 글자 배율과 포커스 | 앱의 **글자→기본/크게** 설정으로1.0/1.3배를 바꾼다. 실제 글자 확대·같은 프로세스/Activity·기본 내비와 창 배치를 함께 검사한다. 시스템 전체 글꼴 변경 시 Pleos Home이 기본 내비를 넓은 창으로 다시 띄우는 제약은 별도다. [변경 근거](../outputs/turn-prototype-20261114/WORKLOG.md) |
| 기본 내비 | 실제 화면·경로·TBT와 SDK 요청을 확인했다. Native 위치가 판교에 머물고 경유지 좌표·활성 응답이 불일치해 양재 전체 코스 추종은 미검증이다. 앱의 FusedLocation 위치 재생과 구별한다. [기본 내비 제약과 원자료](../outputs/turn-prototype-20261114/native-navigation-limitations.md) |
| 화면 | 기본64개 조합에 활성 내비 주행8개·재연습 비교8개를 더해 실제80개 캡처를 전수 검토했다. [최종 검증 보고서](../outputs/turn-prototype-20261114/REPORT.md) |
| 시연 | 실제 에뮬레이터3분37초 편집본, 전체 원본3개·자막·컷 명세를 보존했다. [영상](../outputs/turn-prototype-20261114/demo-overview/overview.mp4) · [검수와 원자료](../outputs/turn-prototype-20261114/demo-overview/README.md) |

## 한 회차 사용 방법

1. 정차·P단에서 벨트·거울·문을 확인합니다. 벨트 화면의 **내 차 빠르게 익히기**에서 상태 요약과 실제 조작 실습을 열 수 있습니다.
2. 현재 위치가 수신되면 원하는 연습 시간을 고릅니다. 경로 미리보기의 **기본 내비로 연습 시작**으로 경유지와 복귀 목적지를 전달합니다. 경로 수신과 내비의 안내 시작은 별도의 상태입니다.
3. 시스템 창 손잡이로 내비와 앱을 나란히 놓습니다. 우리 앱은 차량 기록·간단한 코칭·G미터를 표시하며 실제 길은 기본 내비를 따릅니다. 마이크 FAB로 질문하고 듣기를 중지하거나 닫을 수 있습니다.
4. 주차 후 **기록 종료**를 누르고 신호 기반 복습을 확인합니다. **QR로 받기** 또는 연결한 휴대폰으로 결과를 전달할 수 있습니다.

## 기록과 판정

Vehicle SDK 2.3.2에서 벨트·문·방향지시등·조향·연료·배터리·와이퍼를 차종 지원 범위 안에서 읽고 AAOS에서 속도·기어·화면 조작 제한을 읽습니다. 위치는 FusedLocation 1.1.3, 기본 내비는 NaviHelper 3.0.5입니다. 차량 익히기 화면의 작동 확인은 해당 화면 진입 뒤 실제 변화가 있어야 합니다.

새 회차는 `rulesVersion=6`로 기록합니다. 위치·조향 등으로 추정한 회전에서 깜빡이가 켜졌는지, 계속 꺼져 있었는지, 신호가 부족했는지를 남깁니다. 종합 연습 점수와 오답노트를 제공합니다. 확인한 벨트·문·가감속·추정 회전 깜빡이 기록에 앱 자체 차감 규칙을 적용하며, 미평가 항목과 데이터 부족을 표시합니다. 공식 시험 합격·운전 안전성을 산출하지 않습니다. 이전 규칙의 회차는 당시 기록과 답변을 유지합니다. 현재 위치 코스의 도로 형상과 복귀 지점도 함께 저장해 앱 재시작 후 같은 경로로 복습합니다.

SQLite에 세션과 표본을 저장합니다. 기록 중에는 foreground service가 위치를 수집하고, 기록하지 않을 때에는 앱이 보이는 동안 현재 위치를 받습니다. 프로세스가 종료되면 진행 중이던 회차는 다음 실행에서 중단 기록으로 복구합니다.

## 빌드와 설치

프로젝트 루트의 `Pleos 개발환경 시작.cmd`로 가상 기기를 준비한 뒤 `pleos-app` 폴더에서 실행한다. 이 프로젝트의 검증 환경은 Android Emulator 36.6.11, NVIDIA host GPU, 2048MB, Vulkan 비활성, `emulator-5580`이다. 이는 현재 검증 프로필이며 Vulkan 결함의 원인·해결을 확정한 설정은 아니다. 빌드 스크립트는 한글 경로의 Gradle 테스트 워커를 위해 임시 ASCII 드라이브 별칭을 쓴다.

```powershell
.\tools\build.ps1 -Tasks ':app:assembleDebug',':app:testDebugUnitTest',':app:lintDebug',':ui-driver:assembleDebug'
.\tools\install.ps1 -Serial emulator-5580 -WithUiDriver
```

앱은 `kr.juhyu.licensenote`, minSdk 28, compileSdk·targetSdk 35다. 설치 파일은 `app/build/outputs/apk/debug/app-debug.apk`에 생성된다. 개발 기기에서 SDK 진단과 접근성 기반 화면 검사를 할 때 시험용 `ui-driver`를 함께 설치한다. [Gradle 설정](app/build.gradle.kts)

## 에뮬레이터에서 재현

Virtual Console 브리지를 켜고 검증용 에뮬레이터에서 실행한다. 아래 명령은 해당 환경에서 측정한 콘솔과 SDK 사이의 조향 부호 −1을 사용한다. 각 실행의 `--output`에는 새 폴더를 지정한다. 현재 위치를 읽을 수 없는 기기에서는 시험 전 위치를 `--restore-lat`, `--restore-lon`으로 지정한다.

```powershell
python .\tools\verify_turn_flow.py --serial emulator-5580 --console-to-sdk-sign -1 --ui-matrix --ui-matrix-font-mode app --expected-width 1682 --output ..\outputs\turn-replay-first
```

첫 실행은 좌회전 깜빡이 꺼짐과 우회전 켜짐을 입력하고, 주차 후 동승자 확인·정정·복원을 검사한다. `--ui-matrix`는 벨트·거울·문·코스·주행·동승자 확인·결과·오답노트에서 841/1682 폭, 주간/야간, 글자 1.0/1.3을 조합한다. 각 기본 화면은 8종으로 총 64장이다. 스크롤한 보조 화면과 메타데이터도 별도로 남긴다. [회전 시나리오](tools/verify_turn_flow.py) · [화면 조합 검사](tools/verify_ui_matrix.py)

첫 실행은 마지막에 좌회전 응답을 다시 확정하고 오답노트를 연 상태에서 끝난다. 그 상태에서 아래 명령을 실행하면 검사 도구가 **좌회전 다시 연습**를 눌러 목표를 저장한 뒤 다음 회차를 시작한다. 양재 좌회전에서 이번에는 왼쪽 신호를 보낸다. 비교 조건은 동일한 이전 세션, 동일한 구간, 지난번 `OMISSION`과 이번 `OBSERVED`다.

```powershell
$previousPracticeId = (Get-Content -Raw -Encoding UTF8 ..\outputs\turn-replay-first\confirmed-report.json | ConvertFrom-Json).id
python .\tools\verify_turn_flow.py --serial emulator-5580 --console-to-sdk-sign -1 --left-signal BlinkLeft --expect-target left-01 --expect-previous-session $previousPracticeId --output ..\outputs\turn-replay-second
```

에뮬레이터 화면 녹화는 시나리오 명령 앞에 아래처럼 녹화 도구를 붙인다. 출력은 실제 Android `screenrecord`의 무음 MP4와 촬영 메타데이터다. 180초마다 실제 녹화를 자동 분할하고 원본을 보존한 뒤 재인코딩 없이 연결한다. 분할 사이의 짧은 공백 가능성은 capture.json에 명시한다. 이전 차로 변경 시나리오의 도구는 0.1.x 재현용으로 보존했다. [녹화 도구](tools/record_demo.py) · [이전 차로 변경 검사](tools/verify_flow.py)

```powershell
python .\tools\record_demo.py --serial emulator-5580 --seconds 180 --output ..\outputs\turn-demo -- python .\tools\verify_turn_flow.py --serial emulator-5580 --console-to-sdk-sign -1 --output ..\outputs\turn-demo-flow
```

## 코드와 자산

| 파일 | 역할 |
| --- | --- |
| [Practice.kt](app/src/main/java/kr/juhyu/licensenote/Practice.kt) | 코스, 회전 추정, 관찰 정정, 구간별 판정, 회차 비교 |
| [PracticeCoordinator.kt](app/src/main/java/kr/juhyu/licensenote/PracticeCoordinator.kt) · [SessionStore.kt](app/src/main/java/kr/juhyu/licensenote/SessionStore.kt) | 연습 시작·종료·다음 목표 저장, SQLite 복원 |
| [SdkTelemetry.kt](app/src/main/java/kr/juhyu/licensenote/SdkTelemetry.kt) · [TelemetryService.kt](app/src/main/java/kr/juhyu/licensenote/TelemetryService.kt) | 차량·위치·내비 SDK, 화면과 분리된 주행 수집 |
| [PracticeUi.kt](app/src/main/java/kr/juhyu/licensenote/PracticeUi.kt) · [CourseMap.kt](app/src/main/java/kr/juhyu/licensenote/CourseMap.kt) | 준비·주행·복습 화면과 기본 내비 요청 상태. 앱 안의 지도·타일 표시는 제거했다. |
| [PracticeTest.kt](app/src/test/java/kr/juhyu/licensenote/PracticeTest.kt) | 합성 입력 기반 회귀 검사 |

Figma SVG는 앱 자산에 보관하고 AndroidSVG로 표시한다. 글꼴은 Asta Sans이며 조작 도움말 영상은 2025 그랜저 기준이다. 현재 위치 코스, 설명서 음성 문답, 차량 신호 코칭, G미터와 휴대폰 전달을 구현했다. 차량별 콘텐츠 확장과 실차 검증은 후속 범위다. [Asta Sans 라이선스](licenses/AstaSans-OFL.txt) · [AndroidSVG](https://bigbadaboom.github.io/androidsvg/)
