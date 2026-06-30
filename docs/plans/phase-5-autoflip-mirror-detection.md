# Phase 5 — Auto-flip (Horizontal Mirror Detection)

> **Depends on:** Phase 4 (OCR rotation correction) — autoFlip은 autoRotate **다음에** 실행되어, upright 정렬이 끝난 후보에서만 horizontal flip 비교를 수행한다. 8후보 조합폭발 방지.

## Goal

수평으로 반전(mirror)된 갤러리 영수증 사진을 OCR 품질 비교로 감지하고, `autoFlip: true` (default) 일 때 출력 JPEG 픽셀에 `horizontalFlip` 을 베이크하여 반환한다.

기본값은 **`autoFlip: true`** — 영수증 앱 전용 패키지이므로 도메인 목적에 부합. 의심 케이스에서만 두 번째 OCR pass가 수행된다 (`mode: "suspiciousOnly"`).

---

## Scope (MVP)

| 포함                                              | 제외                                                                 |
| ------------------------------------------------- | -------------------------------------------------------------------- |
| `horizontalFlip` 만 비교 (`none` vs `flip` 2후보) | `verticalFlip`, `transpose`, `transverse` — 영수증 도메인에서 비효율 |
| `ocr === true` 일 때만 동작                       | `autoRotate` 와 8후보 조합 — 순차 적용                               |
| iOS + Android 동시 구현 (휴리스틱 score 기반)     | ML classifier — 휴리스틱으로 충분히 잡히는지 먼저 검증               |

뒤집힌 영수증 OCR은 보통 다음과 같은 특징을 보인다:

```log
- 완성형 한글 단어 비율 낮음
- 자모/기호/깨진 문자 비율 높음
- 금액/날짜/시간 패턴 적음
- 영수증 도메인 키워드 적음
- line은 많은데 의미 있는 token이 적음
```

점수 함수는 이 신호들을 가중치 합으로 환산한다.

---

## API 추가

### `ScanReceiptOptions.autoFlip`

`src/types.ts`:

```ts
export type AutoFlipOptions = {
  /** "suspiciousOnly" (default): Pass 1 결과가 의심스러울 때만 Pass 2 OCR 수행. "alwaysCompare": 항상 두 후보 비교. "off": autoFlip 비활성화와 동일. */
  mode?: "off" | "suspiciousOnly" | "alwaysCompare";
  /** 텍스트량이 이보다 적으면 flip 판정 자체를 건너뜀 (작은 영수증 false positive 방지). */
  minTextLength?: number;
  /** 라인 수가 이보다 적으면 flip 판정 자체를 건너뜀. */
  minLines?: number;
  /** flip 후보가 채택되려면 score 가 원본보다 이 값 이상 커야 함. */
  minScoreDelta?: number;
  /** flip 후보가 채택되려면 score 가 원본의 이 배수 이상이어야 함. */
  minScoreRatio?: number;
};

export type ScanReceiptOptions = {
  // ...기존 필드
  /**
   * Detect horizontally mirrored text by comparing OCR quality between the
   * current image and a horizontally flipped candidate. Only applies when
   * `ocr === true`. The flipped pixels are baked into the output JPEG when
   * adopted; `exif.orientation` remains `1`.
   *
   * @defaultValue `true`
   */
  autoFlip?: boolean | AutoFlipOptions;
};

export const DEFAULT_AUTOFLIP_OPTIONS: Required<AutoFlipOptions> = {
  mode: "suspiciousOnly",
  minTextLength: 20,
  minLines: 2,
  minScoreDelta: 12,
  minScoreRatio: 1.25,
};
```

`DEFAULT_SCAN_OPTIONS.autoFlip = true` 로 추가.

### `ReceiptImage.correction`

현재 `ReceiptImage`에는 보정 흔적이 없다. `exif.orientation`이 항상 `1`로 고정되어 있어 원래 어떤 보정이 적용됐는지 추적 불가. 별도 필드로 노출:

```ts
export type ImageTransform =
  "rotate90" | "rotate180" | "rotate270" | "horizontalFlip" | "verticalFlip";

export type OcrCandidateQuality = {
  transform: "none" | "horizontalFlip";
  score: number;
  textLength: number;
  lineCount: number;
  confidence?: number;
  validTokenRatio?: number;
  weirdTokenRatio?: number;
};

export type ImageCorrection = {
  appliedTransforms: ImageTransform[];
  reason: "exif" | "ocr_rotation" | "ocr_flip" | "none";
  comparedCandidates?: OcrCandidateQuality[];
};

export type ReceiptImage = {
  // ...기존 필드
  correction?: ImageCorrection;
};
```

