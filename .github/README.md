# 도로주행 도우미

Pleos Connect에서 출발 준비, 코스 선택, 주행 중 안내, 주차 후 복습을 연결하는 Android 앱입니다. 운전석 안전벨트, 도어, 방향지시등, 조향과 위치 신호를 연습 기록에 사용합니다.

**심사용 0.5.3 실행**

Pleos Connect 시스템 이미지 2.1.0(Android 14, API 34, x86_64), 가로 2560 × 1440 환경에서 확인합니다. 음성을 시연할 때는 에뮬레이터의 오디오 입출력을 켭니다. 저장소와 소스·APK 압축본의 설치 경로는 `apk/도로주행도우미_release.apk`입니다. [설치 APK](../apk/도로주행도우미_release.apk) · [0.5.3 비공개 릴리스](https://github.com/jhn-oh/dorojuhaeng-doumi/releases/tag/v0.5.3)

1. Pleos 에뮬레이터를 부팅하고 대상 기기의 시리얼을 `adb devices`로 확인합니다. 새 시험 기기에 `adb -s <serial> install -g apk/도로주행도우미_release.apk`로 설치합니다. 이전 개발용 디버그 APK는 서명이 다릅니다.
2. 차량 앱 목록에서 **도로주행 도우미**를 엽니다. 정차·P단에서 첫 안내를 읽고 차량 접근 권한을 허용합니다. 안전벨트 상태는 차량 신호가 들어오는 동안 그대로 반영됩니다.
3. **계기판 읽기 → 출발 점검 계속하기**로 돌아와 거울·도어 점검을 마치고 코스를 선택합니다. 주행 후 정차·P단에서 **기록 종료**를 누르면 결과가 표시됩니다. 재연습할 구간이 있을 때 같은 구간의 신호를 다음 회차와 비교할 수 있습니다. 차량에서 보이는 **QR로 받기**는 별도 서버 없이 작동합니다.

소스 프로젝트는 `pleos-app`에 있습니다. Android SDK 35, JDK 17, Gradle Wrapper 8.10.2를 사용합니다. `pleos-app`에서 `gradlew.bat :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease`로 빌드하고 검사합니다. 새로 빌드한 unsigned APK는 본인 키로 서명해야 합니다. 압축본에 동봉된 APK는 서명되어 있습니다.

[앱 소스와 빌드 방법](../pleos-app/README.md) · [개발 요구사항](../면허연습노트/개발%20요구사항.md) · [화면과 문구](../면허연습노트/상황별%20화면과%20문구.md) · [Figma](https://www.figma.com/design/AabvIHA3LX6ZfKEd4rtKHi?node-id=25-2)
