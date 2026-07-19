# OCR Line Geometry Exposure (`ocrGeometry`)

**Spec version:** 1.0
**최초 작성일:** 2026-07-18
**상위 문서:** [`api-contract.md`](./api-contract.md), [`ocr-orientation-correction.md`](./ocr-orientation-correction.md) v2.0
**관련 결정:** ADR-003 (패키지 경계), ADR-006 (OCR intent)
**관련 스펙:** [`portrait-rotation-detection.md`](./portrait-rotation-detection.md) v1.3, [`ios-geometry-rotation-routing.md`](./ios-geometry-rotation-routing.md), [`../notes/platform-asymmetries.md`](../notes/platform-asymmetries.md)
**플랫폼:** iOS + Android
**목표 릴리스:** 0.7.0

## 현재 상태: Implemented (0.7.0) — 기기 QA **부분 완료**

미측정으로 남은 셀이 하나 있다: **iOS · 180° 리매핑** (2026-07-19 2차 QA를 Android만 실행). 아래 두 표의 셀 단위 결과를 그대로 읽을 것 — "완료"로 뭉뚱그리지 않는다.

JS 계약과 양 플랫폼 구현이 들어갔고, CW 리매핑 공식은 `OcrGeometryTest`(JUnit 22케이스)가 고정한다.
2026-07-18 실기기 QA에서 아래 셀을 확인했다 (모두 example 앱 오버레이 육안 정합 + `줄 수` = `OCR 영역 좌표 (N줄)` 일치):

| 셀                                | 결과 | 근거                                                                                 |
| --------------------------------- | ---- | ------------------------------------------------------------------------------------ |
| iOS 카메라 · 항등 (`d=0`)         | ✅   | 신분증 캡처. pass 프레임 = 출력 프레임 가정이 실제로 성립함을 확인                   |
| iOS 갤러리 · 항등 (`d=0`)         | ✅   | 원근 보정 후에도 정합. 62줄 영수증에서 62박스                                        |
| Android 갤러리 · 리매핑 (`d=270`) | ✅   | autoRotate가 실제로 픽셀을 회전시킨 상태에서 정합 — 축이 바뀌는 90/270 분기를 태움   |
| 박스 누락                         | ✅   | 양 플랫폼 4개 캡처 모두 `ocrLines.length == ocrQuality.lineCount` (52/54/57/59/62줄) |

~~**나머지 각도는 "미검증"이 아니라 "도달 불가"다.** autoRotate 검출이 그 값을 내놓지 못하기 때문이다 — iOS는 현재 어떤 회전도 검출하지 않아 `d`가 항상 0이고(그래서 iOS 역회전 분기는 실행 자체가 불가능), Android는 가로 콘텐츠에 항상 270°만 적용한다.~~

⚠️ **2026-07-19 해소.** 위 제약은 상류 autoRotate의 상태였고, 각도 기반 재설계([`ocr-angle-rotation-detection.md`](./ocr-angle-rotation-detection.md))가 그 상태를 바꿨다. 이제 검출이 90·180·270을 모두 내놓으므로 남아 있던 셀에 실제로 도달한다.

| 셀                            | 결과 | 근거                             |
| ----------------------------- | ---- | -------------------------------- |
| 양 플랫폼 · 90° / 270° 리매핑 | ✅   | 2026-07-19 QA, 오버레이 정합     |
| Android · 180° 리매핑         | ✅   | 2026-07-19 2차 QA                |
| iOS · 180°                    | —    | 미측정 (2차 QA는 Android만 실행) |

Android 회전 경로는 리매핑을 **거치지 않는 것이 정상 경로**가 됐다는 점도 함께 바뀌었다 — 회전 후 재인식이 박스를 출력 프레임에서 직접 재므로, `rotateClockwise`는 그 재인식이 실패했을 때만 쓰인다. iOS는 종전대로다.

## 목적

캡처 후 결과 화면에서 "스캐너 불빛이 위에서 아래로 지나간 뒤, 인식된 텍스트 영역이 박스로 표시되는" Clova OCR류 UX를 소비 앱이 구현할 수 있게 한다.
패키지는 **라인 단위 텍스트 영역 좌표(geometry)라는 이미지 프리미티브만** 반환한다.
스캔라인 애니메이션·박스 리빌·"Copy All" 같은 연출과 인터랙션은 전부 소비 앱 JS 레이어의 몫이다 — ADR-003의 "image primitives only" 경계를 그대로 유지한다.

