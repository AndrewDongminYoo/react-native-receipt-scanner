# Portrait Rotation Detection — Single-Pass Aspect Mismatch

**Spec version:** 1.3
**최초 작성일:** 2026-05-10 (v1.0 — lineAspect 게이트)
**개정일:** 2026-05-10 (v1.3 — single-pass + aspect mismatch)
**상위 문서:** [`ocr-orientation-correction.md`](./ocr-orientation-correction.md) v2.0
**관련 결정:** ADR-006 D14
**플랫폼:** Android only (iOS는 v2.0 그대로)

## 현재 상태 (v1.3): 폴백으로 강등 — [`ocr-angle-rotation-detection.md`](./ocr-angle-rotation-detection.md) v1.0이 대체 (2026-07-18)

**이 문서는 더 이상 Android의 1차 회전 검출이 아니다.** `Text.Line.getAngle()` 기반 각도 라우팅이 앞에 놓이고, v1.3 lineAspect 알고리즘은 각도 표본이 부족하거나 분산이 클 때만 실행되는 **폴백**으로 남는다.

강등 사유는 §회전 방향(90 vs 270) 결정의 한계가 예고한 실패가 실측에서 확인됐기 때문이다. 그 절의 "field 데이터 추가 수집 후 결정"은 이제 답이 나왔고, **답은 "둘 중 하나를 고르는 문제가 아니다"**이다 — 상세는 해당 절에 기록.

lineAspect는 라인의 *모양*을 재므로 90°와 270°에서 값이 같다. 방향 정보가 원리적으로 없으니 어떤 기본값을 골라도 절반이다. 각도는 방향을 담고 있어 그 구분이 성립하며, v1.3이 비범위로 둔 180° 단독 검출도 함께 해소된다.

⚠️ 아래 알고리즘 절의 임계값과 `ROTATED_DEFAULT_DEGREES = 270`은 **폴백 경로에서 여전히 유효**하다 — 삭제하지 말 것. 각도 신호가 실측으로 입증되면 그때 별도 변경으로 정리한다.

Android에서 ML Kit Korean Text Recognizer가 입력 회전에 *invariant*하다는 사실이 실측에서 확인됨. 즉 4-pass probe loop가 모든 회전에서 동일한 lineCount/lineAspect/textLength를 반환 — probe 자체가 무용 비용. v1.3은 multi-pass를 단일 Pass 0로 단순화하고, 회전 검출은 image-aspect와 line-aspect의 *방향 일치 여부*로 결정한다.

## 변천 기록

### v1.0 (2026-05-10) — lineAspect 게이트

portrait fast-path에 line bbox aspect 게이트 추가. 가정: 회전된 한글은 box가 세로로 길어 aspect 0.1–0.25. 실측에서 ML Kit Korean이 회전된 한글을 짧은 단어 단위로 인식해서 box가 정사각(aspect ≈ 1)에 머물러 가정 실패.

### v1.1 (2026-05-10) — always-probe portrait

portrait도 항상 4-probe. 자연 방향 비용이 매번 +300 ms로 사용자 정책에 부합하지 않음.

### v1.2 (2026-05-10) — deferred

portrait fast-path를 v2.0으로 복귀, 진단 로그만 영구 추가.

### v1.3 (2026-05-10, 현재) — single-pass + aspect mismatch ✓

Galaxy Z Flip6 두 번째 라운드 데이터에서:

```log
probe deg=0   lineCount=34 lineAspect=0.23 textLength=403  imageAspect=2.218
probe deg=90  lineCount=34 lineAspect=0.23 textLength=403
probe deg=180 lineCount=34 lineAspect=0.23 textLength=403
probe deg=270 lineCount=34 lineAspect=0.23 textLength=403
```

네 probe가 _완전히 동일_ — ML Kit Korean Text Recognizer 16.0.0이 `InputImage.fromBitmap(bitmap, rotationDegrees)`의 rotation hint를 사실상 무시하거나, 자체 회전-invariant detection을 수행. 두 가설 모두 결론은 같음: **Android에서 OCR을 여러 번 시도하는 것은 의미가 없다**.

대신 같은 데이터에서 **lineAspect는 매우 강한 단일-pass 신호**임을 확인:

| 파일                   | imageAspect | lineAspect | 의미                        |
| ---------------------- | ----------- | ---------- | --------------------------- |
| 2번 (정상 portrait)    | 0.489       | **4.66**   | 정상 한글 영수증            |
| 3번 (정상 portrait)    | 0.362       | **5.42**   | 정상 한글 영수증            |
| 1번 (가로로 들고 촬영) | 2.218       | **0.23**   | 라인이 세로로 누움 — 회전됨 |

