# Pleos Routines

**차량 상태와 시간·위치에 맞춰 안내와 공조 동작을 실행하는 Pleos Connect용 루틴 앱입니다.**

사용자가 ‘언제 실행할지’, ‘어떤 조건에서 실행할지’, ‘무엇을 실행할지’를 조합하여 차량용 자동화를 구성합니다. 반복하는 공조 설정을 루틴으로 묶고, 실행 결과를 화면에서 확인할 수 있습니다.

## 주요 기능

| 구분 | 지원 내용 |
| --- | --- |
| 실행 계기 | 수동 실행, 배터리 기준치 이하, 지정 시간·요일, 저장 위치 진입·이탈, P단 진입·이탈 |
| 추가 조건 | A/C 상태, 공조 전원 상태, 배터리 범위, 주차 상태, 성에 제거 꺼짐 |
| 실행 동작 | 안내 표시, 설정 온도 변경, A/C 켜기, A/C 끄고 기존 송풍 유지, 송풍 세기 변경 |
| 복합 동작 | 하나의 루틴에 여러 동작을 구성하고 순서대로 실행 |
| 관리 | 루틴 추가·편집·삭제, 활성화·비활성화, 로컬 저장 |
| 실행 정보 | 대기 이유, 실행 횟수, 최근 실행 시각, 최근 실행 기록 최대 20건 |

차량 SDK가 제공하는 기능과 지원 범위를 조회하여 공조 영역, 온도 및 송풍 세기 선택에 반영합니다. 여러 동작 중 하나가 실패하면 이후 동작을 중단하고 결과를 기록합니다.

## 기본 루틴 템플릿

| 템플릿 | 구성 |
| --- | --- |
| 출근 쾌적 | 평일 08:00에 22°C 설정 → A/C 켜기 → 안내 표시 |
| 에어컨 자동 건조 | P단 진입 시 A/C를 끄고 기존 송풍 유지 → 안내 표시 |
| 도착 전 송풍 | 설정 위치 진입 시 추가 조건을 확인하여 A/C를 끄고 기존 송풍 유지 |
| 스마트 ECO 안내 | 배터리 30% 이하에서 충전 계획과 ECO 모드 활용 안내 |
| 출발 준비 | 수동 실행으로 23°C 설정 |

템플릿은 비활성 상태로 제공됩니다. 공조 대상 영역과 위치 등 필요한 설정을 완료한 후 활성화합니다. ‘에어컨 자동 건조’는 A/C를 끄고 송풍을 유지하는 동작이며, 건조 완료 감지나 일정 시간 후 송풍 종료 기능을 의미하지 않습니다. ECO 모드는 안내만 제공하며 직접 변경하지 않습니다.

## 개발 환경

다음은 저장소의 빌드 설정에 기재된 값입니다.

| 항목 | 설정 |
| --- | --- |
| 언어 / UI | Kotlin / Android XML Views, Material Components |
| Application ID | `com.example.pleos_routines` |
| 앱 버전 | `1.0` (`versionCode = 1`) |
| Android SDK | `compileSdk = 37`, `targetSdk = 37`, `minSdk = 28` |
| Android Gradle Plugin | `9.5.1` |
| Gradle Wrapper | `9.6.0` |
| Gradle Daemon JVM | Java 25 (`gradle-daemon-jvm.properties`) |
| Java 소스·타깃 호환성 | Java 11 |
| Pleos Vehicle SDK | `ai.pleos.playground:Vehicle:2.3.2` |
| Pleos FusedLocation SDK | `ai.pleos.playground:FusedLocation:1.1.3` |

Pleos SDK 저장소는 `settings.gradle.kts`에 등록되어 있습니다. 이 앱은 `android.hardware.type.automotive`를 필수로 선언하므로 Pleos Connect 차량 환경 또는 호환되는 차량용 에뮬레이터를 사용합니다.

## 테스트 환경 및 필요한 설정

### 실제 사용한 환경 — 제출 전 입력

| 항목 | 값 |
| --- | --- |
| Pleos Connect 에뮬레이터 버전 | **[실제 사용한 버전 입력]** |
| 에뮬레이터 화면 규격 | **[해상도 및 필요 시 DPI 입력]** |
| Android Studio 버전 | **[실제 사용한 버전 입력]** |
| 실제 테스트 결과 | **[확인한 기능과 결과 입력]** |

위 항목은 소스코드만으로 확인할 수 없으므로 실제 개발·실행 환경에 맞게 작성해야 합니다. 아래 절차는 실행 및 검증 방법이며, 테스트 통과 결과를 의미하지 않습니다.

### 실행 전 설정

1. 프로젝트 빌드 설정을 지원하는 Android Studio와 Android SDK Platform 37을 준비합니다.
2. Pleos Connect 에뮬레이터를 실행하고 차량 서비스 및 Virtual Console 연결 상태를 확인합니다.
3. 루틴 편집과 수동 실행을 위해 기어를 P단으로 설정합니다.
4. 위치 루틴을 사용할 때는 앱 화면의 **위치 권한** 버튼에서 권한을 허용합니다.
5. 공조 동작에 필요한 차량 권한과 해당 영역의 기능 지원 여부를 확인합니다.

