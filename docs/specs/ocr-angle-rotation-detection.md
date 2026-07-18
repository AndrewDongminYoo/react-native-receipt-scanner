# OCR Angle Rotation Detection — Cross-Platform

**Spec version:** 1.0
**최초 작성일:** 2026-07-18
**대체 대상:** [`portrait-rotation-detection.md`](./portrait-rotation-detection.md) v1.3 (Android), [`ocr-orientation-correction.md`](./ocr-orientation-correction.md) v2.0 (iOS)
**관련 결정:** ADR-006 D7, D14
**플랫폼:** Android + iOS (양 플랫폼 공통 신호)

## 왜 재설계인가

2026-07-18 실기기 QA에서 두 플랫폼의 회전 검출이 **동시에** 근거를 잃었다 ([`../notes/platform-asymmetries.md`](../notes/platform-asymmetries.md) §2.1).

- iOS Vision은 회전된 한글을 0° 패스에서 거의 동등하게 읽어낸다 (180° 뒤집힘 62줄/76%, 90°·270° 52~54줄/84%).
  multi-pass 라우팅은 "회전된 입력은 못 읽힌다"는 격차를 전제하므로 `kRotateCommitRatio = 1.3`이 영영 미달이고, **어떤 회전도 검출되지 않는다**.
- Android ML Kit Korean은 애초에 rotation-invariant다.
  `lineAspect`는 "콘텐츠가 누웠다"는 사실은 잡지만 **어느 쪽으로** 누웠는지는 못 가리므로, 고정 기본값 270°가 절반만 맞는다.

두 실패의 원인은 같다 — **인식 품질(줄 수·신뢰도)에서 방향을 읽어내려 했다.**
두 엔진 모두 회전에 강건해진 이상 품질 신호에는 방향 정보가 없다.

## 신호

품질 대신 **기하**를 쓴다. 단, 박스 _모양_(aspect)을 추론하는 간접 경로가 아니라 엔진이 이미 계산해 둔 **텍스트 각도**를 직접 읽는다.

`A` = 이미지 수평축 기준 텍스트 라인의 **시계방향 양수(CW-positive)** 각도.

| 플랫폼  | 취득 방법                                                                                                                                   |
| ------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| Android | `Text.Line.getAngle()` — 문서상 _"angle (in degrees, clockwise is positive, range is \[-180, 180]) of the rotation of the recognized line"_ |
| iOS     | `VNRecognizedTextObservation`의 `topLeft` / `topRight`에서 유도 (아래 식)                                                                   |

Android는 CW-positive라 이 패키지가 이미 채택한 CW 정준화(§3.1)와 **방향이 그대로 일치**한다 — 변환 단계가 없다.

iOS는 Vision이 정규화 `[0, 1]` · **bottom-left 원점**을 쓰므로 y축을 뒤집어야 CW가 된다.

```log
A_cw = atan2(−(topRight.y − topLeft.y), topRight.x − topLeft.x) × 180 / π
```

`topRight − topLeft`는 텍스트 자신의 진행 방향 벡터다.
bottom-left 원점에서 top-left 원점으로 옮기면 y 성분의 부호가 뒤집히고, 화면 좌표계(+y 아래)에서 CW 양수 각도는 `atan2(dy_screen, dx)`가 된다.

## 보정 각도

CW-`d`만큼 기울어진 텍스트를 되돌리는 것은 CW-`(360 − d)` 회전이다.

```log
correction_CW = (360 − quantize(A)) % 360
```

`quantize`는 가장 가까운 1/4 회전으로 반올림한 뒤 `[0, 360)`으로 정규화한다 (`-90` → `270`).

### 실측 교차검증

이 공식이 2026-07-18 field data의 두 케이스를 모두 재현하는지가 설계의 1차 근거다.

| 실측 입력 콘텐츠 | `A`         | 공식 결과         | v1.3 고정 270° 동작 | 판정           |
| ---------------- | ----------- | ----------------- | ------------------- | -------------- |
| CW 90°           | `+90`       | `postRotate(270)` | `postRotate(270)`   | 유지 ✅        |
| CW 270°          | `−90` → 270 | `postRotate(90)`  | `postRotate(270)`   | **수정** ❌→✅ |

맞던 케이스를 유지하고 틀리던 케이스를 고친다.
180° 단독 회전은 `A ≈ ±180`으로 나타나므로 별도 처리 없이 따라온다 — v1.3이 비범위로 둔 항목이 해소된다.

## 집계 — wrap-safe

각도를 **선형 평균하지 않는다.**
`−179`와 `+179`의 평균은 `0`으로, 실제(≈180°)와 정반대의 최악값이다.

라인별 각도를 `quantize`로 `{0, 90, 180, 270}` 4개 bin에 넣고 **최빈값(mode)** 을 취한다.
bin 경계에서 wrap이 이미 해소되므로 순환 평균이 따로 필요 없다.

판정은 두 게이트를 모두 통과해야 성립한다.

