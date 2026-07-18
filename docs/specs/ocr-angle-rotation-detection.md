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

## 가정과 그 리스크 — ✅ 2026-07-19 실측으로 확정

이 설계는 검증되지 않은 가정 하나에 얹혀 있었다.

> 두 엔진이 보고하는 각도·코너는 **이미지 공간** 기준이다 — 즉 회전된 영수증에서 `A ≈ ±90 / ±180`이 나온다.

반대 가능성은 엔진이 내부적으로 정규화한 **리딩 프레임** 기준으로 보고하는 것이었다.
그 경우 회전 여부와 무관하게 `A ≈ 0`이 나오고 신호가 죽는다.

**2026-07-19 실기기 QA에서 가정이 참으로 확정됐다.** 양 플랫폼 모두 90°·270° 입력에서 출력 이미지가 정방향으로 나왔다.

| 플랫폼  | 캡처   | 줄 수 | 신뢰도        | 출력 방향 |
| ------- | ------ | ----- | ------------- | --------- |
| Android | 90/270 | 46/45 | 79.9% / 80.6% | 정방향 ✅ |
| iOS     | 90/270 | 44/46 | 87.5% / 91.1% | 정방향 ✅ |

이 결과는 신호가 살아 있을 때만 가능하다. 죽어 있었다면 §회귀 가드가 발동해 v1.3 동작(Android는 270 고정이라 한쪽만 정답, iOS는 회전 없음)이 유지됐을 것이므로, **두 방향이 동시에 맞았다는 사실 자체가 각도가 방향을 담고 있다는 증거**다.

부수적으로 iOS `atan2` y-반전 부호도 확정됐다 — 유닛 테스트가 닿지 않는 유일한 공식이었고, 부호가 뒤집혀 있었다면 90과 270이 서로 바뀌어 한쪽이 180° 어긋났을 것이다.

남은 미검증 항목은 **180° 단독 회전**이다 (아래 DoD).

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

- [x] CW 90°로 눕힌 영수증 → 출력 이미지 정방향 (2026-07-19, 양 플랫폼)
- [x] CW 270°로 눕힌 영수증 → 출력 이미지 정방향 (2026-07-19, 양 플랫폼) — **v1.3이 180° 뒤집던 케이스**
- [x] 양 플랫폼 모두 `ocrLines` 오버레이가 회전 후 이미지에서 텍스트에 정합 (2026-07-19, 4캡처 전부)
- [ ] 180° 뒤집은 영수증 → 출력 이미지 정방향 (v1.3 비범위였던 신규 케이스, 미측정)
- [ ] 회전 적용 케이스에서 `ocrText` 라인 순서가 출력 이미지와 일치 (회전 후 재인식, 2026-07-19 추가 — 미측정)
- [ ] 정상 portrait → 회전 미적용, 회귀 없음
- [ ] `angleBins` 로그 수집 → `ANGLE_MIN_LINES` / `ANGLE_MAJORITY` PROVISIONAL 해제
- [ ] 라인 부족(`lineCount < 5`) 입력 → 폴백 하강, 회귀 없음

### 실측으로 열린 후속 항목

- **폴백 삭제 검토.** 신호가 입증됐으므로 §폴백 배선이 예고한 "다음 변경"의 조건이 충족됐다. 다만 Android `lineAspect`와 iOS probe 루프는 각각 라인이 적은 입력과 iOS 16/17을 여전히 덮으므로, 삭제 전에 그 두 케이스의 실측이 필요하다.
- **`ocrText` 라인 순서 (Android)** — 회전 후 재인식으로 수정함. 아래 별도 절 참조. 미검증(아래 DoD).

## 관측: 회전 시 Android `ocrText` 라인 순서가 뒤집힌다 (2026-07-19)

QA 캡처 한 장에서 출력 이미지와 `ocrLines` 박스는 모두 정상인데 `ocrText`만 영수증 아래→위 순서로 나왔다.

```log
드를 지참 시 교환/환불 가능합니다.      ← 영수증 맨 아래 줄이 첫 줄로
구매일로부터 7일 이내 영수증과 결제카
emart24 고객센터 1588-1234
```

원인은 **Android가 회전 후 OCR을 다시 돌리지 않는다**는 데 있다.
`runDetection`은 모든 분기에서 `pass0.text`를 반환하고, 회전은 그 뒤 `ReceiptScannerModule.applyAutoRotateIfNeeded`가 파일에 적용한다.
따라서 텍스트 순서는 회전 _전_ 프레임에서 ML Kit이 정한 순서로 남는다.
박스는 `OcrGeometry.rotateClockwise`로 리매핑되므로 영향받지 않는다.

iOS에는 이 현상이 없다 — `resultByRotating:`이 회전된 프레임에서 accurate 패스를 다시 돌리므로 순서가 출력 이미지와 일치한다.

**이 스펙이 만든 문제는 아니다.** v1.3도 회전할 때 같은 동작이었으나 회전이 `landscape-vertical-lines` 한 분기에서만 발동했다. 각도 라우팅이 90/270/180을 모두 잡으면서 노출 빈도가 늘어난 것이다.

### 해소 (2026-07-19) — 회전 후 재인식

`ReceiptScannerModule.runOcrAndAutoRotate`가 회전을 적용한 직후 `OcrProcessor.recognizeInFinalFrame`으로 출력 파일을 다시 인식한다.
재인식 패스는 회전 검출을 돌리지 않는다 — 이미 정방향이므로 낭비이고, 헛된 0 판정으로 폴백을 건드릴 여지만 생긴다.

성공 경로에서는 박스가 출력 프레임에서 직접 측정되므로 리매핑이 `0`이 된다.
`OcrGeometry.rotateClockwise`는 **재인식이 실패했을 때만** 쓰인다 (방금 쓴 파일을 디코드하지 못하는 드문 경우) — 그때는 첫 패스 결과를 유지하고 박스만 돌린다. 회전 보정을 잃는 편이 텍스트 전체를 잃는 것보다 싸기 때문이다.

`lineCount` / `confidence`도 재인식 패스 값이 된다. `lineCount`가 JS `OcrFloor` 게이트의 입력이므로 회전 경로에서 게이트가 보는 숫자가 바뀌지만, ML Kit이 rotation-invariant라 값은 거의 같고 정방향 이미지 기준이라 더 정직하다.

비용은 **회전이 실제로 일어날 때만** OCR 1패스(~150 ms)다.

⚠️ 이 변경은 컴파일과 순수 헬퍼 유닛 테스트까지만 검증됐다 — 둘 다 라인 *순서*를 건드리지 않는다. 순서가 실제로 교정됐는지는 `example/data/RECEIPT-90.jpg` / `RECEIPT-270.jpg`로 디바이스 QA를 한 번 더 돌려야 확정된다.

사용자 영향 평가는 [`../notes/platform-asymmetries.md`](../notes/platform-asymmetries.md) §4.2.

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