### `OcrQuality` 확장

기존:

```ts
type OcrQuality = {
  textLength: number;
  lineCount: number;
  confidence?: number;
};
```

확장:

```ts
export type OcrQuality = {
  textLength: number;
  lineCount: number;
  /** iOS 만 제공. Android ML Kit Korean recognizer는 line/element confidence를 노출하지 않는다. */
  confidence?: number;

  score: number;
  validTokenCount: number;
  weirdTokenCount: number;
  validTokenRatio: number;
  weirdTokenRatio: number;
  numericTokenCount: number;
  amountLikeTokenCount: number;
  dateLikeTokenCount: number;
  receiptKeywordCount: number;

  candidateTransform?: "none" | "horizontalFlip";
};
```

---

## Step 0 — Fixture 먼저 (가장 먼저 할 일)

알고리즘 튜닝과 회귀 방지의 grounding. **threshold 는 실데이터로만 의미가 있다.**

- [ ] `src/__tests__/fixtures/ocr/` 디렉토리 생성
- [ ] 정상 영수증 OCR 텍스트 **10건** (`normal-*.json`)
- [ ] 좌우반전 영수증 OCR 텍스트 **10건** (`mirrored-*.json`)
- [ ] 텍스트량은 비슷하지만 자모/깨짐 비율이 높은 케이스 포함
- [ ] fixture 수집 방법은 `src/__tests__/fixtures/ocr/README.md` 에 기록 (실기기에서 example 앱 dump → JSON 저장 → 익명화 절차)
- [ ] 예제 앱에 OCR 결과를 fixture JSON 으로 dump 하는 디버그 UI 추가 (수집 가능 인프라)

### Fixture JSON 스키마

```json
{
  "id": "normal-001",
  "platform": "ios",
  "source": "camera",
  "transform": "none",
  "tokens": ["메가커피", "아메리카노", "4,500원"],
  "lines": ["메가커피", "아메리카노 4,500원"],
  "confidence": 0.91,
  "notes": "anonymized — store name kept, card/biz number redacted"
}
```

- `transform`: `"none" | "horizontalFlip"` — 좌우반전 fixture는 `"horizontalFlip"` 적용 후 OCR 한 결과
- `confidence`: iOS에서만 채움 (Android는 `null`)
- `notes`: 익명화 처리 내역 (사업자번호, 카드번호, 전화번호 redact 기록)

---

## Step 1 — `scoreReceiptOcr` (순수 JS)

> 점수 함수는 **JS 레이어**에 둔다. 네이티브에서 token/line 배열만 전달받아 JS 가 점수 매기면 두 플랫폼에서 동일한 휴리스틱이 보장된다. 휴리스틱 안정화 단계에서는 JS-only, 성능 문제 발견 시에만 네이티브 이식.

### 1-1. `src/types.ts` — 타입 추가

상단 "API 추가" 섹션의 타입 추가 (`AutoFlipOptions`, `ImageCorrection`, `ImageTransform`, `OcrCandidateQuality`, 확장된 `OcrQuality`, 확장된 `ScanReceiptOptions`, `DEFAULT_AUTOFLIP_OPTIONS`).

- [ ] `DEFAULT_SCAN_OPTIONS.autoFlip = true`
- [ ] `src/__tests__/index.test.tsx` — `autoFlip` 기본값 propagation 검증

### 1-2. `src/ocr/scoreReceiptOcr.ts` — 점수 함수 (신규)

영수증 도메인 패턴:

```ts
const receiptKeywordPatterns = [
  /영수증/,
  /합계|총액|결제|승인|매출|공급가|부가세/,
  /카드|현금|일시불/,
  /사업자|대표|상호|주소|전화/,
  /주문|상품|수량|단가|금액/,
];

const amountPattern = /\d{1,3}(,\d{3})+|\d+\s?원/;
const datePattern = /\d{4}[./-]\d{1,2}[./-]\d{1,2}|\d{2}[./-]\d{1,2}[./-]\d{1,2}/;
const timePattern = /\d{1,2}:\d{2}/;
```

token 분류:

```ts
function isValidToken(token: string): boolean {
  return (
    /[가-힣]{2,}/.test(token) ||
    /[A-Za-z]{2,}/.test(token) ||
    /\d{2,}/.test(token) ||
    amountPattern.test(token) ||
    datePattern.test(token)
  );
}

function isWeirdToken(token: string): boolean {
  if (!token.trim()) return true;

  const hasHangulJamo = /[ㄱ-ㅎㅏ-ㅣ]/.test(token);
  const symbolHeavy = token.replace(/[가-힣A-Za-z0-9,.:/%₩원-]/g, "").length / token.length > 0.4;
  const tooFragmented = token.length <= 1 && !/\d/.test(token);

  return hasHangulJamo || symbolHeavy || tooFragmented;
}
```

