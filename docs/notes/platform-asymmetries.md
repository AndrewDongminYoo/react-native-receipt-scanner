# Platform Asymmetries (Living Document)

**Status:** Living
**최초 작성일:** 2026-05-10
**목적:** iOS와 Android 구현 사이에서 발견된 동작/의미론/인터페이스의 차이를 한 자리에 모은다. 새 차이가 발견되면 이 문서에 즉시 추가하고, 알고리즘이나 새 기능을 설계할 때 _먼저_ 이 표를 참고한다.

문서 운영 원칙:

- 새 비대칭 발견 → 이 문서에 행 추가 (PR 단위)
- 향후 통일 예정 항목은 "Resolution path"에 계획 기록
- 의도된 비대칭(통일 안 함)은 "Resolution path: 비범위" + 이유 명시
- 사용자 영향이 있는 비대칭은 `api-contract.md`에도 cross-reference

## 1. EXIF / 이미지 메타데이터

### 1.1 출력 JPEG의 EXIF 보존

| 항목                  | iOS                                             | Android                                            |
| --------------------- | ----------------------------------------------- | -------------------------------------------------- |
| 출력 JPEG에 EXIF 작성 | ✅ `CGImageDestinationAddImage`로 보존          | ❌ `Bitmap.compress(JPEG, …)`만 사용 — EXIF 미작성 |
| 결과                  | 파일 자체에 EXIF 존재 → server-side 재검증 가능 | JS 응답의 `exif` 필드만 메타데이터 source          |

**Resolution path.** ADR-005 "출력 JPEG metadata asymmetry" 및 ADR-006 D11(deferred)에 기록. server-side 검증 정책에 따라 Android에 `ExifInterface(outFile).saveAttributes()` 추가 가능. 1-call fix.

### 1.2 `software` 태그의 의미론

| 플랫폼  | 자연 카메라가 채우는 값                                                                           |
| ------- | ------------------------------------------------------------------------------------------------- |
| iOS     | OS 버전 (e.g. `"17.0"`, `"26.4.2"`)                                                               |
| Android | 기기/펌웨어 식별자 (e.g. `"F741NKSS3CZCS"` Galaxy Z Flip6, `"MIUI Camera"`) — 비어있는 OEM도 있음 |

**Resolution path.** 비범위. 두 플랫폼이 같은 EXIF 표준 키를 다른 의미로 사용 — 패키지가 정규화하지 않고 raw 값 그대로 forward (per ADR-006 D2, D8). consumer는 *값 패턴*으로 판단해야 함 (`api-contract.md` "Software tag patterns").

### 1.3 `ISOSpeedRatings` 형태

| 플랫폼                | 원본 형태                           |
| --------------------- | ----------------------------------- |
| iOS ImageIO           | `NSArray<NSNumber>` (e.g. `@[@50]`) |
| Android ExifInterface | 단일 string (e.g. `"50"`)           |

**Resolution path.** 패키지가 native 단계에서 **단일 number로 정규화**해서 `exif.iso`로 노출 (ADR-006 D8). consumer는 항상 `number?` 타입 받음.

### 1.4 `kCGImagePropertyExifSoftware` 부재

| 플랫폼  | EXIF 사전 내 Software 키 존재                             |
| ------- | --------------------------------------------------------- |
| iOS     | ❌ `kCGImagePropertyExifSoftware` 없음 (ImageIO에 미정의) |
| Android | n/a (단일 flat dict)                                      |

**Resolution path.** iOS는 `kCGImagePropertyTIFFSoftware`(TIFF dict)에서만 읽음. EXIF dict는 fallback 안 함. ADR-006 D2 Consequences에 기록.

### 1.5 GPS 좌표 키 prefix

| 플랫폼                | GPS dict 내부 키                                                   |
| --------------------- | ------------------------------------------------------------------ |
| iOS ImageIO           | prefix 없음 (`Latitude`, `Longitude`, `Altitude`) — separate dict  |
| Android ExifInterface | `GPS` prefix (`GPSLatitude`, `GPSLongitude`, `GPSAltitude`) — flat |

**Resolution path.** 패키지가 `exif.raw` 평탄화 시 iOS의 GPS 키들에 `GPS` prefix를 붙여 양 플랫폼 키를 통일. ADR-006 D8.

## 2. OCR

### 2.1 회전 강건성 (rotation invariance)

