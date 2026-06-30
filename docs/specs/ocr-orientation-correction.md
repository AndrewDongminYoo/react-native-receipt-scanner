# OCR Orientation Correction (0° / 90° / 180° / 270°)

**Spec version:** 2.0
**최초 작성일:** 2026-05-09 (v1.0 — 180°만)
**개정일:** 2026-05-09 (v2.0 — 90° / 270° 확장)

## 문제 정의

영수증을 카메라/갤러리에서 가져왔을 때 콘텐츠가 **세로 자연 방향**과 일치하지 않는 두 가지 케이스를 보정해야 한다.

### Case 1 — 180° 거꾸로 (v1.0에서 처리)

iOS VisionKit으로 영수증을 상하 뒤집어 촬영하면 OCR 결과가 거꾸로 읽힌다.

```plaintext
rt.og.neslgt.www        ← www.7eleven.co.kr 뒤집힌 결과
비이고(주)              ← (주)코리아세븐 뒤집힌 결과
arTa#무보주상 블은북     ← 세븐일레븐 보문점 뒤집힌 결과
```

### Case 2 — 90° / 270° 측면 회전 (v2.0에서 추가)

갤러리 경로에서 가로 방향(`width > height`)으로 들어오는 영수증. 사용자가 회전 편집한 이미지, 가로로 누인 캡처, 가로 PDF에서 추출된 영수증 등이 이에 해당.

특이점: Vision/ML Kit은 90°/270° 회전된 한국어 텍스트도 _어느 정도_ 인식 가능 — confidence는 떨어지지만 0이 아니다. 즉 단순 confidence 비교만으로는 회전 검출이 어렵고, 종횡비 게이트와 결합해야 한다.

## 근본 원인

`RNImageProcessor`는 CGImageRef 픽셀을 항상 EXIF orientation-normalized (Up, EXIF=1) 상태로 저장한다. EXIF 메타데이터는 정방향이지만, 콘텐츠의 자연 방향(영수증의 위쪽이 어디인가)은 픽셀 데이터에만 존재한다.

EXIF로는 콘텐츠 방향을 감지할 수 없으므로, **이미지 픽셀에서 방향을 추론**해야 한다.

## API 변경 범위

- **추가**: `ScanReceiptOptions.autoRotate?: boolean` (default `true`). `false`로 끄면 v1.0 이전 동작(픽셀 회전 없음).
- **변경**: `autoRotate: true`일 때 출력 JPEG의 픽셀이 영수증 자연 방향으로 회전됨. `width` / `height`도 회전 후 값으로 보고됨. EXIF orientation은 여전히 항상 `1`.
- 결과 타입(`ReceiptImage` / `ReceiptExif`)은 변동 없음.

## 감지 알고리즘

### 종횡비 게이트

입력 픽셀 종횡비를 가장 먼저 본다. 정상 세로 영수증(`aspect = width / height ≤ 1`)은 v1.0 알고리즘을 그대로 적용. 가로 입력(`aspect > 1`)만 4-pass 진입.

이유: 가로형 영수증(일부 호텔/식당)은 정상 0°에서 OCR confidence가 가장 높을 것이므로 4-pass에서도 살아남는다. 반대로 정상 세로 영수증을 4-pass 하면 90°/270° 검사 비용이 매번 추가됨 — 사용자의 99% 케이스에 0 비용을 유지하기 위한 게이트.

### v1.0 — Portrait fast path (`aspect ≤ 1`)

```log
Pass 1 — Primary (recognitionLevel: .accurate, rotation 0°)
  ├─ Q0 = mean(top_candidate.confidence) × clamp(count / 10, 0, 1)
  └─ if Q0 ≥ 0.80 OR count < 3 → text@0°, rotation=0

Pass 2 — Probe (recognitionLevel: .fast, rotation 180°)
  └─ Q180 calculated; if Q180 > Q0 × 1.15 → Pass 3, else text@0°

Pass 3 — Accurate on rotated (rotation 180°)
  └─ text@180°, rotation=180
```

### v2.0 — Landscape 4-pass (`aspect > 1`)

```log
Pass 1 — Primary (recognitionLevel: .accurate, rotation 0°)
  ├─ Q0 calculated
  └─ if Q0 ≥ 0.80 AND count ≥ 5 AND aspect ≤ 1.5
        → text@0°, rotation=0  (정상 가로형 영수증으로 판단)

Pass 2a — Probe 90°  (recognitionLevel: .fast)
Pass 2b — Probe 180° (recognitionLevel: .fast)
Pass 2c — Probe 270° (recognitionLevel: .fast)
  └─ Q90, Q180, Q270 collected.
     best = argmax(Q0, Q90, Q180, Q270)

Pass 3 — Accurate on best non-zero rotation (only if best ≠ 0)
  ├─ Q[best] > Q0 × 1.15 (fast-vs-accurate margin) 인 경우만 진입
  └─ text@best°, rotation=best
```

가로형 영수증 fast-path 조건(`Q0 ≥ 0.80 AND count ≥ 5 AND aspect ≤ 1.5`)은 호텔 청구서·식당 단가표 같은 진짜 가로 영수증을 보호한다. `count ≥ 5`는 텍스트 라인이 충분해야 신뢰할 수 있다는 뜻이며, `aspect ≤ 1.5`는 _진짜 가로_(예: 1024×768)와 _회전된 세로_(예: 3530×1176) 사이의 경험칙 분리선.