점수 함수:

```ts
function scoreReceiptOcr(tokens: string[], lines: string[], confidence?: number): OcrQuality {
  const text = lines.join("\n").trim();

  const validTokens = tokens.filter(isValidToken);
  const weirdTokens = tokens.filter(isWeirdToken);
  const amountLikeTokens = tokens.filter((t) => amountPattern.test(t));
  const dateLikeTokens = tokens.filter((t) => datePattern.test(t) || timePattern.test(t));

  const receiptKeywordCount = receiptKeywordPatterns.reduce(
    (count, pattern) => count + (pattern.test(text) ? 1 : 0),
    0
  );

  const validTokenRatio = tokens.length === 0 ? 0 : validTokens.length / tokens.length;
  const weirdTokenRatio = tokens.length === 0 ? 1 : weirdTokens.length / tokens.length;

  const score =
    Math.min(text.length, 200) * 0.08 +
    Math.min(lines.length, 20) * 1.5 +
    validTokenRatio * 35 -
    weirdTokenRatio * 30 +
    Math.min(amountLikeTokens.length, 10) * 2.5 +
    Math.min(dateLikeTokens.length, 5) * 3 +
    Math.min(receiptKeywordCount, 5) * 5 +
    (confidence ?? 0) * 20;

  return {
    textLength: text.length,
    lineCount: lines.length,
    confidence,
    score,
    validTokenCount: validTokens.length,
    weirdTokenCount: weirdTokens.length,
    validTokenRatio,
    weirdTokenRatio,
    numericTokenCount: tokens.filter((t) => /\d/.test(t)).length,
    amountLikeTokenCount: amountLikeTokens.length,
    dateLikeTokenCount: dateLikeTokens.length,
    receiptKeywordCount,
  };
}
```

- [ ] 위 함수 + 상수 + helper 구현
- [ ] Jest 테스트: Step 0 의 fixture 20개로 회귀 검증
  - 정상 fixture: `score` 가 mirrored fixture 보다 평균 `minScoreDelta(=12)` 이상 큼
  - 정상 fixture: `validTokenRatio >= 0.45`, `weirdTokenRatio <= 0.35` 만족
  - mirrored fixture: 반대 경향 확인

---

## Step 2 — 판정 로직 (네이티브 공통 패턴)

판정은 네이티브 OCR 호출을 감싸는 **correction coordinator** 레벨에서 한다 (OCR 함수 내부 X).

### 2-1. 의심 판정 (suspicious)

```ts
const isSuspicious =
  options.autoFlip &&
  originalQuality.textLength >= minTextLength &&
  originalQuality.lineCount >= minLines &&
  (originalQuality.score < 55 ||
    originalQuality.validTokenRatio < 0.45 ||
    originalQuality.weirdTokenRatio > 0.35 ||
    originalQuality.receiptKeywordCount === 0);
```

### 2-2. flip 채택 조건

```ts
const isBetter =
  flippedQuality.score >= originalQuality.score + minScoreDelta ||
  flippedQuality.score >= originalQuality.score * minScoreRatio;
```

### 2-3. 공통 flow

```log
1. OCR pass 1 (원본 upright 후보)
2. score(pass1) → suspicious 판정
3. suspicious 면 horizontalFlip 후보 생성 → OCR pass 2
4. score(pass2) 와 score(pass1) 비교 → flip 채택 여부
5. 채택 시: flip 이미지로 JPEG 재인코딩 + ocrText/ocrQuality 교체
   + ReceiptImage.correction = {
       appliedTransforms: ["horizontalFlip"],
       reason: "ocr_flip",
       comparedCandidates: [
         { transform: "none", ...originalQuality },
         { transform: "horizontalFlip", ...flippedQuality },
       ],
     }
6. 미채택 시: 원본 유지, correction.reason = "none" (또는 autoRotate 가 이미 보정했으면 "ocr_rotation")
```

`autoRotate` 와의 순서: **autoRotate 먼저 → 최종 upright 후보에서만 horizontalFlip 비교.**

---

## Step 3 — iOS 구현

> 수정 파일: `ios/RNOcrProcessor.{h,m}`, `ios/RNImageProcessor.{h,m}`, `ios/ReceiptScanner.mm`, `ios/RNScanOptions.{h,m}`.