회전 검출은 `imageAspect`와 `lineAspect`의 **방향 일치 여부**로 결정한다.

## 알고리즘 (v1.3)

```log
runDetection(bitmap):
  pass0 = recognizeAt(0)            # 단일 OCR pass
  log probe(0, lineCount, lineAspect, textLength, imageAspect)

  if pass0.lineCount < 3              → return 0°  (too little signal)
  if pass0.lineCount < 5              → return 0°  (low-line-count-skip-mismatch)

  imageIsLandscape = imageAspect > 1
  lineIsHorizontal = lineAspect > 1.5
  lineIsVertical   = lineAspect < 0.7

  # 가장 흔한 회전 케이스: 사용자가 portrait 영수증을 가로로 들고 촬영
  if imageIsLandscape AND lineIsVertical:
    log decision(270, "landscape-vertical-lines")
    return 270°  (Android Matrix.postRotate(270) = 90° CCW from user POV)

  # 모호 영역 (0.7 ≤ lineAspect ≤ 1.5): 보수적으로 0° 통과
  if NOT lineIsHorizontal AND NOT lineIsVertical:
    log decision(0, "line-aspect-ambiguous")
    return 0°

  # 정상 (portrait + horizontal lines, landscape + horizontal lines)
  log decision(0, "aspect-matched")
  return 0°
```

### 임계값

| 임계값                      | 값    | 근거                                                                  |
| --------------------------- | ----- | --------------------------------------------------------------------- |
| `LINE_HORIZONTAL_THRESHOLD` | `1.5` | 정상 한글 영수증 lineAspect가 4–10. 보수적 분리선                     |
| `LINE_VERTICAL_THRESHOLD`   | `0.7` | 회전된 케이스 lineAspect ≈ 0.23. 보수적 분리선 (1.0이 아닌 0.7)       |
| `MISMATCH_MIN_LINES`        | `5`   | trimmed-mean이 신뢰도 갖는 최소 라인 수                               |
| `ROTATED_DEFAULT_DEGREES`   | `270` | "사용자가 가로로 들고 찍는 일반 자세" 가정 — 영수증 위쪽이 image 왼쪽 |

### 회전 방향(90 vs 270) 결정의 한계

Android `Matrix.postRotate`는 시계 방향(CW). `270°`는 _시계 방향 270°_ = _반시계 방향 90°_. 사용자 1번 케이스에서 영수증이 어느 방향으로 누웠는지 단일 데이터로 결정 불가. 다음 두 가설 중:

1. **영수증 자연 위쪽이 image의 왼쪽** — 정상화하려면 90° CCW = `Matrix.postRotate(270)` ✓ (현재 default)
2. **영수증 자연 위쪽이 image의 오른쪽** — 정상화하려면 90° CW = `Matrix.postRotate(90)`

field 데이터 추가 수집 후 결정. 만약 1번 케이스가 잘못 회전되면 commit message가 안내하는 대로 `ROTATED_DEFAULT_DEGREES = 90`으로 변경.

### field data (2026-07-18) — 두 가설이 모두 실재함

같은 영수증을 두 방향으로 눕혀 갤러리 경로에 넣은 결과:

| 입력 콘텐츠 방향 | 적용된 회전       | 출력 이미지        |
| ---------------- | ----------------- | ------------------ |
| CW 90°           | `postRotate(270)` | **정방향** ✅      |
| CW 270°          | `postRotate(270)` | **180° 뒤집힘** ❌ |

즉 위 두 가설은 배타적이지 않고 **둘 다 실제로 발생한다**. 고정 기본값을 90으로 바꾸면 성공/실패 케이스가 서로 뒤바뀔 뿐, 적중률은 그대로다.
ML Kit Korean이 rotation-invariant인 이상([`../notes/platform-asymmetries.md`](../notes/platform-asymmetries.md) §2.1) 인식 품질에서는 90과 270을 가르는 신호가 나오지 않으므로, **다른 신호가 필요하다**.

~~후보는 박스 기하 — 0.7.0에서 노출한 `ocrLines`가 라인 baseline 방향과 문자 진행 방향을 담고 있어, 텍스트가 위에서 아래로 읽히는지 아래에서 위로 읽히는지를 판별할 여지가 있다.~~

⚠️ **이 후보는 틀렸다 (2026-07-19 정정).** `OcrLine.frame`은 **축정렬** 사각형(`x`/`y`/`width`/`height`)이라 baseline 방향도 문자 진행 방향도 담고 있지 않다 — 90°와 270°로 누운 레이아웃에서 기하가 동일하므로 원리적으로 방향을 가릴 수 없다.

