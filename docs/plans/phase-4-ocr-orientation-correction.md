# Phase 4 — OCR Orientation Correction (0° / 90° / 180° / 270°)

> **Update 2026-05-09 (spec v2.0):** 90° / 270° 확장은 ADR-006 D7로 결정 → 양 플랫폼에 적용. 본 plan의 알고리즘 섹션과 DoD는 v1.0(180°만) 기준으로 작성된 것이며, v2.0 알고리즘과 픽셀 회전 적용은 `docs/specs/ocr-orientation-correction.md` v2.0과 ADR-006 D7을 참조.

## Goal (v2.0)

OCR 처리 단계에서 콘텐츠가 90° / 180° / 270° 회전되어 있는지 감지하고, `autoRotate: true` (default)면 출력 JPEG 픽셀까지 정방향으로 회전하여 반환한다.

`autoRotate: false`면 v1.0 동작(180°만 OCR text 보정, 픽셀 회전 없음).

설계 명세: `docs/specs/ocr-orientation-correction.md` (v2.0)

---

## 변경 파일

| 파일                                                       | 변경 종류                           |
| ---------------------------------------------------------- | ----------------------------------- |
| `ios/RNOcrProcessor.m`                                     | 2-pass 방향 감지 로직 추가          |
| `ios/RNOcrProcessor.h`                                     | 공개 API 변화 없음 (내부 헬퍼 추가) |
| `android/src/main/java/com/receiptscanner/OcrProcessor.kt` | 조사 결과에 따라 선택적 수정        |

---

## iOS Tasks ✅ 완료 (2026-05-09)

### Step 1 — `RNOcrProcessor.m` 내부 헬퍼 추출

현재의 `recognizeTextInImage:error:` 본문을 **새로운 private 메서드 `runOcrOnCIImage:level:error:`** 로 추출한다.

```objc
// 추가할 private 메서드 선언 (구현부 상단 @interface extension)
@interface RNOcrProcessor ()
+ (nullable NSString *)runOcrOnCIImage:(CIImage *)ciImage
                                  level:(VNRequestTextRecognitionLevel)level
                                  error:(NSError **)error;
+ (double)qualityScoreFromResults:(NSArray<VNRecognizedTextObservation *> *)results;
+ (CIImage *)rotate180:(CIImage *)ciImage;
@end
```

`runOcrOnCIImage:level:error:` 는 현재 `recognizeTextInImage:error:` 의 내용을 그대로 이동. `level` 파라미터를 `request.recognitionLevel`에 바인딩.

### Step 2 — quality score 계산 헬퍼

```objc
+ (double)qualityScoreFromResults:(NSArray<VNRecognizedTextObservation *> *)results {
    if (results.count == 0) return 0.0;
    double sumConf = 0.0;
    for (VNRecognizedTextObservation *obs in results) {
        VNRecognizedText *top = [[obs topCandidates:1] firstObject];
        if (top) sumConf += top.confidence;
    }
    double meanConf = sumConf / results.count;
    double countFactor = MIN(results.count / 10.0, 1.0);
    return meanConf * countFactor;
}
```

### Step 3 — 180° 회전 헬퍼

```objc
+ (CIImage *)rotate180:(CIImage *)ciImage {
    CIImage *rotated = [ciImage imageByApplyingTransform:
        CGAffineTransformMakeRotation(M_PI)];
    // 회전 후 extent의 origin이 음수가 되므로 원점으로 이동
    CGRect ext = rotated.extent;
    return [rotated imageByApplyingTransform:
        CGAffineTransformMakeTranslation(-ext.origin.x, -ext.origin.y)];
}
```

### Step 4 — `recognizeTextInImage:error:` 메인 로직 교체

```objc
+ (nullable NSString *)recognizeTextInImage:(UIImage *)image
                                      error:(NSError **)error {
    CIImage *ciImage = [[CIImage alloc] initWithImage:image];
    if (!ciImage) {
        if (error) {
            *error = [NSError errorWithDomain:@"RNOcrProcessor" code:1
                         userInfo:@{NSLocalizedDescriptionKey:
                                    @"Failed to create CIImage from UIImage"}];
        }
        return nil;
    }

    // Pass 1: accurate-level OCR on original image
    NSMutableArray<VNRecognizedTextObservation *> *pass1Results = [NSMutableArray new];
    NSString *pass1Text = [self runOcrOnCIImage:ciImage
                                          level:VNRequestTextRecognitionLevelAccurate
                                    outResults:pass1Results
                                          error:error];
    if (!pass1Text) return nil;

    double q1 = [self qualityScoreFromResults:pass1Results];

    // High-confidence result or insufficient data — skip rotation check
    if (q1 >= 0.80 || pass1Results.count < 3) {
        return pass1Text;
    }

    // Pass 2: fast-level probe on 180°-rotated image
    CIImage *rotated = [self rotate180:ciImage];
    NSMutableArray<VNRecognizedTextObservation *> *pass2Results = [NSMutableArray new];
    NSError *pass2Err = nil;
    [self runOcrOnCIImage:rotated
                    level:VNRequestTextRecognitionLevelFast
               outResults:pass2Results
                    error:&pass2Err];

    double q2 = [self qualityScoreFromResults:pass2Results];

    // Rotated orientation is clearly better — run accurate pass on rotated image
    if (q2 > q1 * 1.15) {
        NSString *pass3Text = [self runOcrOnCIImage:rotated
                                              level:VNRequestTextRecognitionLevelAccurate
                                         outResults:nil
                                              error:error];
        return pass3Text ?: pass1Text;
    }

    return pass1Text;
}
```