| 플랫폼                              | 동작                                                                                                                                                                                           |
| ----------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| iOS Vision (VNRecognizeTextRequest) | 입력 픽셀 회전에 따라 confidence가 다름 → multi-pass 검출 가능                                                                                                                                 |
| Android ML Kit Korean               | **rotation invariant** — `InputImage.fromBitmap(bitmap, rotationDegrees)` 의 rotation hint와 무관하게 동일 결과 (lineCount/lineAspect/textLength). 2026-05-10 Galaxy Z Flip6 field data로 확인 |

**Resolution path.** ~~비범위 (ML Kit 동작). 두 플랫폼이 _동일한 회전 검출 알고리즘을 공유할 수 없음_. iOS는 confidence 기반 multi-pass(ADR-006 D7), Android는 단일-pass + lineAspect 기반(ADR-006 D14 v1.3).~~ — 2026-07-18 해소. 아래 참조.

⚠️ **2026-07-18 갱신 — 이 비대칭은 더 이상 성립하지 않는다.** iOS 26.5 실기기 측정에서 Vision도 회전된 한글·영문을 거의 동등하게 읽어냈다: 180° 뒤집힌 영수증에서 0° 패스가 62줄/신뢰도 76%, 90°·270°로 누운 영수증에서 52~54줄/84%. 즉 **양 플랫폼 모두 사실상 rotation-robust**가 되었다.

이건 단순한 표 갱신이 아니라 두 플랫폼의 회전 검출 설계 근거가 동시에 사라졌다는 뜻이다. iOS의 multi-pass는 "회전된 입력은 못 읽힌다"는 격차를 전제로 하고(그래서 `kRotateCommitRatio = 1.3`이 영원히 미달), Android의 lineAspect 방식은 애초에 rotation-invariance를 전제로 설계됐으나 90/270을 가를 신호가 없다. 결과적으로 iOS `autoRotate`는 어떤 회전도 보정하지 못하고, Android는 가로 콘텐츠에 항상 270°만 적용해 절반만 맞는다.

관측 가능한 사용자 증상은 양 플랫폼 공통이었다 — **OCR 텍스트는 정방향으로 정상 출력되는데 결과 이미지는 돌아간 채로 남는다.**

**해소 (2026-07-18): 두 플랫폼이 처음으로 동일한 신호를 공유한다.** "공유할 수 없다"는 원래 판단은 _인식 품질_ 을 신호로 쓴다는 전제에서만 참이었다 — 품질은 엔진마다 회전 반응이 다르므로 공유 불가가 맞다. 그러나 **텍스트 각도**는 엔진과 무관한 기하량이라 양쪽에서 같은 의미를 갖는다. [`../specs/ocr-angle-rotation-detection.md`](../specs/ocr-angle-rotation-detection.md) v1.0이 그 신호로 재설계했고, quantize·최빈값·보정 수학은 양 플랫폼이 같은 공식을 쓴다 (취득 경로 차이는 §2.5). 위 표의 rotation-invariance 차이는 이제 _폴백_ 경로에만 영향을 준다 — 특히 iOS 16/17에서는 Vision이 아직 rotation-variant라 probe 루프가 실제로 동작하므로, 그 폴백은 살려 둔다.

### 2.2 per-line confidence 노출

| 플랫폼                | per-line confidence                                                                                   |
| --------------------- | ----------------------------------------------------------------------------------------------------- |
| iOS Vision            | ✅ `VNRecognizedText.confidence` 노출                                                                 |
| Android ML Kit Korean | ✅ `Text.Line.getConfidence()` 노출 (**번들** 인식기 기준. unbundled 라이브러리는 0을 반환할 수 있음) |

**Resolution path.** 해소됨 (0.5.0). 이 표는 원래 "Android 미노출"로 기록돼 있었으나 0.5.0이 `meanLineConfidence`로 양 플랫폼 모두 `ocrQuality.confidence`를 채우도록 바꿨고, 표만 갱신되지 않았다 (2026-07-18 정정). `ocrFloor.minConfidence`는 여전히 absent 시 satisfied로 간주한다(ADR-006 D6) — OCR 미실행이나 무텍스트에서는 양 플랫폼 모두 absent이기 때문.

⚠️ 다만 두 값의 **분포는 비교 가능하다고 검증되지 않았다**. `api-contract.md`가 명시한 대로 confidence는 reporting-only이며, cross-platform 임계값으로 쓰지 말 것.

### 2.3 `Line.boundingBox` 좌표계