iOS 는 `VNRecognizedText.confidence` 를 사용할 수 있어 점수 함수에 confidence term이 의미 있게 들어간다.

- [ ] `RNScanOptions` — `autoFlip` (`BOOL` 또는 dictionary) 파싱
- [ ] `RNOcrProcessor` — OCR 결과를 **token 배열 + line 배열 + mean confidence** 로 노출 (현재는 joined string만 반환). JS 점수 함수가 소비할 수 있는 raw 형태.
- [ ] `RNImageProcessor` — `horizontallyFlipped:(UIImage *)image` 헬퍼 추가 (`UIImageOrientationUpMirrored` 적용 후 `kCGImagePropertyOrientationUp` 으로 정규화 재인코딩, EXIF orientation = 1 유지)
- [ ] `ReceiptScanner.mm` — coordinator
  - Pass 1: 원본 OCR (accurate)
  - JS bridge 로 score 위임 → suspicious 판정
  - suspicious 면 Pass 2 (flip 이미지 OCR, accurate)
  - 채택 시 `RNImageProcessor` 로 flip 베이크 → JPEG 재인코딩 → 기존 temp file 교체
  - `ReceiptImage.correction` 채워서 반환

```objc
// ReceiptScanner.mm — coordinator 의사 코드
- (void)recognizeBestCandidate:(UIImage *)image options:(RNScanOptions *)options {
  RNOcrCandidate *original = [self runOcrPass:image transform:RNTransformNone];
  OcrQuality q1 = [self scoreInJS:original];

  if (![self shouldTryFlip:q1 options:options]) {
    return [self emit:original quality:q1];
  }

  UIImage *flipped = [RNImageProcessor horizontallyFlipped:image];
  RNOcrCandidate *flippedCand = [self runOcrPass:flipped transform:RNTransformHorizontalFlip];
  OcrQuality q2 = [self scoreInJS:flippedCand];

  if ([self isMeaningfullyBetter:q2 than:q1 options:options]) {
    [self emit:flippedCand quality:q2 correction:RNCorrectionOcrFlip
        candidates:@[q1, q2]];
  } else {
    [self emit:original quality:q1];
  }
}
```

**ADR-004 준수:** `VNImageRequestHandler` 는 `initWithCGImage:orientation:` 사용 (mirrored orientation은 `kCGImagePropertyOrientationUpMirrored`). `CIPerspectiveCorrection` 단계가 있다면 orientation 베이크 후 호출.

---

## Step 4 — Android 구현

> 수정 파일: `android/src/main/java/com/receiptscanner/OcrProcessor.kt`, `ImageProcessor.kt`, `ReceiptScannerModule.kt`, `ScanOptions.kt`.

Android ML Kit Text Recognition은 `Text → TextBlock → Line → Element` 구조를 반환한다. **confidence 는 노출되지 않으므로** 점수 함수에서 confidence term이 0이 되고, validTokenRatio / weirdTokenRatio / receiptKeywordCount 중심으로 판정한다.

- [ ] `ScanOptions` — `autoFlip` 파싱 (boolean | map)
- [ ] `OcrProcessor` — `Text` → `(tokens: List<String>, lines: List<String>, confidence: null)` 변환. element를 token으로 사용, line은 textBlock.lines로 매핑
- [ ] `ImageProcessor` — `flipHorizontally(bitmap: Bitmap): Bitmap` 헬퍼:

  ```kotlin
  fun Bitmap.flipHorizontally(): Bitmap {
    val matrix = Matrix().apply { preScale(-1f, 1f) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
  }
  ```

  flip 후 JPEG 재인코딩하면서 EXIF orientation = `1` 유지.

- [ ] `ReceiptScannerModule` — coordinator: iOS와 동일한 흐름, JS bridge 로 score 위임
- [ ] `ReceiptImage.correction` 채워서 반환

---

## Step 5 — 통합

- [ ] `src/scan.native.tsx` — `autoFlip` 정규화 + 기본값 머지 (`boolean` → `Required<AutoFlipOptions>` 변환 로직)
- [ ] `src/scan.tsx` (web fallback) — `autoFlip` no-op (web 은 OCR 미지원)
- [ ] `autoRotate` + `autoFlip` 동시 활성화 시 순서 검증: rotate → flip
- [ ] **autoRotate ↔ autoFlip OCR 결과 캐시:** autoRotate가 수행한 최종 upright OCR 결과를 autoFlip Pass 1 로 재사용. 현재 `RNOcrProcessor` 는 매번 새로 호출하므로 coordinator 레벨에서 명시적 캐시 전달 필요.
- [ ] `imageOrigin === "screenshot"` 인 경우 mirror 확률 낮음 — 휴리스틱 skip 고려 (선택적 최적화)