> **주의:** `runOcrOnCIImage:level:error:` 시그니처에 `outResults:(NSMutableArray<VNRecognizedTextObservation *> * _Nullable)outResults` 파라미터를 추가해야 한다. `nil` 전달 시 results 수집 생략.

### Step 5 — 검증 언어 로직 이동

현재 `recognizeTextInImage:error:`에 있는 `languages` 결정 로직(`@available(iOS 16, *)` 분기)을 `runOcrOnCIImage:level:outResults:error:` 내부로 이동.

---

## Android Tasks ⏳ 재현 여부 미확인

### Step 1 — 재현 여부 확인 (필수 선행)

실기기에서 180° 뒤집힌 영수증을 GmsDocumentScanner로 스캔한 뒤 반환된 `ocrText`를 확인한다.

```bash
# 확인 방법: 앱 콘솔에서 ocrText 출력
# 또는 Logcat에서 OCR 결과 필터링
adb logcat | grep -i "ocrText\|TextRecog"
```

| 결과                                              | 조치                |
| ------------------------------------------------- | ------------------- |
| GmsDocumentScanner가 자동 교정 → 정상 텍스트 반환 | Android 수정 불필요 |
| 뒤집힌 텍스트 반환                                | Step 2 진행         |

### Step 2 — Android 방향 보정 (재현 확인 시)

`OcrProcessor.kt`의 `recognize(imageUri)` 에 2-pass 방식 적용.

Android는 `VNRecognizedText.confidence`에 해당하는 값이 없으므로, **라인 수(lineCount)** 를 quality 지표로 사용:

```kotlin
private fun qualityScore(text: Text): Double {
    val lineCount = text.textBlocks.sumOf { it.lines.size }
    return lineCount.toDouble().coerceAtMost(10.0) / 10.0
}

private fun recognize180(imageUri: Uri): Text {
    val bmp = BitmapFactory.decodeFile(imageUri.path)
    val matrix = Matrix().apply { postRotate(180f) }
    val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    bmp.recycle()
    val inputImage = InputImage.fromBitmap(rotated, 0)
    val result = Tasks.await(recognizer.process(inputImage))
    rotated.recycle()
    return result
}

fun recognize(imageUri: Uri): String {
    val image = InputImage.fromFilePath(context, imageUri)
    val result1 = Tasks.await(recognizer.process(image))
    val q1 = qualityScore(result1)

    if (q1 >= 0.80 || result1.textBlocks.sumOf { it.lines.size } < 3) {
        return result1.text
    }

    val result2 = recognize180(imageUri)
    val q2 = qualityScore(result2)

    return if (q2 > q1 * 1.15) result2.text else result1.text
}
```

> Android는 fast-level OCR이 없으므로 Pass 2에서 full OCR을 직접 실행. 180° 회전이 필요한 케이스에서 2× OCR 시간 발생하지만, 이는 iOS의 Pass 3와 동일한 수준.

---

## 공통 검증

### 수동 검증 시나리오

- [ ] iOS: 영수증을 정상 방향으로 촬영 → `ocrText` 정상 반환, 결과 변화 없음
- [ ] iOS: 영수증을 180° 뒤집어 촬영 → `ocrText` 정방향 텍스트 반환
- [ ] iOS: 빈 종이 / 텍스트 없는 이미지 → `ocrText` null 또는 빈 문자열, 크래시 없음
- [ ] iOS: `ocr: false` 옵션 → `recognizeTextInImage:` 미호출, 로직 미실행 (회귀 없음)
- [ ] iOS: iOS 15 기기에서 실행 → fast-level 정상 동작 (`VNRequestTextRecognitionLevelFast` iOS 14+ 지원)
- [ ] Android: (재현 여부 확인 후) 해당 시나리오 동일하게 검증

### 자동 검증

```bash
# iOS 빌드 검증
cd ios && bundle exec pod install && cd ..
npm run typecheck && npm run lint

# 예제 앱 실행
yarn example ios
```

---

## 롤백

PR revert. `RNOcrProcessor.m` 단독 변경이므로 다른 파일에 영향 없음.
Android를 수정한 경우 `OcrProcessor.kt` 함께 revert.

---

## 관련 문서

- 설계 명세: `docs/specs/ocr-orientation-correction.md`
- 스캔 파이프라인 전체 흐름: `docs/specs/scan-pipeline.md`
- 수정 대상 파일: `ios/RNOcrProcessor.m`, `ios/RNOcrProcessor.h`