실제 해답은 박스가 아니라 **엔진이 보고하는 텍스트 각도**였다 (`Text.Line.getAngle()`). 상세는 [`ocr-angle-rotation-detection.md`](./ocr-angle-rotation-detection.md) v1.0 — 이 문서를 대체한다.

재설계는 별도 작업으로 분리했다. 이 절의 기본값 변경 안내는 **적용하지 말 것** — 문제를 옮길 뿐이다.

## 진단 로그

```bash
adb logcat -s ReceiptScanner.Ocr:I
```

전형적 출력:

```log
# 정상 portrait
I/ReceiptScanner.Ocr: probe deg=0 file=…jpg lineCount=47 lineAspect=4.82 textLength=600 imageAspect=0.373
I/ReceiptScanner.Ocr: decision file=…jpg chosen=0 reason=aspect-matched lineCount=47 lineAspect=4.82 textLength=600

# 가로로 들고 찍은 portrait (회전 보정 적용)
I/ReceiptScanner.Ocr: probe deg=0 file=…jpg lineCount=34 lineAspect=0.23 textLength=403 imageAspect=2.218
I/ReceiptScanner.Ocr: decision file=…jpg chosen=270 reason=landscape-vertical-lines lineCount=34 lineAspect=0.23 textLength=403

# 모호
I/ReceiptScanner.Ocr: probe deg=0 file=…jpg lineCount=20 lineAspect=1.10 textLength=180 imageAspect=0.71
I/ReceiptScanner.Ocr: decision file=…jpg chosen=0 reason=line-aspect-ambiguous lineCount=20 lineAspect=1.10 textLength=180
```

## 성능 (v1.3)

| 입력 케이스                                | 비용                                              |
| ------------------------------------------ | ------------------------------------------------- |
| 정상 portrait/landscape (aspect-matched)   | OCR × 1 ≈ 150 ms                                  |
| 회전된 portrait (landscape-vertical-lines) | OCR × 1 + Bitmap rotate + JPEG re-encode ≈ 250 ms |
| 모호한 lineAspect                          | OCR × 1 ≈ 150 ms (회전 안 적용)                   |
| 빈 영수증 (lineCount < 3)                  | OCR × 1 ≈ 150 ms                                  |

v2.0 대비:

- portrait 자연 방향: 변화 없음 (둘 다 1-pass)
- portrait 약신호: -150 ms (v2.0의 180° probe 제거)
- landscape 약신호: -300~450 ms (v2.0의 90/180/270 probe 제거)

## 검증 기준 (Definition of Done)

- [ ] 사용자 1번 케이스 (Galaxy Z Flip6 2706×1220, lineAspect=0.23) → 270° 회전 적용 후 픽셀 정방향
- [ ] 사용자 2번/3번 케이스 (정상 portrait, lineAspect 4.66/5.42) → 0° 통과, 회귀 없음
- [ ] 진단 로그 (`adb logcat -s ReceiptScanner.Ocr:I`)로 decision reason 확인
- [ ] lineCount < 3 케이스 → 빠른 종료
- [ ] 모호 lineAspect (0.7 ≤ x ≤ 1.5) → 0° 통과 (false positive 방지)
- [x] ~~1번 케이스의 회전 방향이 잘못된 경우 → `ROTATED_DEFAULT_DEGREES`를 90으로 변경~~ — 2026-07-18 field data로 무효. 두 방향이 모두 발생하므로 기본값 교체는 실패 케이스를 맞바꿀 뿐이다 (§field data 참조)

## 비범위 (Non-Goals)

- ~~iOS 동등 보강 — Vision은 confidence 기반 알고리즘이 다르고 회전 검출이 가능. ADR-006 D7 v2.0 그대로 유지~~ — 2026-07-18 무효: iOS 26.5에서는 Vision도 회전 검출이 되지 않는다 ([`ocr-orientation-correction.md`](./ocr-orientation-correction.md) 상단 상태 블록)
- ~~90° vs 270° 자동 결정 (textBlock 위치 분석 등) — field 데이터 부족, 단순 default로 충분할 가능성~~ — 2026-07-18 무효: 두 방향이 모두 발생함이 확인됐으므로 단순 default로는 불충분하다. 박스 기하 기반 판별이 다음 후보이며 별도 작업으로 분리
- 180° 단독 회전 검출 — lineAspect 신호는 180°에서 정상 분포와 같음. 따로 처리 안 함