| 게이트            | 값    | 근거                                                                                       |
| ----------------- | ----- | ------------------------------------------------------------------------------------------ |
| `ANGLE_MIN_LINES` | `5`   | `MISMATCH_MIN_LINES`와 동일 — trimmed 통계가 신뢰도를 갖는 최소 표본                       |
| `ANGLE_MAJORITY`  | `0.7` | 같은 종이에 인쇄된 라인은 각도가 사실상 일치하므로 정상 신호는 0.95+. 0.5 초과라 동률 불가 |

**PROVISIONAL** — 실기기 로그로 확정한다 ([`threshold-calibration.md`](./threshold-calibration.md) 절차).
회전 오검출이 미검출보다 나쁘다는 기존 편향(ADR-006 D7)을 유지해 보수적으로 잡았다.

## 폴백 배선

양 플랫폼 대칭이다.

```log
angle 판정 성공 (게이트 통과) → correction_CW 적용. correction == 0 이면 "정방향 확정"으로 회전하지 않음
angle 판정 실패 (표본 부족·분산 과다) → 기존 경로로 하강
                                        Android: lineAspect mismatch (v1.3)
                                        iOS:     probe 루프 (v2.0)
```

iOS probe 루프는 iOS 26.5 실측상 死코드(`kRotateCommitRatio` 미발동)지만 이번 스펙에서 **삭제하지 않는다**.
Android가 `lineAspect`를 폴백으로 유지하는 것과 대칭이고, 이 패키지가 지원하는 **iOS 16/17에서는 Vision이 아직 rotation-variant라 probe 루프가 살아 있는 경로**이기 때문이다.
삭제는 angle 신호가 실측으로 입증된 다음 변경에서 처리한다.

### 회귀 가드 — `correction == 0`을 어디까지 믿을 것인가

가정이 깨졌을 때(각도가 리딩 프레임 기준) 증상은 **"회전된 영수증인데 모든 라인이 정방향"**, 즉 `turn = 0`에 `share ≈ 1.0`이다.
이걸 액면 그대로 "정방향 확정"으로 받으면 **현재 동작하는 폴백을 억제**해 회귀가 된다.
따라서 `turn == 0`의 취급은 각 플랫폼 폴백의 성격에 맞춰 다르게 배선한다.

| 플랫폼  | `turn == 0`일 때                                  | 근거                                                                                                                                                                 |
| ------- | ------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Android | `lineAspect`가 회전을 가리키지 **않을 때만** 신뢰 | lineAspect는 정상 영수증을 오검출하므로 "정방향 확정"의 억제 효과가 실익이다. 단 둘이 충돌하면 angle을 버리고 v1.3 동작을 유지한다                                   |
| iOS     | 항상 폴백으로 하강 (角 0은 액션이 아님)           | iOS 폴백은 애초에 회전에 보수적이라 억제할 false positive가 없고, iOS 16/17에서는 폴백이 유일하게 동작하는 경로다. 얻는 것 없이 회귀 위험만 있으므로 신뢰하지 않는다 |

결과적으로 **신호가 죽어 있으면 양 플랫폼 모두 현행 동작이 그대로 유지된다** — 회귀가 아니라 no-op이다.
살아 있으면 90/270 구분과 180 검출이 새로 열린다.

## 가정과 그 리스크

이 설계는 검증되지 않은 가정 하나에 얹혀 있다.

> 두 엔진이 보고하는 각도·코너는 **이미지 공간** 기준이다 — 즉 회전된 영수증에서 `A ≈ ±90 / ±180`이 나온다.

반대 가능성은 엔진이 내부적으로 정규화한 **리딩 프레임** 기준으로 보고하는 것이다.
그 경우 회전 여부와 무관하게 `A ≈ 0`이 나오고 신호는 죽는다.

- Android: `getAngle()`의 *의미*는 문서화돼 있으나 rotation-invariant 리더에서의 실제 반환값은 문서에 없다.
  `lineAspect = 0.23`(회전 케이스)이 관측된 이상 `boundingBox`는 이미지 공간을 반영하므로 `getAngle()`도 그럴 개연성이 높다 — 그러나 개연성은 이 저장소가 측정으로 대체하기로 한 대상이다.
- iOS: `VNRecognizedTextObservation`의 코너가 텍스트를 따라 회전하는지 자체가 **미문서화**다.
  헤더는 4개 코너가 각각 독립 `CGPoint`임만 보장한다 (축정렬 사각형에서 유도되지 않음).

### 리스크 완화

가정이 깨졌을 때의 비용을 **상수·공식 한 곳**으로 국소화한다.

- quantize / 최빈값 / 보정 수학은 **플랫폼 무관 순수 함수**로 `OcrGeometry`(Kotlin)에 두고 JUnit이 부호 규약을 고정한다.
  `RNOcrGeometry`가 같은 공식을 미러한다.
- 진단 로그에 `angleBins` / `angleN`을 실어, **같은 QA 런**이 라우팅 검증과 신호 생사 판정을 동시에 처리한다.
- 죽은 신호는 게이트를 미달하지 **않는다** — `turn = 0`을 `share ≈ 1.0`으로 자신 있게 내놓는다.
  이 경우를 잡는 것은 게이트가 아니라 §회귀 가드이며, 그래서 그 가드가 이 설계에서 선택 사항이 아니다.

