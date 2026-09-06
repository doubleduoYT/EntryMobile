# EntryMobile

Entry 2.1.35 오프라인 에디터를 Android WebView + Kotlin 네이티브 브리지로 포팅하는 프로젝트야.

## 현재 구현

- Android WebView 네이티브 셸
- 공식 `entrylabs/entry-offline`의 Entry **2.1.35** 소스를 정확한 커밋으로 빌드해 APK에 포함
- Electron preload API 일부를 `mobile-preload.js`로 에뮬레이션
- Electron IPC의 `loadProject` / `saveProject` / `resetDirectory` / 기본 시스템 호출을 Android bridge로 교체
- Android Storage Access Framework 파일 열기/저장
- `.ent` 파일의 **tar + gzip** 압축 해제/저장
- `temp/project.json` 로드 및 PC판과 유사한 media `fileurl` 경로 변환
- WebViewAssetLoader로 압축 해제한 프로젝트 이미지/사운드를 안전하게 제공
- `.ent` 파일 VIEW intent 진입점
- 가로 화면 및 기본 터치 대응 CSS
- GitHub Actions 디버그 APK 자동 빌드

## 아직 미완성

- Entry Hardware / node-serialport 대체 (Android USB/BLE 브리지 필요)
- PC판 FFmpeg 기반 오디오 변환 완전 대체
- 외부 이미지/사운드 import 결과 객체를 PC판과 100% 동일하게 만드는 작업
- 구형 XML block 형식 프로젝트의 BlockConverter 포팅
- 카메라/마이크 runtime permission UI
- 일부 Electron IPC 채널 (Excel, object import/export, block capture 등)
- 스마트폰 세로 UI 최적화

## APK 빌드

가장 쉬운 방법은 GitHub의 **Actions → Build EntryMobile debug APK → Run workflow**야. 빌드가 끝나면 `EntryMobile-2.1.35-alpha-debug` artifact 안에 APK가 생겨.

로컬 Linux/macOS/Git Bash에서는:

```bash
npm install -g yarn@1.22.22
./tools/prepare-entry-web.sh
gradle :app:assembleDebug
```

`prepare-entry-web.sh`는 공식 Entry 2.1.35 오프라인 소스 커밋 `b238f8aeddcd748c035d2d3b6580cc23616dfaf2`를 체크아웃하고 Electron native postinstall은 건너뛴 채 renderer bundle을 만든다. 이후 PC판 `main.html`의 상대경로 구조가 유지되도록 필요한 renderer resources와 주요 web dependencies를 `app/src/main/assets/web` 아래에 배치한다.

## 설계

```text
EntryJS / Entry offline renderer
             │
       mobile-preload.js
             │
      JavascriptInterface
             │
 Kotlin EntryBridge / ProjectSession
       │                 │
 Android SAF       .ent tar.gz I/O
```

`.ent` 파일을 열면 `temp/project.json`과 리소스를 앱 전용 세션 폴더에 해제하고, 프로젝트 리소스 URL을 `https://appassets.androidplatform.net/session/...` 형태로 바꿔 WebView가 읽는다. 저장할 때는 다시 `temp/...` 경로로 되돌려 `temp` 폴더를 gzip tar로 패킹한다.

> 이 저장소는 아직 alpha 포팅 단계야. 첫 목표는 **PC Entry 2.1.35에서 저장한 .ent를 Android에서 열고, 수정 후 다시 PC에서 열 수 있는 것**이야.