| 플랫폼         | bbox 단위              |
| -------------- | ---------------------- |
| iOS Vision     | 정규화된 `[0, 1]` 좌표 |
| Android ML Kit | 픽셀 좌표 (`Rect`)     |

추가로 원점도 다르다 — iOS Vision은 **bottom-left**, Android ML Kit `Rect`는 **top-left**.

**Resolution path.** 해소됨 (0.7.0, `ocrGeometry`). 그전까지는 _aspect ratio_(width/height)만 쓰여 단위 차이가 외부에 노출되지 않았으나, `ReceiptImage.ocrLines`가 bbox를 노출하면서 양 플랫폼 모두 **출력 JPEG 픽셀 · top-left 원점**으로 정규화해서 내보낸다 (iOS는 `+[RNOcrGeometry rectFromNormalizedBox:pixelSize:]`가 `1 - maxY`로 y축을 뒤집어 변환). 잔여 차이 하나: Android는 ML Kit `Rect`에서 온 **정수** 픽셀, iOS는 정규화 값 × 픽셀 크기라 **소수** 픽셀이다. JS `OcrLine.frame`은 `number`이므로 계약 위반은 아니고, 오버레이 배치에도 영향이 없다. 자세한 계약은 `docs/specs/ocr-line-geometry.md`.

### 2.4 OCR 단계 호출 순서 (autoRotate 흐름)

| 플랫폼  | OCR 호출 시점                                                                  |
| ------- | ------------------------------------------------------------------------------ |
| iOS     | JPEG 인코딩 _전_ (CGImage 단계에서 OCR 후 회전을 픽셀에 bake)                  |
| Android | JPEG 인코딩 _후_ (cache 파일을 디코드하여 OCR, 필요 시 in-place 회전+재인코딩) |

**Resolution path.** 비범위. iOS는 perspective correction 결과가 CGImage이고 `RNImageProcessor`가 모든 인코딩을 담당하므로 OCR-first가 자연스러움. Android는 `ImageProcessor.processGallery`가 perspective correction + 인코딩을 한 흐름으로 처리하므로 OCR이 인코딩 후. 이 순서 차이가 사용자에게 노출되는 결과는 동일.

### 2.5 텍스트 각도 취득 경로

| 플랫폼         | 각도 취득                                                                                                                                     |
| -------------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| Android ML Kit | ✅ `Text.Line.getAngle()`이 직접 제공. 문서상 **시계방향 양수**, `[-180, 180]` — 이 패키지의 CW 정준화와 방향이 그대로 일치                   |
| iOS Vision     | ❌ 각도 API 없음. `VNRecognizedTextObservation`의 `topLeft`/`topRight`에서 `atan2`로 유도해야 하고, **bottom-left 원점이라 y 부호 반전 필요** |

**Resolution path.** 의도된 비대칭 — 플랫폼이 주는 것이 다르다. 두 경로가 **같은 CW 규약의 값**을 내도록 `+[RNOcrGeometry clockwiseAngleFromTopLeft:topRight:]`가 흡수한다. y 부호를 놓치면 90과 270이 조용히 뒤바뀌므로(이번 재설계가 고치려는 바로 그 증상) 부호 규약은 `OcrGeometryTest`가 Kotlin 쪽에서 고정하고 iOS는 같은 공식을 미러한다. 상세는 [`../specs/ocr-angle-rotation-detection.md`](../specs/ocr-angle-rotation-detection.md).

⚠️ 두 값이 **이미지 공간** 기준인지는 아직 실측 미확인이다. 엔진 내부의 정규화된 리딩 프레임 기준이라면 회전 여부와 무관하게 0이 나오고 신호가 죽는다. 그 경우를 대비한 회귀 가드가 양 플랫폼에 배선돼 있다 (해당 스펙 §회귀 가드).

## 3. 회전·픽셀 변환

### 3.1 `rotationDegrees`의 회전 방향 (CW vs CCW)

⚠️ **잠재 의미론 충돌.** 코드에서 같은 `rotationDegrees=90` 값이 플랫폼마다 _반대 방향_ 회전을 의미한다.

| 플랫폼  | 호출                                                                                      | 90의 의미            |
| ------- | ----------------------------------------------------------------------------------------- | -------------------- |
| iOS     | `RNImageProcessor cgImageByRotating:degrees:90` → `CGAffineTransformMakeRotation(M_PI_2)` | 90° **CCW** (반시계) |
| Android | `ImageProcessor.rotateFileInPlace(degrees=90)` → `Matrix.postRotate(90f)`                 | 90° **CW** (시계)    |