Manifest에는 `CAR_ENERGY`, `FUSED_LOCATION`, `CAR_CLIMATE`, `CONTROL_CAR_CLIMATE`, `CAR_DRIVE` 관련 Pleos 권한이 선언되어 있습니다. 권한 거부 또는 차량 서비스 연결 실패 시 화면의 상태 안내를 확인합니다.

## 실행 방법

1. 저장소를 복제합니다.

   ```bash
   git clone https://github.com/Akjxc/pleos-routines.git
   cd pleos-routines
   ```

2. Android Studio에서 프로젝트 루트 폴더를 열고 Gradle Sync를 완료합니다.
3. 실행 대상으로 Pleos Connect 에뮬레이터를 선택하고 `app`을 실행합니다.
4. 화면 상단에서 배터리·기어·공조 연결 상태를 확인합니다. 필요하면 **다시 연결**을 누릅니다.
5. P단에서 **+ 루틴 추가** 또는 템플릿의 **편집**을 선택합니다.
6. 실행 계기, 추가 조건, 실행 동작 및 공조 대상 영역을 설정하고 저장·활성화합니다.
7. 앱 화면을 열어둔 상태에서 조건을 변경하여 대기 이유와 실행 기록을 확인합니다.

## APK

**현재 이 README 작성 기준으로 저장소에 APK가 등록되어 있지 않습니다.**

제출용 release APK를 생성한 후 아래 위치에 업로드하고, 이 절의 상태와 경로를 실제 파일에 맞게 갱신합니다.

- 업로드 예정 위치: `apk/팀명_PleosRoutines_release.apk`
- 위 경로는 예정 경로이며 현재 다운로드 링크가 아닙니다.

Android Studio의 서명된 앱 생성 메뉴(`Generate Signed App Bundle or APK`)에서 APK와 release 빌드를 선택하여 서명된 APK를 생성할 수 있습니다. 서명 키와 비밀번호는 저장소에 포함하지 않습니다.

## 검증 방법

Windows PowerShell에서 프로젝트 루트를 기준으로 실행합니다.

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat connectedDebugAndroidTest
```

macOS / Linux에서는 `bash ./gradlew testDebugUnitTest`, `bash ./gradlew connectedDebugAndroidTest`를 사용합니다. 계측 테스트는 연결된 호환 에뮬레이터가 필요하며, 차량 연동 테스트는 SDK 서비스와 권한 설정도 필요합니다.

저장소에는 루틴 엔진, 동작 실행기 및 편집·저장·차량 SDK 연동 등을 위한 테스트 코드가 포함되어 있습니다. 테스트 결과는 실제 실행 후 위 테스트 환경 표에 기록합니다.

## 현재 동작 범위와 제한

- 자동 실행은 앱 화면이 활성 상태인 동안 동작합니다. 백그라운드 상시 실행, 앱 종료 후 실행 및 차량 시동 전 원격 실행은 지원하지 않습니다.
- 루틴 추가·편집·삭제, 활성 상태 변경 및 수동 실행은 P단 확인 후 가능합니다. 자동 공조 동작은 주행 기어에서도 실행될 수 있습니다.
- 성에 제거 상태 및 공조 상태 확인에 따라 공조 요청이 제한될 수 있습니다. 미지원 기능이나 확인되지 않은 상태를 성공으로 처리하지 않습니다.
- 위치·기어 루틴은 앱 실행 후 관측한 상태 변화를 기준으로 동작합니다. 최초 위치 수신이나 최초 기어 확인만으로 진입·이탈 이벤트가 발생하지는 않습니다.
- 시간 루틴은 지정된 분에 앱이 활성 상태여야 합니다. 지나간 실행 시각을 소급하여 실행하지 않습니다.
- 배터리 루틴은 저전력 구간에서 반복 실행을 방지하며, 기준치보다 3%p 이상 회복하면 다음 감지를 준비합니다.
- 루틴은 최대 30개까지 저장할 수 있습니다. 실제 사용 가능한 공조 기능과 범위는 연결된 차량·에뮬레이터의 지원 상태에 따라 달라집니다.

## 코드 구성

| 위치 | 역할 |
| --- | --- |
| `MainActivity.kt` | 화면 표시, 차량 연결, 루틴 편집·실행 흐름 |
| `model/Routine.kt` | 루틴·조건·동작 모델, 입력 검증, 기본 템플릿 |
| `routine/RoutineEngine.kt` | 실행 계기와 추가 조건 평가, 중복 감지 방지 |
| `routine/RoutineActionRunner.kt` | 여러 동작의 순차 실행과 결과 취합 |
| `data/RoutineStore.kt` | 루틴 및 실행 기록 로컬 저장 |
| `ui/RoutineEditorDialog.kt` | 루틴 편집 화면 |
| `vehicle/PleosVehicleGateway.kt` | 차량 기능 조회, 배터리·공조 상태 조회 및 공조 제어 |
| `vehicle/ParkingStateSource.kt` | 주차 상태 확인 |
| `vehicle/PleosLocationSource.kt` | 차량 위치 수신 |

위 경로는 `app/src/main/java/com/example/pleos_routines/` 기준입니다.
