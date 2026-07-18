# OCR Line Geometry Exposure (`ocrGeometry`)

**Spec version:** 1.0
**최초 작성일:** 2026-07-18
**상위 문서:** [`api-contract.md`](./api-contract.md), [`ocr-orientation-correction.md`](./ocr-orientation-correction.md) v2.0
**관련 결정:** ADR-003 (패키지 경계), ADR-006 (OCR intent)
**관련 스펙:** [`portrait-rotation-detection.md`](./portrait-rotation-detection.md) v1.3, [`ios-geometry-rotation-routing.md`](./ios-geometry-rotation-routing.md), [`../notes/platform-asymmetries.md`](../notes/platform-asymmetries.md)
**플랫폼:** iOS + Android
**목표 릴리스:** 0.7.0

## 현재 상태: Proposed

## 목적

캡처 후 결과 화면에서 "스캐너 불빛이 위에서 아래로 지나간 뒤, 인식된 텍스트 영역이 박스로 표시되는" Clova OCR류 UX를 소비 앱이 구현할 수 있게 한다.
패키지는 **라인 단위 텍스트 영역 좌표(geometry)라는 이미지 프리미티브만** 반환한다.
스캔라인 애니메이션·박스 리빌·"Copy All" 같은 연출과 인터랙션은 전부 소비 앱 JS 레이어의 몫이다 — ADR-003의 "image primitives only" 경계를 그대로 유지한다.

기반 사실: 양 플랫폼 OCR 프로세서는 이미 라인 bounding box를 내부적으로 순회하고 있다 (`RNOcrProcessor.m:233`의 `obs.boundingBox`, `OcrProcessor.kt:215`의 `line.boundingBox`).
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

1. **캡처 지점**: Pass 0에서 `line.boundingBox`(px, top-left 원점), `line.text`, `line.confidence`를 읽어 유효한 텍스트와 box가 모두 있는 항목만 `OcrProcessor.Result.lines: List<OcrLineData>`에 담는다. OCR 입력이 processed JPEG 파일이므로 좌표는 **pre-autoRotate 프레임**이다.
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
4. delegate의 결과 dict에 `ocrLines` 직렬화를 추가한다.

### 회전 리매핑 테이블

원본 프레임 `W × H`의 box `(x, y, w, h)`에 회전 `θ`를 적용해 새 프레임 좌표로 옮긴다 (top-left 원점 기준, θ는 픽셀이 받은 시계 방향 회전):

| θ    | 새 프레임 | 새 box                         |
| ---- | --------- | ------------------------------ |
| 90°  | `H × W`   | `(H − y − h, x, h, w)`         |
| 180° | `W × H`   | `(W − x − w, H − y − h, w, h)` |
| 270° | `H × W`   | `(y, W − x − w, h, w)`         |

**방향 검증 필수**: `rotateFileInPlace`(Android, `Matrix.postRotate` 기반)와 `cgImageByRotating:`(iOS, CI 좌표계 `CGAffineTransformMakeRotation`)의 실제 회전 방향이 top-left 기준 시계 방향과 일치하는지는 **코드 추론이 아니라 기기 fixture로 확정**한다 (`platform-asymmetries.md`의 rotationDegrees 방향 항목과 대조).
방향이 반대로 판명되면 90°/270° 행을 서로 교환하면 된다 — 계약(최종 출력 공간 불변식)은 변하지 않는다.

## Non-goals

- **라이브 프리뷰 오버레이 없음.** GMS Document Scanner와 `VNDocumentCameraViewController`는 닫힌 시스템 UI라 주입 불가. 커스텀 카메라 파이프라인은 ADR-001을 뒤집는 별도 메이저 작업이다.
- **word/element/character 단위 geometry 없음.** 필요 근거가 생기면 별도 스펙.
- **RN 오버레이 컴포넌트 제공 없음.** 렌더링은 소비 앱 몫 (example 앱 데모는 검증 도구로만 제공).
- **텍스트 의미 해석 없음** (금액·상호 파싱 등) — ADR-003.

## 검증 계획

1. **JS**: `src/__tests__/`에 `ocrGeometry` 기본값 전파(`DEFAULT_SCAN_OPTIONS` → native 호출 인자) 테스트 추가. → verify: `yarn test`
2. **타입**: `Required<ScanReceiptOptions>` 컴파일 강제 확인. → verify: `yarn typecheck`
3. **기기 매트릭스** (수동, [`threshold-calibration.md`](./threshold-calibration.md)의 fixture 활용): 플랫폼(2) × 콘텐츠 회전(0/90/180/270) × `autoRotate`(on/off) — 각 셀에서 example 앱 오버레이가 실제 텍스트 위치와 시각적으로 정합하는지 확인. 90°/270° 셀이 리매핑 방향 검증을 겸한다.
4. **example 앱**: 결과 화면에 스캔라인 스윕 후 `frame.y` 순 박스 리빌 데모를 추가한다 — 데모이자 3의 검증 도구.

## 호환성

- Additive 변경만 있다. 기존 호출·결과 형태 불변, breaking change 없음.
- web fallback(`src/scan.tsx`)은 `ocrLines`를 반환하지 않는다 (`undefined`).
- 문서: `README.md` API 표와 `docs/specs/api-contract.md`에 `ocrGeometry` / `OcrLine` 반영, `platform-asymmetries.md`에 좌표 변환 비대칭(정규화 bottom-left vs px top-left) 항목 추가.