## 진단 로그

```bash
adb logcat -s ReceiptScanner.Ocr:I     # Android
# iOS: Xcode 콘솔, [ReceiptScanner.Ocr] 필터 (DEBUG 빌드 전용)
```

`angleBins`는 `[0°, 90°, 180°, 270°]` 순의 라인 수다.
승자만이 아니라 분포 전체를 싣는 이유는 `ANGLE_MAJORITY` 보정에 산포가 필요하기 때문이다.

```log
# 정상 portrait — angle이 정방향을 확정
I/ReceiptScanner.Ocr: probe deg=0 file=…jpg lineCount=47 lineAspect=4.82 textLength=600 imageAspect=0.373 angleBins=[47,0,0,0] angleN=47
I/ReceiptScanner.Ocr: decision file=…jpg chosen=0 reason=text-angle-0 lineCount=47 lineAspect=4.82 textLength=600

# 가로로 눕힌 영수증 — CW 90°로 누웠으므로 270° 보정
I/ReceiptScanner.Ocr: probe deg=0 file=…jpg lineCount=34 lineAspect=0.23 textLength=403 imageAspect=2.218 angleBins=[1,32,0,1] angleN=34
I/ReceiptScanner.Ocr: decision file=…jpg chosen=270 reason=text-angle-90 lineCount=34 lineAspect=0.23 textLength=403

# 신호가 죽어 있는 경우 — 전부 0으로 몰리고 폴백이 판단
I/ReceiptScanner.Ocr: probe deg=0 file=…jpg lineCount=34 lineAspect=0.23 textLength=403 imageAspect=2.218 angleBins=[34,0,0,0] angleN=34
I/ReceiptScanner.Ocr: decision file=…jpg chosen=270 reason=landscape-vertical-lines lineCount=34 lineAspect=0.23 textLength=403
```

iOS는 DEBUG 빌드에서 같은 필드를 `logDiagnostics:`가 찍는다.

```log
[ReceiptScanner.Ocr] pass1 0deg accurate count=52 meanConf=0.84 candidateSpread=0.18 boxAspect=0.24 angleBins=[0,50,1,1]
```

마지막 Android 예시가 **가정 붕괴의 지문**이다 — `lineAspect=0.23`이 회전을 가리키는데 `angleBins`가 0°에 몰려 있으면 각도가 이미지 공간이 아니라 리딩 프레임 기준이라는 뜻이다.
이 조합에서는 각도 경로가 스스로 물러나고 v1.3 동작이 그대로 유지되도록 배선돼 있다 (아래 §회귀 가드).

## 검증 기준 (Definition of Done)

- [ ] CW 90°로 눕힌 영수증 → 출력 이미지 정방향 (v1.3에서도 통과하던 케이스, 회귀 없음)
- [ ] CW 270°로 눕힌 영수증 → 출력 이미지 정방향 (**v1.3이 180° 뒤집던 케이스**)
- [ ] 180° 뒤집은 영수증 → 출력 이미지 정방향 (v1.3 비범위였던 신규 케이스)
- [ ] 정상 portrait → 회전 미적용, 회귀 없음
- [ ] 양 플랫폼 모두 `ocrLines` 오버레이가 회전 후 이미지에서 텍스트에 정합 (0.7.0 데모로 즉시 판별)
- [ ] `angleTurn` / `angleShare` 로그 수집 → 임계값 PROVISIONAL 해제
- [ ] 라인 부족(`lineCount < 5`) 입력 → 폴백 하강, 회귀 없음

## 비범위 (Non-Goals)

- **JS 표면 변경 없음.** `rotationDegrees`는 native-internal이고 `OcrLine` 계약은 그대로다. 각도를 JS로 노출하지 않는다 (아무도 요구하지 않았고 ADR-003 범위 밖).
- **임의 각도 보정.** 1/4 회전만 다룬다. 3° 기울어진 영수증의 deskew는 별개 기능이며 이 스펙의 대상이 아니다.
- **iOS probe 루프 삭제.** 신호 입증 후 별도 변경.
- **원근 왜곡 기반 상하 판별.** 코너의 사다리꼴 왜곡으로 촬영 각도를 추정하는 접근은 크롭 단계에서 이미 보정되므로 여기서는 신호가 남지 않는다.

## Cross-references

- [`../notes/platform-asymmetries.md`](../notes/platform-asymmetries.md) §2.1 (회전 강건성), §3.1 (CW 정준화)
- [`ocr-line-geometry.md`](./ocr-line-geometry.md) — 0.7.0 `ocrLines` 계약, 검증에 쓰는 오버레이 데모
- [`ios-geometry-rotation-routing.md`](./ios-geometry-rotation-routing.md) — 기하 기반 라우팅 초안 0.1. step-1 측정 / step-2 게이트 구조를 이 스펙이 계승한다
- [`threshold-calibration.md`](./threshold-calibration.md) — PROVISIONAL 임계값 확정 절차