**현재 영향.**

- ADR-006 D7 (iOS autoRotate): iOS 자체 알고리즘 안에서 일관됨 — `RNOcrProcessor.rotate:byDegrees:` 도 CCW 기준. iOS에선 OCR 검출과 픽셀 회전이 같은 방향 정의 사용. 자체 일관성은 유지.
- ADR-006 D14 (Android v1.3): Android 자체 알고리즘 안에서 일관됨 — `Matrix.postRotate` CW 기준. Android에선 OCR 신호로 결정한 회전을 픽셀 회전에 그대로 전달. 자체 일관성은 유지.
- **그러나 _플랫폼 간_** 의 의미론은 정반대. 향후 cross-platform 신호로 회전 결정을 통일하거나, `rotationDegrees`를 결과 표면에 노출할 일이 생기면 _반드시_ 한쪽으로 정규화해야 한다.
- **0.7.0 `ocrGeometry`가 이 정규화의 첫 사례다.** OCR 박스를 출력 프레임으로 옮기려면 픽셀에 가해진 회전을 박스에도 가해야 하므로 이 비대칭을 정면으로 만난다. 채택한 규약은 위 "장기 1안"과 같은 **CW 정준화**: `OcrGeometry.rotateClockwise`(Android) / `+[RNOcrGeometry rectByRotating:frameSize:clockwiseDegrees:]`(iOS)가 같은 CW 공식을 쓰고, 방향 차이는 각 플랫폼이 넘기는 인자에 흡수된다.

  | 플랫폼  | OCR이 박스를 잰 프레임            | 출력 프레임                      | remap이 필요한 조건                 | 넘기는 CW 각도 |
  | ------- | --------------------------------- | -------------------------------- | ----------------------------------- | -------------- |
  | Android | autoRotate _전_ JPEG              | autoRotate 후                    | autoRotate가 **실제로 회전**했을 때 | `d`            |
  | iOS     | 선택된 pass 프레임 (이미 CCW-`d`) | autoRotate면 그대로, 아니면 원본 | autoRotate가 **회전하지 않았을 때** | `d`            |

  즉 remap 조건이 두 플랫폼에서 **반대**다 — Android는 회전했을 때, iOS는 회전하지 않았을 때. iOS가 `360 − d`가 아니라 `d`를 넘기는 이유는 CCW-`d`를 되돌리는 것이 CW-`d`이기 때문이다. CW 공식 자체는 `OcrGeometryTest`가 고정한다.

**Resolution path.** 단기: 현재 사용처는 native-internal이므로 외부 영향 없음 — 그대로 유지. 장기:

1. `rotationDegrees`의 의미를 spec에 **CW로 명시**(EXIF orientation 표준과 일치) 하고 iOS의 `cgImageByRotating`을 CW로 변경, 또는
2. `OcrResult.rotationDegrees`를 외부에 노출하지 않도록 결정 — 픽셀이 정규화되면 사용자는 "정방향"만 알면 되므로.

ADR-006 후속 D 항목으로 추가 추적.

### 3.2 EXIF orientation 정규화

| 플랫폼  | 픽셀 처리                                                       | 출력 `exif.orientation`                                                                                                     |
| ------- | --------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| iOS     | `imageByApplyingOrientation:` + `kCGImagePropertyOrientationUp` | 항상 `1`                                                                                                                    |
| Android | `applyExifRotation` (Bitmap.createBitmap with Matrix)           | gallery 경로는 `.copy(orientation = ORIENTATION_NORMAL)` 강제 = 1; 카메라 경로는 source EXIF 그대로 (GMS가 정상화한 경우 1) |

**Resolution path.** 일관 의도 (모두 1). Android camera 경로의 corner case(GMS가 다른 값을 주는 경우)는 미관찰. `api-contract.md`에 "Always reported as 1" 명시.

## 4. JS 옵션 표면

### 4.1 옵션 적용 범위

| 옵션              | iOS 적용            | Android 적용        | 비고                                                                                                            |
| ----------------- | ------------------- | ------------------- | --------------------------------------------------------------------------------------------------------------- |
| `cropAutoConfirm` | ✅ gallery          | ❌ (무시)           | iOS gallery crop editor 전용 — 의도된 비대칭                                                                    |
| `autoRotate`      | ✅ camera + gallery | ✅ camera + gallery | 양쪽 적용. 다만 알고리즘은 다름(2.1)                                                                            |
| `includeRawExif`  | ✅                  | ✅                  | raw 키 셋은 거의 동일하나 일부 플랫폼-고유 키 존재 (예: iOS ColorModel, Android `XResolution` 표기 차이 가능성) |
| `includeGpsExif`  | ✅                  | ✅                  | 양쪽 적용                                                                                                       |