### 판정 임계값

| 임계값                         | 값                                         | 근거                                             |
| ------------------------------ | ------------------------------------------ | ------------------------------------------------ |
| Portrait skip threshold (`Q0`) | `≥ 0.80`                                   | 정상 인식 판단 — false positive 방지             |
| Landscape fast-path skip       | `Q0 ≥ 0.80 AND count ≥ 5 AND aspect ≤ 1.5` | 진짜 가로형 영수증을 보호                        |
| Minimum observation count      | `3` (portrait) / `5` (landscape)           | 빈 영수증/저품질 차단. 가로 케이스는 더 엄격하게 |
| Probe win margin               | `× 1.15`                                   | fast vs accurate confidence 차이 보정            |

### 성능 분석

| 케이스                                    | 추가 시간                                |
| ----------------------------------------- | ---------------------------------------- |
| Portrait + 고신뢰도 (Q0 ≥ 0.80)           | 0ms                                      |
| Portrait + 저신뢰도                       | ~100–150ms (180° fast probe)             |
| Portrait + 180° 뒤집힘                    | ~150ms (probe) + Pass 3 (accurate)       |
| Landscape + 진짜 가로 영수증              | 0ms (`Q0 ≥ 0.80 AND count ≥ 5` skip)     |
| Landscape + 90° / 270° 회전 (이번 케이스) | ~300–450ms (3개 fast probe) + Pass 3     |
| Landscape + 회전 없음 (낮은 confidence)   | ~300–450ms (3개 probe로 확인 후 0° 채택) |

티켓의 "방향 감지 오버헤드 150ms 이내"는 portrait에서만 보장. landscape는 의도적으로 더 비싼 검사를 허용한다 — landscape 입력 자체가 드물고, 사용자가 명시적으로 회전된 이미지를 가져온 시나리오라 비용 정당화.

## 픽셀 회전 적용

OCR detect → rotation degrees(0/90/180/270) 결정 → caller가 픽셀 회전 적용 후 JPEG 인코딩. `autoRotate: false`면 detection은 수행해 OCR text는 보정되지만 픽셀은 회전 안 함(v1.0 호환).

```log
iOS:
  perspectiveCorrected CGImage
    → RNOcrProcessor.recognize → { text, rotationDegrees }
    → if rotationDegrees != 0 AND options.autoRotate
        rotated = CIImage rotate(rotationDegrees) → CGImage
      else rotated = original
    → RNImageProcessor.processImage(rotated, …) → JPEG

Android:
  perspective-correct Bitmap (already in CropEditorActivity)
    → OcrProcessor.recognize(bitmap) → { text, rotationDegrees }
       (uses InputImage.fromBitmap(bitmap, rotationDegrees) per pass)
    → if rotationDegrees != 0 AND options.autoRotate
        rotated = Bitmap.createBitmap with rotation matrix
      else rotated = original
    → encode JPEG
```

회전 후 픽셀의 `width` / `height`는 swap된 값으로 보고된다. EXIF orientation은 여전히 `1`.

## 플랫폼 구현 메모

### iOS

`RNOcrProcessor`는 메서드 시그니처 변경:

```objc
+ (nullable RNOcrResult *)recognizeAndDetectRotationInImage:(UIImage *)image
                                                       error:(NSError **)error;

@interface RNOcrResult : NSObject
@property (nonatomic, copy) NSString *text;
@property (nonatomic, assign) NSInteger rotationDegrees;  // 0 | 90 | 180 | 270
@property (nonatomic, assign) double meanConfidence;       // for ocrQuality
@end
```

기존 `recognizeTextInImage:` 는 v1.0 호환을 위해 유지하되, `recognizeAndDetectRotationInImage:`로 위임.

### Android

`OcrProcessor.recognize`는 `Result` data class 리턴:

```kotlin
data class Result(
  val text: String,
  val rotationDegrees: Int,  // 0 | 90 | 180 | 270
  val lineCount: Int,
)

fun recognize(uri: Uri, aspectRatio: Float): Result
```

ML Kit Korean recognizer는 per-line confidence를 노출하지 않으므로 quality metric은 `lineCount` 사용. landscape skip 조건은 `lineCount ≥ 8 AND aspect ≤ 1.5`로 보수적 적용.

## 검증 기준 (Definition of Done)

- [ ] 180° 뒤집힌 영수증 — OCR 결과 + 픽셀 모두 정방향
- [ ] 90° / 270° 회전된 영수증 (gallery) — OCR 결과 + 픽셀 모두 정방향
- [ ] 정상 세로 영수증 — 변화 없음, 0ms 오버헤드
- [ ] 진짜 가로 영수증 (예: 호텔 청구서, 1024×600) — 회전 적용 안 됨
- [ ] 텍스트가 적은 영수증 (< 3줄) — 무한 pass 없이 원본 반환
- [ ] `autoRotate: false` — detection은 동작하되 픽셀은 회전 안 됨 (v1.0 동작)
- [ ] iOS 16+ (ko-KR + en-US) 동작
- [ ] Android — `aspect > 1`인 갤러리 입력에 4-pass 적용

## 비범위 (Non-Goals)

- 임의 각도(예: 45° 기울어진 영수증) 보정
- 이미지 흐림/저화질로 인한 OCR 실패 개선
- 거울 반전(mirror flip; EXIF 2/4/5/7) 처리 — EXIF 정규화 단계에서 흡수됨