기반 사실: 양 플랫폼 OCR 프로세서는 이미 라인 bounding box를 내부적으로 순회하고 있었다 (`RNOcrProcessor`의 `meanBoxAspectFromResults:`가 `obs.boundingBox`를, `OcrProcessor.lineAspectOf`가 `line.boundingBox`를 aspect 계산에 사용).
이 스펙은 그 데이터를 버리지 않고 브리지 너머로 직렬화하는 additive 변경이다.

## API 계약 (`src/types.ts`)

### 옵션

```ts
export type ScanReceiptOptions = {
  // …기존 필드…
  /**
   * `true`이면 각 페이지에 라인 단위 OCR geometry(`ReceiptImage.ocrLines`)를
   * 포함한다. `ocr === true`일 때만 유효 — OCR 패스가 없으면 측정할 대상이 없다.
   * @defaultValue `false`
   */
  ocrGeometry?: boolean;
};
```

- 이름·게이팅 문구는 기존 `ocrFloor` / `autoRotate`("only when `ocr === true`") 컨벤션을 따른다.
- `DEFAULT_SCAN_OPTIONS`는 `Required<ScanReceiptOptions>`이므로 `ocrGeometry: false` 추가가 컴파일 타임에 강제된다.
- opt-in인 이유: 긴 영수증은 라인 수가 수백 개까지 가능하고, geometry가 필요 없는 기존 소비자에게 페이로드를 물리지 않는다.

### 결과

```ts
/** 라인 하나의 인식 텍스트와 위치. 좌표는 최종 출력 JPEG 픽셀 공간. */
export type OcrLine = {
  /** 이 라인의 인식 텍스트. */
  text: string;
  /** axis-aligned bounding box. 원점 top-left, 단위 px. */
  frame: { x: number; y: number; width: number; height: number };
  /** 라인 confidence `[0, 1]`. 플랫폼이 제공할 때만. */
  confidence?: number;
};

export type ReceiptImage = {
  // …기존 필드…
  /** `ocr && ocrGeometry`일 때만 존재. OCR 엔진의 인식 순서를 유지한다. */
  ocrLines?: OcrLine[];
};
```

- **라인 단위 고정.** block은 오버레이가 지나치게 뭉개지고 element(단어)는 페이로드 대비 연출 이득이 없다. 두 플랫폼의 공통 분모이자 Clova류 연출의 자연 단위가 라인이다.
- `ocrLines`는 OCR 엔진이 반환한 인식 순서를 유지하되, 비어 있는 텍스트나 유효한 frame을 만들 수 없는 라인은 제외한다. Android의 `line.boundingBox`는 nullable이므로 `ocrText`와 배열 길이 또는 인덱스 대응을 계약하지 않는다. 각 박스에 대응하는 텍스트는 `OcrLine.text`를 사용한다.
- codegen 영향 없음: Phase 1 TurboModule spec은 options/result를 `Object`로 선언하므로 `NativeReceiptScanner.ts` 변경이 필요 없다.

### 좌표계 계약 (불변식)

`frame`은 **반환되는 `uri`의 JPEG 픽셀 공간** — 즉 `ReceiptImage.width` × `ReceiptImage.height`와 같은 공간 — 이며 원점은 top-left다.
불변식: 모든 라인에 대해 `0 ≤ x`, `0 ≤ y`, `0 < width`, `0 < height`, `x + width ≤ ReceiptImage.width`, `y + height ≤ ReceiptImage.height`다.
직렬화 직전에 JPEG 경계와 교차하도록 frame을 클램프하고, 교차 결과의 너비나 높이가 0이면 해당 라인을 제외한다.
소비 앱은 표시 크기 대비 `displayedWidth / width` 스케일만 곱하면 된다.

## 플랫폼 구현 설계

네 개 call site 전부에 동일하게 적용한다: Android camera/gallery (`ReceiptScannerModule.kt`), iOS camera (`RNDocumentCameraDelegate.m`) / gallery (`RNGalleryPickerDelegate.m`).

### Android (`OcrProcessor.kt`, `ResultBuilder.kt`)