**Resolution path.** 옵션 표면은 통일됨. 알고리즘 내부 차이는 항목별로 위 섹션 참조.

### 4.2 OCR 결과 reading order

| 플랫폼         | textBlocks 정렬                              |
| -------------- | -------------------------------------------- |
| iOS Vision     | reading order (top-to-bottom, left-to-right) |
| Android ML Kit | top-to-bottom (image 좌표계)                 |

**Resolution path.** 비범위. consumer가 `ocrText`를 *전체 string*으로 keyword 매칭에 사용 (ADR-006 D5). reading order의 미세 차이는 keyword 검색에 영향 없음.

## 5. ImageOrigin 분류

(api-contract.md "imageOrigin platform behavior" 참조)

| Source            | iOS                              | Android                                           |
| ----------------- | -------------------------------- | ------------------------------------------------- |
| `source: camera`  | `"camera"` 항상                  | `"camera"` 항상                                   |
| `source: gallery` | PHAsset subtype + EXIF heuristic | MediaStore `BUCKET_DISPLAY_NAME` + EXIF heuristic |

**Resolution path.** 비범위. 같은 4-value enum을 다른 신호로 채움. `api-contract.md` "imageOrigin platform behavior" 표 참조.

## 6. 추후 확인 / 추가 가능성

- **Android camera 경로의 EXIF orientation**: GMS Document Scanner가 항상 1을 주는지 confirm 필요. 시뮬레이터에서 실측하지 못함.
- **iOS Galaxy(Android) 상호 portability**: 같은 영수증을 양 플랫폼에서 스캔 시 출력 비교 — pixel-level diff, EXIF diff. 정기적 회귀 테스트 권장.
- **`Software` 태그 OEM 패턴 사전**: Samsung("F…"), Xiaomi("MIUI Camera"), OnePlus, Vivo 등 채집해 consumer 측 fraud filter에 활용. 이 문서가 아닌 consumer 코드 또는 별도 reference에 두는 게 적절.

## 7. Quad distortion backstop (2026-06)

크롭 quad 왜곡 가드(`docs/specs/quad-distortion-backstop.md`)는 양 플랫폼 모두 **최종 quad**에 작용하지만, **검출 소스가 다르므로** 막아야 하는 왜곡의 성격이 다르다.

- **iOS** — Vision `VNDetectDocumentSegmentationRequest`/`VNDetectRectanglesRequest`. 샘플 quad는 대체로 깨끗함(convex, 낮은 edge ratio). 가드는 주로 confirm / `cropAutoConfirm` 경로를 방어. predicate: `ios/RNQuadGeometry`.
- **Android** — ML Kit text-block 코너(`quadFromTextBlocks`, sector-furthest-point). 엉뚱한 텍스트로 non-convex/왜곡 quad를 낼 수 있어 convexity 체크가 특히 유효. predicate: `com.receiptscanner.QuadGeometry`.

임계값(`MAX_EDGE_RATIO = 2.2`)은 PROVISIONAL이며 두 predicate에서 **동일하게 유지**해야 한다. (`MIN_EDGE_FRACTION`은 제거됨 — convexity + opposite-edge-ratio가 collapsed corner를 이미 거르고, 유일한 효과가 정상 초장축(>~20:1) 영수증 오판이었음. `quad-distortion-backstop.md` 참고.) 왜곡 판정 시 동작: warp 생략 → axis-aligned bbox 크롭(confirm), 검출 quad 폐기 → inset 기본값(seeding).

## Cross-references

- ADR-001 — Android ML Kit Document Scanner (camera path)
- ADR-002 — iOS gallery crop strategy
- ADR-003 — Package responsibility boundaries
- ADR-004 — iOS crop editor real-device fixes
- ADR-005 — Android gallery uses CropEditorActivity
- ADR-006 — 2026-05-09 design audit (D1–D14 결정 트리)
- `docs/specs/api-contract.md` — 사용자 표면 contract
- `docs/specs/ocr-orientation-correction.md` — iOS multi-pass 알고리즘
- `docs/specs/portrait-rotation-detection.md` — Android single-pass v1.3
