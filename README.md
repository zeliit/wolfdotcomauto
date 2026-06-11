# wolfdotcomauto

`Mihon` / `Tachiyomi` 계열에서 사용하는 `늑대닷컴 Auto` 커스텀 확장 저장소입니다.

이 저장소에서 관리하는 실제 변경 대상은 `src/ko/wolfdotcomauto` 모듈입니다.

## 변경 내용

- 기존 `늑대닷컴`과 충돌하지 않도록 별도 패키지명 사용
  - `eu.kanade.tachiyomi.extension.ko.wolfdotcomauto`
- 별도 확장명 사용
  - 앱 표시명: `Tachiyomi: Wolf.com Auto`
  - 소스 표시명: `늑대닷컴 Auto - ...`
- `t.me/s/wfwf_com` 텔레그램 채널에서 `wfwf###.com` 도메인 번호를 읽어 자동 갱신
- 기존 공식 `늑대닷컴` 확장과 별개로 설치 가능하도록 분리

## APK

현재 빌드 결과물:

- `src/ko/wolfdotcomauto/build/outputs/apk/release/tachiyomi-ko.wolfdotcomauto-v1.4.3-release.apk`

## 빌드

```powershell
.\gradlew.bat :src:ko:wolfdotcomauto:assembleRelease --no-daemon
```

## 설치 주의

- 이 APK는 공식 서명본이 아니라 커스텀 빌드입니다.
- 설치 후 Mihon 쪽에서 확장 신뢰 처리가 필요할 수 있습니다.
- 공식 `늑대닷컴`과는 별도 확장으로 동작합니다.