---

## Step 6 — 문서

- [ ] `README.md` 에 **Auto-flip — handling mirrored receipt photos** 섹션 추가:

  ````markdown
  ### Auto-flip — handling mirrored receipt photos

  Some gallery images are horizontally mirrored by editors or camera apps.
  Mirrored text can still produce a similar amount of OCR output, but the
  recognized text is usually low quality. When `autoFlip` is enabled, the
  library compares OCR quality between the current image and a horizontally
  flipped candidate. If the flipped candidate is meaningfully better, the
  output JPEG is written with the flip baked into the pixels.

  ```ts
  const result = await scan({
    source: "gallery",
    autoFlip: true,
  });

  console.log(result.images[0].correction);
  // → { appliedTransforms: ["horizontalFlip"], reason: "ocr_flip", ... }
  ```

  The detector runs only when `ocr: true`. By default it only tries the
  flipped OCR candidate when the initial OCR result looks suspicious,
  avoiding unnecessary second-pass OCR for normal images.

  ```ts
  await scan({
    autoFlip: {
      mode: "suspiciousOnly",
      minScoreDelta: 12,
      minScoreRatio: 1.25,
    },
  });
  ```
  ````

- [ ] README API table 에 `autoFlip` 행 추가:

  ```markdown
  | `autoFlip` | `boolean \| AutoFlipOptions` | `true` | Detect horizontally mirrored text by comparing OCR quality against a flipped candidate. Only applies when `ocr: true`. |
  ```

- [ ] `docs/specs/autoflip.md` 신규 작성 — score 함수, threshold 근거, fixture 출처
- [ ] `docs/notes/platform-asymmetries.md` 에 "Android ML Kit Text Recognition은 confidence를 노출하지 않으므로 autoFlip 점수에서 confidence term은 0으로 계산됨" 항목 추가
- [ ] `CHANGELOG.md` — 다음 minor 버전 항목 추가

---

## Definition of Done

- [ ] 정상 영수증은 flip 후보 OCR 을 **대부분 실행하지 않는다** (suspicious 판정 통과율 < 10%)
- [ ] 좌우반전 영수증은 flip 후보가 채택된다 (fixture 10건 중 ≥ 8건)
- [ ] 텍스트량이 비슷해도 `score` 차이로 구분된다
- [ ] 최종 JPEG 는 픽셀에 flip 이 베이크되어 있고 **EXIF orientation = 1**
- [ ] 앱에서 `result.images[0].correction` 으로 어떤 보정이 적용됐는지 추적 가능
- [ ] `yarn typecheck && yarn lint && yarn test && trunk fmt && trunk check` 통과
- [ ] 예제 앱에서 mirrored 갤러리 사진 1건 실기기 검증 (iOS + Android 각 1회)

---

## Risks / Open Questions

1. **점수 계산 위치** — JS 위임 vs 네이티브 중복 구현. 일단 JS-only 로 시작; coordinator 1회 스캔당 score 호출 2회(suspicious 판정 + 채택 비교)의 IPC 비용이 측정 가능한 수준이면 유지, 측정상 무시 못 할 경우에만 네이티브 이식 검토.
2. **autoRotate ↔ autoFlip OCR 캐시** — Step 5 참조. coordinator 레벨에서 Pass 1 OCR 결과를 재사용해야 OCR 호출이 1회 추가에 그친다.
3. **`ocrFloor` 와의 상호작용** — flip 채택 후 score 가 충분히 높아져 floor 통과하는 경우가 정상 시나리오. floor 검사는 **최종 채택된 후보** 의 quality 로 수행.
4. **Temp file lifecycle** — Pass 1 이미지와 Pass 2 (flip) 이미지가 동시에 cache 디렉토리에 존재. 채택 결정 후 미채택 후보 파일은 즉시 삭제.
5. **휴리스틱 threshold 의 정당성** — `score < 55`, `validTokenRatio < 0.45`, `weirdTokenRatio > 0.35` 의 절댓값은 Step 0 fixture 20개의 분포를 보고 재조정해야 한다. 현재 값은 출발점일 뿐.

---

## 관련 문서

- 직전 단계: `docs/plans/phase-4-ocr-orientation-correction.md`
- 점수 함수 명세: `docs/specs/autoflip.md` (Step 6 에서 작성)
- 플랫폼 비대칭: `docs/notes/platform-asymmetries.md`
- iOS 구현 제약: `docs/notes/adr-004-ios-crop-editor-realdevice-fixes.md`