1. **캡처 지점**: Pass 0에서 `line.boundingBox`(px, top-left 원점), `line.text`, `line.confidence`를 읽어 유효한 텍스트와 box가 모두 있는 항목만 `OcrProcessor.OcrResult.lines: List<OcrProcessor.Line>`에 담는다 (`linesOf`). OCR 입력이 processed JPEG 파일이므로 좌표는 **pre-autoRotate 프레임**이다. 옵션과 무관하게 항상 수집하고, 직렬화 여부만 모듈이 `ocrGeometry`로 가른다.
2. **리매핑**: `applyAutoRotateIfNeeded`가 `rotateFileInPlace(file, rotationDegrees, quality)`로 픽셀을 회전시킨 경우, 각 box에 **픽셀이 받은 것과 동일한 회전**을 적용한 뒤 직렬화한다 (아래 리매핑 테이블). `autoRotate == false`이거나 `rotationDegrees == 0`이면 identity.
3. ML Kit Korean은 rotation-invariant(v1.3)이므로 회전된 콘텐츠라도 box는 입력 프레임 기준의 실제 레이아웃을 반영한다 — autoRotate 미적용 출력에도 오버레이 정합이 유지된다.
4. `ResultBuilder.buildImage`에 `ocrLines: WritableArray` 직렬화를 추가한다.

### iOS (`RNOcrProcessor.{h,m}`, 두 delegate)

1. **캡처 지점**: 최종 텍스트를 만든 패스의 `VNRecognizedTextObservation` 배열에서 유효한 top candidate의 `string`, `confidence`와 `boundingBox`(normalized, bottom-left 원점)를 `RNOcrResult.lines`로 담는다. 선택 회전의 accurate 재실행이 실패해 fast 결과로 폴백한 경우에는 해당 fast 패스 배열을 사용한다.
2. **정규화 → 픽셀 변환**: `UIImage.size`(point)가 아니라 해당 OCR 패스에 전달된 `CIImage.extent`의 픽셀 크기 `W_p × H_p`를 기준으로
   `x = box.minX × W_p`, `y = (1 − box.maxY) × H_p`, `width = box.width × W_p`, `height = box.height × H_p`.
3. **프레임 정합 케이스** (iOS Vision은 rotation-variant — 최종 텍스트를 만든 패스가 회전된 픽셀을 봤을 수 있다):
   - `autoRotate == true && rotationDegrees != 0`: 출력 JPEG은 `cgImageByRotating:`으로 회전된 픽셀 = 그 패스가 본 픽셀. 변환 후 **identity**.
   - `autoRotate == false && rotationDegrees != 0`: 출력은 미회전 픽셀인데 box는 회전 프레임 기준. **역회전 리매핑**을 적용해 미회전 프레임으로 되돌린다.
   - `rotationDegrees == 0`: identity.
4. **출력 프레임으로 재정규화**: 위 변환은 Vision이 측정한 pass 프레임 기준이고, 계약이 요구하는 것은 인코딩된 출력(`processed.width/height`) 기준이다. 둘은 같을 것으로 기대되지만 — `RNImageProcessor.normalizeOrientation:`이 `UIGraphicsImageRenderer`(화면 scale)로 재렌더할 수 있어 `UIImage.size`(point)와 픽셀이 갈리는 지점이 있다 — 가정으로 두지 않고 `linesByRotating:...outputSize:`가 두 프레임의 축별 비율로 rescale한 뒤 출력 경계로 clamp한다. 기대 케이스에서는 배율이 1이라 무연산이다. 그래서 이 호출은 `processImage:`가 끝난 뒤에 위치한다.
5. delegate의 결과 dict에 `ocrLines` 직렬화를 추가한다.

### 회전 리매핑 테이블

원본 프레임 `W × H`의 box `(x, y, w, h)`에 회전 `θ`를 적용해 새 프레임 좌표로 옮긴다 (top-left 원점 기준, θ는 픽셀이 받은 시계 방향 회전):

| θ    | 새 프레임 | 새 box                         |
| ---- | --------- | ------------------------------ |
| 90°  | `H × W`   | `(H − y − h, x, h, w)`         |
| 180° | `W × H`   | `(W − x − w, H − y − h, w, h)` |
| 270° | `H × W`   | `(y, W − x − w, h, w)`         |

이 표는 **CW 한 방향만** 정의한다. 구현은 양 플랫폼이 같은 CW 헬퍼를 쓴다 — `OcrGeometry.rotateClockwise`(Android), `+[RNOcrGeometry rectByRotating:frameSize:clockwiseDegrees:]`(iOS) — 그리고 `OcrGeometryTest`가 이 공식을 고정한다.

**플랫폼 방향 차이는 각도가 아니라 "언제 · 어느 프레임에" 적용하느냐로 흡수된다.** `platform-asymmetries.md` §3.1이 기록한 대로 `rotationDegrees=d`는 Android(`Matrix.postRotate`)에서 CW, iOS(`CGAffineTransformMakeRotation`)에서 CCW를 뜻한다. 그 결과:

| 플랫폼  | OCR이 박스를 잰 프레임                  | remap 조건                          | 넘기는 CW 각도 |
| ------- | --------------------------------------- | ----------------------------------- | -------------- |
| Android | autoRotate _전_ JPEG (`processed` 치수) | autoRotate가 **실제로 회전했을 때** | `d`            |
| iOS     | 선택된 pass 프레임 (이미 CCW-`d` 상태)  | autoRotate가 **회전하지 않았을 때** | `d`            |

remap 조건이 두 플랫폼에서 서로 **반대**라는 점이 이 설계의 유일한 반직관 지점이다 — Android는 OCR을 회전 전 파일에 돌리고, iOS는 이미 회전된 pass 결과를 쓰기 때문이다.
iOS가 `360 − d`가 아니라 `d`를 넘기는 이유는 CCW-`d`를 되돌리는 연산이 곧 CW-`d`이기 때문이며, 이 왕복 항등식은 `OcrGeometryTest`의 round-trip 케이스가 검증한다.

## Non-goals

- **라이브 프리뷰 오버레이 없음.** GMS Document Scanner와 `VNDocumentCameraViewController`는 닫힌 시스템 UI라 주입 불가. 커스텀 카메라 파이프라인은 ADR-001을 뒤집는 별도 메이저 작업이다.
- **word/element/character 단위 geometry 없음.** 필요 근거가 생기면 별도 스펙.
- **RN 오버레이 컴포넌트 제공 없음.** 렌더링은 소비 앱 몫 (example 앱 데모는 검증 도구로만 제공).
- **텍스트 의미 해석 없음** (금액·상호 파싱 등) — ADR-003.

## 검증 계획

1. ✅ **JS**: `ocrGeometry` 기본값 전파와 `ocrLines` 통과를 `src/__tests__/index.test.tsx`가 검증. → `yarn test` (21케이스)
2. ✅ **타입**: `Required<ScanReceiptOptions>` 컴파일 강제 + 루트 `OcrLine` re-export를 테스트의 type-only import가 확인. → `yarn typecheck`
3. ✅ **CW 공식**: `OcrGeometryTest` 22케이스 — 리매핑 10건(0/90/180/270 각 행을 비대칭 rect로 축 교환까지 검출, 90→270 왕복 항등, 각도 정규화, clamp 트림·전량 탈락·zero-area 탈락) + 각도 라우팅 12건(1/4 회전 양자화와 동률 규칙, 보정각, 최빈값 집계·기권). → `./gradlew :react-native-receipt-scanner:testDebugUnitTest`
4. 🟡 **기기 매트릭스 — 부분** (수동, 2026-07-18 / 2026-07-19): 위 상태 표의 셀을 확인. 1차에는 autoRotate 검출이 내놓는 각도가 제한돼 전체 매트릭스(2 × 4 × 2)를 실행할 수 없었으나, 각도 기반 재설계 이후 2차에서 90·180·270이 모두 도달 가능해졌다. 공식 자체는 3이 고정하므로 여기서 잡힐 실패는 배선 오류였을 것이다.
   **미완:** iOS · 180° (2차 QA는 Android만 실행). 이 셀이 닫혀야 이 항목이 ✅가 된다.
5. ✅ **example 앱**: 결과 화면에 스캔라인 스윕 후 `frame.y` 순 박스 리빌 데모 (`OcrGeometryPreview`) — 데모이자 4의 검증 도구. `resizeMode="contain"` 레터박싱을 감안해 `containFit`으로 그려진 사각형 기준으로 배치한다.

## 호환성

- Additive 변경만 있다. 기존 호출·결과 형태 불변, breaking change 없음.
- web fallback(`src/scan.tsx`)은 `ocrLines`를 반환하지 않는다 (`undefined`).
- 좌표 자료형에 잔여 비대칭이 있다: Android는 ML Kit `Rect`에서 온 정수 픽셀, iOS는 정규화 값 × 픽셀 크기라 소수 픽셀. JS `OcrLine.frame`이 `number`이므로 계약 위반이 아니고 오버레이 배치에도 영향이 없어 정규화하지 않는다.
- 문서: `README.md` API 표와 `docs/specs/api-contract.md`에 `ocrGeometry` / `OcrLine` 반영 완료, `platform-asymmetries.md` §2.3(좌표계 해소) · §3.1(CW 정준화) 갱신 완료.
