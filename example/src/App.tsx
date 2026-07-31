import { useEffect, useRef, useState } from "react";
import {
  ActivityIndicator,
  Alert,
  Animated,
  Image,
  type LayoutChangeEvent,
  Platform,
  Pressable,
  SafeAreaView,
  ScrollView,
  Share,
  StatusBar,
  StyleSheet,
  Text,
  TextInput,
  View,
} from "react-native";
import {
  getOcrCapabilities,
  scan,
  type ImageOrigin,
  type OcrCapabilities,
  type OcrLine,
  type OcrQuality,
  type ReceiptExif,
  type ReceiptImage,
  type ScanReceiptOptions,
  type ScanReceiptResult,
} from "react-native-receipt-scanner";

import { ANDROID_STATUS_BAR_INSET, C, R, S } from "./theme";

// ─── Scan options state ─────────────────────────────────────────────────────--
//
// Every ScanReceiptOptions field is surfaced as a demo control. The state is a
// single object (with a generic `set` updater) rather than ~14 individual
// useState hooks threaded as props — the option set is the whole point of this
// screen, so one bag keeps ScanPage's signature flat.

type ScanOptionsState = {
  source: "camera" | "gallery";
  ocr: boolean;
  includeExif: boolean;
  maxPages: number;
  quality: number;
  includeGpsExif: boolean;
  includeRawExif: boolean;
  autoRotate: boolean;
  cropAutoConfirm: boolean;
  minimumTextHeight: number;
  ocrGeometry: boolean;
  mergeOcrPages: boolean;
  // ocrFloor is `OcrFloor | false`; modelled here as an on/off flag plus the
  // three sub-thresholds, recombined in `buildOptions`.
  ocrFloorEnabled: boolean;
  floorMinTextLength: number;
  floorMinLines: number;
  floorMinConfidence: number;
};

const INITIAL_OPTIONS: ScanOptionsState = {
  source: "camera",
  ocr: true,
  includeExif: true,
  maxPages: 1,
  quality: 0.82,
  includeGpsExif: false,
  includeRawExif: false,
  autoRotate: true,
  cropAutoConfirm: false,
  minimumTextHeight: 0,
  ocrGeometry: true,
  mergeOcrPages: false,
  ocrFloorEnabled: true,
  floorMinTextLength: 12,
  floorMinLines: 2,
  floorMinConfidence: 0,
};

// Recombine the flattened demo state into the package's option shape.
function buildOptions(o: ScanOptionsState): ScanReceiptOptions {
  return {
    source: o.source,
    ocr: o.ocr,
    includeExif: o.includeExif,
    includeGpsExif: o.includeGpsExif,
    includeRawExif: o.includeRawExif,
    maxPages: o.maxPages,
    quality: o.quality,
    autoRotate: o.autoRotate,
    cropAutoConfirm: o.cropAutoConfirm,
    minimumTextHeight: o.minimumTextHeight,
    ocrGeometry: o.ocrGeometry,
    // Forwarded as-is even when the combination is invalid, so the demo shows
    // the real INVALID_MERGE_OPTION rejection instead of hiding it.
    mergeOcrPages: o.mergeOcrPages,
    ocrFloor: o.ocrFloorEnabled
      ? {
          minTextLength: o.floorMinTextLength,
          minLines: o.floorMinLines,
          minConfidence: o.floorMinConfidence,
        }
      : false,
  };
}

// ─── Shared components ────────────────────────────────────────────────────────

function SectionHeader({ title, description }: { title: string; description?: string }) {
  return (
    <View style={{ marginBottom: S.md }}>
      <Text style={styles.sectionTitle}>{title}</Text>
      {description && <Text style={styles.sectionDesc}>{description}</Text>}
    </View>
  );
}

function Card({ children, style }: { children: React.ReactNode; style?: object }) {
  return <View style={[styles.card, style]}>{children}</View>;
}

function Badge({ text }: { text: string }) {
  return (
    <View style={styles.platformBadge}>
      <Text style={styles.platformBadgeText}>{text}</Text>
    </View>
  );
}

function ToggleRow({
  label,
  value,
  onToggle,
  disabled = false,
  hint,
  badge,
}: {
  label: string;
  value: boolean;
  onToggle: () => void;
  disabled?: boolean;
  hint?: string;
  badge?: string;
}) {
  return (
    <Pressable
      style={styles.toggleRow}
      onPress={disabled ? undefined : onToggle}
      disabled={disabled}
    >
      <View style={styles.controlLabelCol}>
        <View style={styles.controlLabelRow}>
          <Text style={[styles.toggleLabel, disabled && styles.controlDisabled]}>{label}</Text>
          {badge && <Badge text={badge} />}
        </View>
        {hint && <Text style={styles.controlHint}>{hint}</Text>}
      </View>
      <View
        style={[styles.toggleTrack, value && styles.toggleTrackOn, disabled && styles.toggleDimmed]}
      >
        <View style={[styles.toggleThumb, value && styles.toggleThumbOn]} />
      </View>
    </Pressable>
  );
}

function ChipRow({
  label,
  values,
  value,
  onChange,
  format,
  disabled = false,
  hint,
  badge,
}: {
  label: string;
  values: number[];
  value: number;
  onChange: (v: number) => void;
  format?: (v: number) => string;
  disabled?: boolean;
  hint?: string;
  badge?: string;
}) {
  return (
    <View style={[styles.chipRow, disabled && styles.toggleDimmed]}>
      <View style={styles.controlLabelRow}>
        <Text style={[styles.toggleLabel, disabled && styles.controlDisabled]}>{label}</Text>
        {badge && <Badge text={badge} />}
      </View>
      {hint && <Text style={styles.controlHint}>{hint}</Text>}
      <View style={styles.chips}>
        {values.map((v) => {
          const selected = v === value;
          return (
            <Pressable
              key={v}
              style={[styles.chip, selected && styles.chipSelected]}
              onPress={disabled ? undefined : () => onChange(v)}
              disabled={disabled}
            >
              <Text style={[styles.chipText, selected && styles.chipTextSelected]}>
                {format ? format(v) : String(v)}
              </Text>
            </Pressable>
          );
        })}
      </View>
    </View>
  );
}

function StepperRow({
  label,
  value,
  onChange,
  min,
  max,
  disabled = false,
}: {
  label: string;
  value: number;
  onChange: (v: number) => void;
  min: number;
  max: number;
  disabled?: boolean;
}) {
  return (
    <View style={styles.stepperRow}>
      <Text style={[styles.toggleLabel, disabled && styles.controlDisabled]}>{label}</Text>
      <View style={[styles.stepper, disabled && styles.toggleDimmed]}>
        <Pressable
          style={styles.stepBtn}
          onPress={disabled ? undefined : () => value > min && onChange(value - 1)}
          disabled={disabled}
        >
          <Text style={styles.stepBtnText}>−</Text>
        </Pressable>
        <Text style={styles.stepValue}>{value}</Text>
        <Pressable
          style={styles.stepBtn}
          onPress={disabled ? undefined : () => value < max && onChange(value + 1)}
          disabled={disabled}
        >
          <Text style={styles.stepBtnText}>+</Text>
        </Pressable>
      </View>
    </View>
  );
}

function MetaRow({ label, value }: { label: string; value: string }) {
  return (
    <View style={styles.metaRow}>
      <Text style={styles.metaLabel}>{label}</Text>
      <Text style={styles.metaValue}>{value}</Text>
    </View>
  );
}

function Collapsible({
  title,
  children,
  defaultOpen = false,
}: {
  title: string;
  children: React.ReactNode;
  defaultOpen?: boolean;
}) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <View>
      <Pressable style={styles.collapseHeader} onPress={() => setOpen(!open)}>
        <Text style={styles.collapseTitle}>{title}</Text>
        <Text style={styles.collapseChevron}>{open ? "▲" : "▼"}</Text>
      </Pressable>
      {open && <View style={styles.collapseBody}>{children}</View>}
    </View>
  );
}

// ─── Origin badge ─────────────────────────────────────────────────────────────

const ORIGIN_LABEL: Record<ImageOrigin, string> = {
  camera: "카메라",
  screenshot: "스크린샷",
  download: "다운로드",
  unknown: "알 수 없음",
};

const ORIGIN_COLORS: Record<ImageOrigin, { bg: string; fg: string }> = {
  camera: { bg: C.successBg, fg: C.successFg },
  screenshot: { bg: C.primaryMuted, fg: C.primary },
  download: { bg: C.warnBg, fg: C.warnFg },
  unknown: { bg: C.surfaceAlt, fg: C.ink400 },
};

function OriginBadge({ origin }: { origin: ImageOrigin }) {
  const colors = ORIGIN_COLORS[origin];
  return (
    <View style={[styles.originBadge, { backgroundColor: colors.bg }]}>
      <Text style={[styles.originBadgeText, { color: colors.fg }]}>{ORIGIN_LABEL[origin]}</Text>
    </View>
  );
}

// ─── Fixture dump (Phase 5 Step 0) ────────────────────────────────────────────
//
// Collects real-device OCR output and shares it as a JSON blob matching
// `src/__tests__/fixtures/ocr/types.ts#OcrFixture`. The user labels each
// dump as "normal" or "mirrored"; the resulting file goes into
// `src/__tests__/fixtures/ocr/` after anonymization (see that folder's
// README for redaction rules).

type FixtureCategory = "normal" | "mirrored";

function tokenize(text: string): string[] {
  return text
    .split(/\s+/)
    .map((t) => t.trim())
    .filter(Boolean);
}

function splitLines(text: string): string[] {
  return text
    .split(/\n+/)
    .map((l) => l.trim())
    .filter(Boolean);
}

function buildFixture(
  image: ReceiptImage,
  source: "camera" | "gallery",
  category: FixtureCategory,
  sequenceTag: string
) {
  const text = image.ocrText ?? "";
  const platform: "ios" | "android" = Platform.OS === "ios" ? "ios" : "android";
  return {
    id: `${category}-${sequenceTag}`,
    platform,
    source,
    transform: category === "mirrored" ? "horizontalFlip" : "none",
    tokens: tokenize(text),
    lines: splitLines(text),
    confidence: image.ocrQuality?.confidence ?? null,
    notes: "TODO — redact PII per src/__tests__/fixtures/ocr/README.md before committing",
  };
}

async function dumpFixture(
  image: ReceiptImage,
  source: "camera" | "gallery",
  category: FixtureCategory
) {
  const tag = new Date()
    .toISOString()
    .replace(/[-:.TZ]/g, "")
    .slice(0, 14);
  const fixture = buildFixture(image, source, category, tag);
  try {
    await Share.share({
      title: `${fixture.id}.json`,
      message: JSON.stringify(fixture, null, 2),
    });
  } catch (e) {
    console.warn("[fixture-dump] share failed", e);
  }
}

// Human checklist gate. Auto-redaction would risk false negatives on
// region-specific name/address shapes; the gate forces the contributor to
// confirm review before the JSON leaves the app.
function confirmRedactionAndDump(
  image: ReceiptImage,
  source: "camera" | "gallery",
  category: FixtureCategory
) {
  Alert.alert(
    "PII 검토 필수",
    "공유 후 외부 편집기에서 다음 항목을 redact 한 뒤 " +
      "src/__tests__/fixtures/ocr/ 로 저장하세요:\n\n" +
      "• 점원/대표자 이름 → XXX\n" +
      "• 전화번호 → XX-XXXX-XXXX\n" +
      "• 카드번호 → ****-****-****-XXXX\n" +
      "• 사업자등록번호 → XXX-XX-XXXXX\n" +
      '• 거래번호/영수증번호 → 끝자리 "X"\n' +
      '• 주소 번지수 이하 → "…XXX"\n' +
      '• 매장 분점명 → "XXX점"\n\n' +
      "익명화 완료 후 notes 필드에 처리 내역을 기록하세요.",
    [
      { text: "취소", style: "cancel" },
      { text: "확인 — 공유", onPress: () => dumpFixture(image, source, category) },
    ]
  );
}

function promptFixtureCategory(image: ReceiptImage, source: "camera" | "gallery") {
  Alert.alert("Fixture로 저장", "이 영수증의 OCR 출력을 어느 카테고리로 dump 할까요?", [
    {
      text: "정상 영수증",
      onPress: () => confirmRedactionAndDump(image, source, "normal"),
    },
    {
      text: "좌우반전 영수증",
      onPress: () => confirmRedactionAndDump(image, source, "mirrored"),
    },
    { text: "취소", style: "cancel" },
  ]);
}

// ─── OCR quality + EXIF detail ─────────────────────────────────────────────---

function OcrQualityPart({ quality }: { quality: OcrQuality }) {
  const confidence =
    quality.confidence === undefined ? "—" : `${(quality.confidence * 100).toFixed(1)}%`;
  return (
    <Collapsible title="OCR 품질" defaultOpen>
      <MetaRow label="글자 수" value={String(quality.textLength)} />
      <MetaRow label="줄 수" value={String(quality.lineCount)} />
      <MetaRow label="신뢰도" value={confidence} />
    </Collapsible>
  );
}

function ExifPart({ exif, origin }: { exif: ReceiptExif; origin: ImageOrigin }) {
  const { dateTimeOriginal, make, model, software, orientation, gps, raw, ...rest } = exif;
  const hasRest = Object.keys(rest).length > 0;
  const emptyNote =
    origin === "camera"
      ? "스캐너가 원본 EXIF를 내보내지 않아 기기 정보만 합성해 제공합니다."
      : "추가 EXIF 필드 없음";
  return (
    <View style={{ marginVertical: S.sm }}>
      {dateTimeOriginal && <MetaRow label="촬영일시" value={dateTimeOriginal} />}
      {make && <MetaRow label="제조사" value={make} />}
      {model && <MetaRow label="기기 모델" value={model} />}
      {software && <MetaRow label="소프트웨어" value={software} />}
      {orientation !== undefined && <MetaRow label="방향 태그" value={String(orientation)} />}
      {gps && (
        <MetaRow label="GPS" value={`${gps.latitude.toFixed(5)}, ${gps.longitude.toFixed(5)}`} />
      )}
      {hasRest ? (
        <Text style={[styles.metaValue, styles.exifRawJson]}>{JSON.stringify(rest, null, 2)}</Text>
      ) : (
        !raw && <Text style={styles.exifNote}>{emptyNote}</Text>
      )}
      {raw && (
        <Collapsible title={`원본 EXIF (raw · ${Object.keys(raw).length} keys)`}>
          <Text style={[styles.metaValue, styles.exifRawJson]}>{JSON.stringify(raw, null, 2)}</Text>
        </Collapsible>
      )}
    </View>
  );
}

// ─── OCR geometry overlay ─────────────────────────────────────────────────────
//
// Draws `ocrLines` over the preview: a light bar sweeps top-to-bottom and each
// text box fades in as the bar passes it. The package only returns geometry —
// this presentation lives entirely in the consuming app (ADR-003).

const SWEEP_DURATION_MS = 1600;
// How much of the image height a box fades in over, as a fraction. Small enough
// to read as "the bar revealed it", large enough not to look like a hard cut.
const REVEAL_FRACTION = 0.04;

/**
 * `resizeMode="contain"` letterboxes the image inside its container, so line
 * boxes have to be placed against the *drawn* rect, not the container.
 */
function containFit(
  container: { width: number; height: number },
  source: { width: number; height: number }
) {
  if (source.width <= 0 || source.height <= 0) return null;
  const scale = Math.min(container.width / source.width, container.height / source.height);
  const width = source.width * scale;
  const height = source.height * scale;
  return {
    scale,
    width,
    height,
    left: (container.width - width) / 2,
    top: (container.height - height) / 2,
  };
}

function OcrLineBox({
  line,
  fit,
  sweep,
}: {
  line: OcrLine;
  fit: NonNullable<ReturnType<typeof containFit>>;
  sweep: Animated.Value;
}) {
  const topFraction = fit.height > 0 ? (line.frame.y * fit.scale) / fit.height : 0;
  return (
    <Animated.View
      pointerEvents="none"
      style={[
        styles.ocrLineBox,
        {
          left: fit.left + line.frame.x * fit.scale,
          top: fit.top + line.frame.y * fit.scale,
          width: line.frame.width * fit.scale,
          height: line.frame.height * fit.scale,
          opacity: sweep.interpolate({
            inputRange: [topFraction, topFraction + REVEAL_FRACTION],
            outputRange: [0, 1],
            extrapolate: "clamp",
          }),
        },
      ]}
    />
  );
}

function OcrGeometryPreview({ image }: { image: ReceiptImage }) {
  const [container, setContainer] = useState<{ width: number; height: number } | null>(null);
  const sweep = useRef(new Animated.Value(0)).current;
  const lines = image.ocrLines ?? [];

  useEffect(() => {
    sweep.setValue(0);
    Animated.timing(sweep, {
      toValue: 1,
      duration: SWEEP_DURATION_MS,
      useNativeDriver: true,
    }).start();
  }, [sweep, image.uri]);

  const onLayout = (e: LayoutChangeEvent) => setContainer(e.nativeEvent.layout);
  const fit = container ? containFit(container, image) : null;

  return (
    <View style={styles.imagePreview} onLayout={onLayout}>
      <Image source={{ uri: image.uri }} style={StyleSheet.absoluteFill} resizeMode="contain" />
      {fit && (
        <>
          {lines.map((line, i) => (
            <OcrLineBox key={i} line={line} fit={fit} sweep={sweep} />
          ))}
          <Animated.View
            pointerEvents="none"
            style={[
              styles.ocrScanLine,
              {
                left: fit.left,
                top: fit.top,
                width: fit.width,
                opacity: sweep.interpolate({
                  inputRange: [0, 0.9, 1],
                  outputRange: [1, 1, 0],
                }),
                transform: [
                  {
                    translateY: sweep.interpolate({
                      inputRange: [0, 1],
                      outputRange: [0, fit.height],
                    }),
                  },
                ],
              },
            ]}
          />
        </>
      )}
    </View>
  );
}

// ─── Image detail card ────────────────────────────────────────────────────────

function ImageDetailCard({ image, index }: { image: ReceiptImage; index: number }) {
  const sizeKb = (image.fileSize / 1024).toFixed(1);
  const lineCount = image.ocrLines?.length ?? 0;
  return (
    <Card style={{ marginBottom: S.md }}>
      <Text style={styles.imageCardTitle}>페이지 {index + 1}</Text>

      {lineCount > 0 ? (
        <OcrGeometryPreview image={image} />
      ) : (
        <Image source={{ uri: image.uri }} style={styles.imagePreview} resizeMode="contain" />
      )}

      <View style={styles.originRow}>
        <Text style={styles.originRowLabel}>이미지 출처</Text>
        <OriginBadge origin={image.imageOrigin} />
      </View>

      <Collapsible title="파일 정보">
        <MetaRow label="파일명" value={image.fileName} />
        <MetaRow label="해상도" value={`${image.width} × ${image.height}`} />
        <MetaRow label="크기" value={`${sizeKb} KB`} />
        <MetaRow label="형식" value={image.mimeType} />
      </Collapsible>

      {image.ocrQuality && <OcrQualityPart quality={image.ocrQuality} />}

      {image.ocrText !== undefined && (
        <Collapsible title="OCR 텍스트" defaultOpen>
          <ScrollView style={styles.ocrScroll} nestedScrollEnabled>
            <Text style={styles.ocrText}>{image.ocrText.trim() || "(인식된 텍스트 없음)"}</Text>
          </ScrollView>
        </Collapsible>
      )}

      {lineCount > 0 && (
        <Collapsible title={`OCR 영역 좌표 (${lineCount}줄)`}>
          <ScrollView style={styles.ocrScroll} nestedScrollEnabled>
            {image.ocrLines?.map((line, i) => (
              <Text key={i} style={styles.ocrText}>
                {`[${Math.round(line.frame.x)}, ${Math.round(line.frame.y)}, ` +
                  `${Math.round(line.frame.width)}×${Math.round(line.frame.height)}]` +
                  `${line.confidence !== undefined ? ` ${line.confidence.toFixed(2)}` : ""} ${line.text}`}
              </Text>
            ))}
          </ScrollView>
        </Collapsible>
      )}

      {image.exif && <ExifPart exif={image.exif} origin={image.imageOrigin} />}
    </Card>
  );
}

// ─── Scan page ────────────────────────────────────────────────────────────────

type ScanPageProps = {
  opts: ScanOptionsState;
  set: <K extends keyof ScanOptionsState>(key: K, value: ScanOptionsState[K]) => void;
  ocrLanguageInput: string;
  setOcrLanguageInput: (value: string) => void;
  ocrCapabilities: OcrCapabilities | null;
  capabilityError: { code: string; message: string } | null;
  scanning: boolean;
  error: { code: string; message: string } | null;
  lastResult: ScanReceiptResult | null;
  onScan: () => void;
};

function errorDetails(error: unknown): { code: string; message: string } {
  if (error instanceof Error) {
    const code = "code" in error && typeof error.code === "string" ? error.code : "UNKNOWN";
    return { code, message: error.message };
  }
  return { code: "UNKNOWN", message: String(error) };
}

function ScanPage({
  opts,
  set,
  ocrLanguageInput,
  setOcrLanguageInput,
  ocrCapabilities,
  capabilityError,
  scanning,
  error,
  lastResult,
  onScan,
}: ScanPageProps) {
  const isIOS = Platform.OS === "ios";
  return (
    <SafeAreaView style={styles.safeArea}>
      <StatusBar barStyle="dark-content" backgroundColor={C.bg} />
      <ScrollView contentContainerStyle={styles.scrollContent} showsVerticalScrollIndicator={false}>
        {/* 헤더 */}
        <View style={{ marginBottom: S["3xl"] }}>
          <Text style={styles.headerTitle}>Receipt Scanner</Text>
          <Text style={styles.headerSubtitle}>
            New Architecture · Multilingual OCR · Interactive Crop
          </Text>
        </View>

        {/* 섹션 1 — 스캔 방식 선택 */}
        <View style={styles.section}>
          <SectionHeader
            title="스캔 방식"
            description="카메라로 직접 촬영하거나 갤러리에서 영수증 사진을 가져오세요"
          />
          <View style={styles.sourceRow}>
            <Pressable
              style={[styles.sourceCard, opts.source === "camera" && styles.sourceCardSelected]}
              onPress={() => set("source", "camera")}
            >
              <Text style={styles.sourceIcon}>📷</Text>
              <Text
                style={[styles.sourceLabel, opts.source === "camera" && styles.sourceLabelSelected]}
              >
                카메라
              </Text>
              <Text style={styles.sourceSublabel}>문서 스캐너</Text>
            </Pressable>

            <Pressable
              style={[styles.sourceCard, opts.source === "gallery" && styles.sourceCardSelected]}
              onPress={() => set("source", "gallery")}
            >
              <Text style={styles.sourceIcon}>🖼️</Text>
              <Text
                style={[
                  styles.sourceLabel,
                  opts.source === "gallery" && styles.sourceLabelSelected,
                ]}
              >
                갤러리
              </Text>
              <Text style={styles.sourceSublabel}>사진 가져오기 + 원근 보정</Text>
            </Pressable>
          </View>

          {opts.source === "gallery" && (
            <View style={styles.infoBadge}>
              <Text style={styles.infoText}>
                {isIOS
                  ? "📐 iOS: VNDetectRectangles가 문서 모서리를 자동 감지하고 드래그 핸들로 원근 보정이 가능합니다"
                  : "📷 Android: 갤러리에서 영수증 사진을 직접 선택합니다. 문서 모서리를 드래그 핸들로 보정해주세요."}
              </Text>
            </View>
          )}
        </View>

        {/* 섹션 2 — 기본 옵션 */}
        <View style={styles.section}>
          <SectionHeader title="스캔 옵션" description="처리할 데이터와 품질을 설정하세요" />
          <Card>
            <ToggleRow
              label="OCR — 온디바이스 텍스트 인식"
              value={opts.ocr}
              onToggle={() => set("ocr", !opts.ocr)}
            />
            <View style={styles.divider} />
            <ToggleRow
              label="EXIF 메타데이터 포함"
              value={opts.includeExif}
              onToggle={() => set("includeExif", !opts.includeExif)}
            />
            <View style={styles.divider} />
            <StepperRow
              label="최대 페이지 수"
              value={opts.maxPages}
              onChange={(v) => set("maxPages", v)}
              min={1}
              max={10}
            />
            <View style={styles.divider} />
            <ChipRow
              label="JPEG 품질 (quality)"
              values={[0.5, 0.7, 0.82, 0.95, 1.0]}
              value={opts.quality}
              onChange={(v) => set("quality", v)}
              format={(v) => (v === 0.82 ? "0.82·기본" : v.toFixed(2))}
            />
          </Card>
        </View>

        {/* 섹션 3 — OCR 정밀도 */}
        <View style={styles.section}>
          <SectionHeader
            title="OCR 정밀도"
            description="회전 보정 · 작은 글자 인식 · 인식 결과 게이트"
          />
          <Card>
            <View style={styles.inputRow}>
              <Text style={styles.toggleLabel}>OCR 언어 힌트 (ocrLanguages)</Text>
              <TextInput
                style={styles.textInput}
                accessibilityLabel="OCR 언어 힌트 (ocrLanguages)"
                value={ocrLanguageInput}
                onChangeText={setOcrLanguageInput}
                autoCapitalize="none"
                autoCorrect={false}
                placeholder="ko-KR,en-US"
                placeholderTextColor={C.ink400}
              />
              <Text style={styles.controlHint}>
                쉼표로 구분한 BCP 47 태그를 입력 순서와 빈 항목까지 그대로 전달합니다
              </Text>
            </View>
            <View style={styles.divider} />
            <ToggleRow
              label="자동 회전 보정 (autoRotate)"
              value={opts.autoRotate}
              onToggle={() => set("autoRotate", !opts.autoRotate)}
              disabled={!opts.ocr}
              hint={opts.ocr ? undefined : "OCR이 켜져 있어야 적용됩니다"}
            />
            <View style={styles.divider} />
            <ToggleRow
              label="텍스트 영역 좌표 (ocrGeometry)"
              value={opts.ocrGeometry}
              onToggle={() => set("ocrGeometry", !opts.ocrGeometry)}
              disabled={!opts.ocr}
              hint={
                opts.ocr
                  ? "결과 화면에서 인식된 줄 위치를 이미지 위에 겹쳐 보여줍니다"
                  : "OCR이 켜져 있어야 적용됩니다"
              }
            />
            <View style={styles.divider} />
            <ToggleRow
              label="긴 영수증 OCR 병합 (mergeOcrPages)"
              value={opts.mergeOcrPages}
              onToggle={() => set("mergeOcrPages", !opts.mergeOcrPages)}
              disabled={!opts.ocr || opts.maxPages < 2}
              hint={
                !opts.ocr
                  ? "OCR이 켜져 있어야 적용됩니다"
                  : opts.maxPages < 2
                    ? "페이지 수를 2장 이상으로 올려야 이어붙일 경계가 생깁니다"
                    : "겹치게 나눠 찍은 페이지들의 OCR 텍스트를 한 줄기로 이어붙입니다"
              }
            />
            <View style={styles.divider} />
            <ChipRow
              label="최소 텍스트 높이 (minimumTextHeight)"
              values={[0, 0.02, 0.05, 0.1]}
              value={opts.minimumTextHeight}
              onChange={(v) => set("minimumTextHeight", v)}
              format={(v) => (v === 0 ? "기본(1/32)" : v.toFixed(2))}
              badge="iOS"
              disabled={!isIOS || !opts.ocr}
              hint={
                !isIOS
                  ? "Android(ML Kit)에는 대응 항목이 없어 무시됩니다"
                  : !opts.ocr
                    ? "OCR이 켜져 있어야 적용됩니다"
                    : "값을 낮추면 작은 항목 텍스트 인식률이 올라갑니다 (노이즈 증가)"
              }
            />
            <View style={styles.divider} />
            <ToggleRow
              label="OCR 최소 기준 (ocrFloor)"
              value={opts.ocrFloorEnabled}
              onToggle={() => set("ocrFloorEnabled", !opts.ocrFloorEnabled)}
              disabled={!opts.ocr}
              hint={
                opts.ocr
                  ? "기준 미달 이미지는 rejectedImages로 분류됩니다"
                  : "OCR이 켜져 있어야 적용됩니다"
              }
            />
            {opts.ocr && opts.ocrFloorEnabled && (
              <View style={styles.nested}>
                <StepperRow
                  label="minTextLength"
                  value={opts.floorMinTextLength}
                  onChange={(v) => set("floorMinTextLength", v)}
                  min={0}
                  max={200}
                />
                <StepperRow
                  label="minLines"
                  value={opts.floorMinLines}
                  onChange={(v) => set("floorMinLines", v)}
                  min={0}
                  max={50}
                />
                <ChipRow
                  label="minConfidence"
                  values={[0, 0.3, 0.5, 0.7]}
                  value={opts.floorMinConfidence}
                  onChange={(v) => set("floorMinConfidence", v)}
                  format={(v) => (v === 0 ? "0·off" : v.toFixed(1))}
                />
              </View>
            )}
          </Card>
        </View>

        {/* 섹션 4 — EXIF & 크롭 */}
        <View style={styles.section}>
          <SectionHeader title="EXIF & 크롭" description="메타데이터 범위와 크롭 동작" />
          <Card>
            <ToggleRow
              label="GPS EXIF 포함 (includeGpsExif)"
              value={opts.includeGpsExif}
              onToggle={() => set("includeGpsExif", !opts.includeGpsExif)}
              disabled={!opts.includeExif}
              hint={
                opts.includeExif
                  ? "원본 이미지에 박힌 GPS만 복사 — 위치 권한 요청 없음"
                  : "EXIF 포함이 켜져 있어야 적용됩니다"
              }
            />
            <View style={styles.divider} />
            <ToggleRow
              label="원본 raw EXIF 포함 (includeRawExif)"
              value={opts.includeRawExif}
              onToggle={() => set("includeRawExif", !opts.includeRawExif)}
              disabled={!opts.includeExif}
              hint={
                opts.includeExif
                  ? "화이트리스트 밖 태그까지 exif.raw로 노출 (IPC 페이로드 증가)"
                  : "EXIF 포함이 켜져 있어야 적용됩니다"
              }
            />
            <View style={styles.divider} />
            <ToggleRow
              label="크롭 자동 확정 (cropAutoConfirm)"
              value={opts.cropAutoConfirm}
              onToggle={() => set("cropAutoConfirm", !opts.cropAutoConfirm)}
              badge="iOS 갤러리"
              disabled={opts.source !== "gallery"}
              hint={
                opts.source === "gallery"
                  ? "문서 감지 신뢰도가 높으면 크롭 편집기를 건너뜁니다 (iOS 전용)"
                  : "source가 gallery일 때만 적용됩니다"
              }
            />
          </Card>
        </View>

        {/* 스캔 버튼 */}
        <View style={styles.section}>
          <Pressable
            style={[styles.scanBtn, scanning && styles.scanBtnDisabled]}
            onPress={onScan}
            disabled={scanning}
          >
            {scanning ? (
              <ActivityIndicator color={C.primaryFg} />
            ) : (
              <Text style={styles.scanBtnText}>
                {opts.source === "camera" ? "📷 카메라로 스캔" : "🖼️ 갤러리에서 가져오기"}
              </Text>
            )}
          </Pressable>
        </View>

        <View style={styles.section}>
          <SectionHeader title="OCR 진단" description="현재 플랫폼의 OCR 언어 및 모델 가용성" />
          <Card>
            <Text style={styles.controlHint}>getOcrCapabilities()</Text>
            <Text style={[styles.ocrText, styles.diagnosticJson]}>
              {ocrCapabilities
                ? JSON.stringify(ocrCapabilities, null, 2)
                : capabilityError
                  ? `${capabilityError.code}: ${capabilityError.message}`
                  : "OCR 기능을 조회하는 중..."}
            </Text>
          </Card>
        </View>

        {/* 오류 표시 */}
        {error && (
          <View style={[styles.section, styles.errorCard]}>
            <Text style={styles.errorTitle}>스캔 오류</Text>
            <Text style={styles.errorCode}>{error.code}</Text>
            <Text style={styles.errorMsg}>{error.message}</Text>
          </View>
        )}

        {/* 마지막 결과 요약 */}
        {lastResult && !error && (
          <View style={styles.section}>
            <View
              style={[
                styles.statusBadge,
                lastResult.status === "success"
                  ? styles.statusBadgeSuccess
                  : styles.statusBadgeWarn,
              ]}
            >
              <Text
                style={[
                  styles.statusBadgeText,
                  lastResult.status === "success"
                    ? styles.statusBadgeTextSuccess
                    : styles.statusBadgeTextWarn,
                ]}
              >
                {statusSummary(lastResult)}
              </Text>
            </View>
          </View>
        )}
      </ScrollView>
    </SafeAreaView>
  );
}

// ─── Result page ──────────────────────────────────────────────────────────────

function statusSummary(result: ScanReceiptResult): string {
  switch (result.status) {
    case "success":
      return `✅ 스캔 성공 — ${result.images.length}페이지`;
    case "rejected":
      return `⚠️ OCR 기준 미달 — ${result.rejectedImages.length}페이지 거부됨`;
    case "cancelled":
    default:
      return "⚪ 스캔 취소됨";
  }
}

function ResultPage({
  result,
  source,
  onBack,
  onRescan,
}: {
  result: ScanReceiptResult;
  source: "camera" | "gallery";
  onBack: () => void;
  onRescan: () => void;
}) {
  const hasImages = result.images.length > 0;
  const hasOcr = hasImages && result.images.some((img) => img.ocrText);
  const isWarn = result.status !== "success";
  // Hoisted so the merged block renders even when every page was floor-rejected.
  const merged = result.mergedOcr;

  return (
    <SafeAreaView style={styles.safeArea}>
      <StatusBar barStyle="dark-content" backgroundColor={C.surface} />

      {/* 내비게이션 바 */}
      <View style={styles.navBar}>
        <Pressable style={styles.backBtn} onPress={onBack}>
          <Text style={styles.backBtnText}>← 뒤로</Text>
        </Pressable>
        <Text style={styles.navTitle}>스캔 결과</Text>
        <View style={styles.navSpacer} />
      </View>

      <ScrollView contentContainerStyle={styles.scrollContent} showsVerticalScrollIndicator={false}>
        {/* 상태 배지 */}
        <View style={styles.section}>
          <View
            style={[
              styles.statusBadge,
              isWarn ? styles.statusBadgeWarn : styles.statusBadgeSuccess,
            ]}
          >
            <Text
              style={[
                styles.statusBadgeText,
                isWarn ? styles.statusBadgeTextWarn : styles.statusBadgeTextSuccess,
              ]}
            >
              {statusSummary(result)}
            </Text>
          </View>
        </View>

        {hasImages && (
          <>
            {/* 섹션 A — 영수증 이미지 */}
            <View style={styles.section}>
              <SectionHeader
                title="영수증 이미지"
                description={
                  source === "gallery"
                    ? "갤러리에서 가져온 후 원근 보정된 영수증 이미지입니다"
                    : "카메라 문서 스캐너로 촬영된 영수증 이미지입니다"
                }
              />
              {result.images.map((img, i) => (
                <ImageDetailCard key={i} image={img} index={i} />
              ))}
            </View>

            {/* 섹션 B — 페이지별 fixture dump */}
            {hasOcr && (
              <View style={styles.section}>
                <SectionHeader
                  title="Fixture 입력"
                  description="OCR 출력을 회귀 테스트 fixture로 dump 합니다"
                />
                {result.images.map((img, i) =>
                  img.ocrText ? (
                    <Card key={`detail-${i}`} style={{ marginVertical: S.md }}>
                      <Text style={styles.imageCardTitle}>페이지 {i + 1}</Text>
                      <Pressable
                        style={styles.fixtureBtn}
                        onPress={() => promptFixtureCategory(img, source)}
                      >
                        <Text style={styles.fixtureBtnText}>📋 Fixture로 저장</Text>
                      </Pressable>
                    </Card>
                  ) : null
                )}
              </View>
            )}
          </>
        )}

        {/* 섹션 B-2 — 페이지 간 OCR 병합 결과 */}
        {merged && (
          <View style={styles.section}>
            <SectionHeader
              title="병합된 OCR (mergedOcr)"
              description={
                merged.isComplete
                  ? "인접한 모든 경계에서 겹침이 확인되었습니다"
                  : "확인하지 못한 경계가 있습니다 — 텍스트는 하나도 버리지 않습니다"
              }
            />
            <Card style={{ marginVertical: S.md }}>
              <Text style={styles.imageCardTitle}>
                {merged.isComplete ? "✅ 완결" : "⚠️ 미완결"} · {merged.pageUris.length}페이지
              </Text>
              {merged.unmatchedBoundaryIndexes.length > 0 && (
                <Text style={styles.ocrText}>
                  겹침 미확인 경계:{" "}
                  {merged.unmatchedBoundaryIndexes.map((i) => `${i}↔${i + 1}`).join(", ")}
                </Text>
              )}
              {merged.rejectedPageIndexes.length > 0 && (
                <Text style={styles.ocrText}>
                  기준 미달 페이지: {merged.rejectedPageIndexes.join(", ")}
                </Text>
              )}
              <Text style={styles.ocrText}>{merged.text.trim() || "(인식된 텍스트 없음)"}</Text>
            </Card>
          </View>
        )}

        {/* 섹션 C — OCR 기준 미달로 거부된 이미지 */}
        {result.rejectedImages.length > 0 && (
          <View style={styles.section}>
            <SectionHeader
              title="거부된 이미지 (rejectedImages)"
              description="ocrFloor 기준을 충족하지 못해 images에서 제외된 캡처입니다"
            />
            {result.rejectedImages.map((img, i) => (
              <ImageDetailCard key={`rejected-${i}`} image={img} index={i} />
            ))}
          </View>
        )}

        {/* 다시 스캔 버튼 */}
        <View style={styles.section}>
          <Pressable style={styles.rescanBtn} onPress={onRescan}>
            <Text style={styles.rescanBtnText}>다시 스캔하기</Text>
          </Pressable>
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

// ─── Root ─────────────────────────────────────────────────────────────────────

export default function App() {
  const [page, setPage] = useState<"scan" | "result">("scan");
  const [scanning, setScanning] = useState(false);
  const [result, setResult] = useState<ScanReceiptResult | null>(null);
  const [error, setError] = useState<{
    code: string;
    message: string;
  } | null>(null);
  const [ocrLanguageInput, setOcrLanguageInput] = useState("ko-KR,en-US");
  const [ocrCapabilities, setOcrCapabilities] = useState<OcrCapabilities | null>(null);
  const [capabilityError, setCapabilityError] = useState<{
    code: string;
    message: string;
  } | null>(null);

  const [opts, setOpts] = useState<ScanOptionsState>(INITIAL_OPTIONS);
  const set = <K extends keyof ScanOptionsState>(key: K, value: ScanOptionsState[K]) =>
    setOpts((prev) => ({ ...prev, [key]: value }));

  useEffect(() => {
    let mounted = true;

    getOcrCapabilities()
      .then((capabilities) => {
        if (mounted) {
          setOcrCapabilities(capabilities);
        }
      })
      .catch((capabilitiesError: unknown) => {
        if (mounted) {
          setCapabilityError(errorDetails(capabilitiesError));
        }
      });

    return () => {
      mounted = false;
    };
  }, []);

  async function handleScan() {
    setError(null);
    setScanning(true);
    try {
      const ocrLanguages = ocrLanguageInput.split(",").map((tag) => tag.trim());
      const scanResult = await scan({ ...buildOptions(opts), ocrLanguages });
      console.debug("[🧾] Scanned Result:", scanResult);
      setResult(scanResult);
      // Navigate to the result screen for both success and rejected so the
      // rejectedImages (ocrFloor effect) are visible; stay on scan for cancel.
      if (scanResult.status !== "cancelled") {
        setPage("result");
      }
    } catch (scanError: unknown) {
      setError(errorDetails(scanError));
    } finally {
      setScanning(false);
    }
  }

  if (page === "result" && result) {
    return (
      <ResultPage
        result={result}
        source={opts.source}
        onBack={() => setPage("scan")}
        onRescan={() => {
          setResult(null);
          setError(null);
          setPage("scan");
        }}
      />
    );
  }

  return (
    <ScanPage
      opts={opts}
      set={set}
      ocrLanguageInput={ocrLanguageInput}
      setOcrLanguageInput={setOcrLanguageInput}
      ocrCapabilities={ocrCapabilities}
      capabilityError={capabilityError}
      scanning={scanning}
      error={error}
      lastResult={result}
      onScan={handleScan}
    />
  );
}

// ─── Styles ───────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  safeArea: {
    flex: 1,
    backgroundColor: C.bg,
  },
  scrollContent: {
    padding: S["2xl"],
    paddingTop: S["2xl"] + ANDROID_STATUS_BAR_INSET,
    paddingBottom: S["3xl"],
  },

  // Header
  headerTitle: {
    fontSize: 28,
    fontWeight: "700",
    color: C.ink900,
    letterSpacing: -0.5,
  },
  headerSubtitle: {
    fontSize: 13,
    color: C.ink400,
    marginTop: 4,
    letterSpacing: 0.2,
  },

  // Section
  section: {
    marginBottom: S["2xl"],
  },
  sectionTitle: {
    fontSize: 16,
    fontWeight: "600",
    color: C.ink900,
  },
  sectionDesc: {
    fontSize: 13,
    color: C.ink600,
    marginTop: 2,
    lineHeight: 18,
  },

  // Card
  card: {
    backgroundColor: C.surface,
    borderRadius: R.lg,
    padding: S.lg,
    borderWidth: 1,
    borderColor: C.border,
    shadowColor: "#000",
    shadowOffset: { width: 0, height: 1 },
    shadowOpacity: 0.04,
    shadowRadius: 4,
    elevation: 1,
  },

  // Source picker
  sourceRow: {
    flexDirection: "row",
    gap: S.sm,
  },
  sourceCard: {
    flex: 1,
    backgroundColor: C.surface,
    borderRadius: R.lg,
    padding: S.lg,
    borderWidth: 2,
    borderColor: C.border,
    alignItems: "center",
    gap: 4,
  },
  sourceCardSelected: {
    borderColor: C.primary,
    backgroundColor: C.primaryMuted,
  },
  sourceIcon: {
    fontSize: 28,
    marginBottom: 4,
  },
  sourceLabel: {
    fontSize: 15,
    fontWeight: "600",
    color: C.ink600,
  },
  sourceLabelSelected: {
    color: C.primary,
  },
  sourceSublabel: {
    fontSize: 11,
    color: C.ink400,
    textAlign: "center",
  },
  infoBadge: {
    marginTop: S.sm,
    backgroundColor: C.primaryMuted,
    borderRadius: R.md,
    padding: S.md,
  },
  infoText: {
    fontSize: 12,
    color: C.primary,
    lineHeight: 17,
  },

  // Control label column (toggle / chip shared)
  controlLabelCol: {
    flex: 1,
    paddingRight: S.md,
  },
  controlLabelRow: {
    flexDirection: "row",
    alignItems: "center",
    gap: S.sm,
    flexWrap: "wrap",
  },
  controlHint: {
    fontSize: 11,
    color: C.ink400,
    marginTop: 3,
    lineHeight: 15,
  },
  controlDisabled: {
    color: C.ink400,
  },
  inputRow: {
    paddingVertical: S.sm,
  },
  textInput: {
    borderWidth: 1,
    borderColor: C.border,
    borderRadius: R.md,
    backgroundColor: C.surfaceAlt,
    color: C.ink900,
    fontSize: 14,
    marginTop: S.sm,
    paddingHorizontal: S.md,
    paddingVertical: S.sm,
  },

  // Toggle
  toggleRow: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    paddingVertical: S.sm,
  },
  toggleLabel: {
    fontSize: 14,
    color: C.ink900,
  },
  toggleTrack: {
    width: 44,
    height: 24,
    borderRadius: R.full,
    backgroundColor: C.border,
    justifyContent: "center",
    paddingHorizontal: 2,
  },
  toggleTrackOn: {
    backgroundColor: C.primary,
  },
  toggleThumb: {
    width: 20,
    height: 20,
    borderRadius: R.full,
    backgroundColor: C.surface,
    shadowColor: "#000",
    shadowOffset: { width: 0, height: 1 },
    shadowOpacity: 0.2,
    shadowRadius: 2,
    elevation: 2,
  },
  toggleThumbOn: {
    alignSelf: "flex-end",
  },
  toggleDimmed: {
    opacity: 0.4,
  },
  divider: {
    height: 1,
    backgroundColor: C.border,
    marginVertical: 2,
  },
  nested: {
    marginTop: S.sm,
    marginLeft: S.md,
    paddingLeft: S.md,
    borderLeftWidth: 2,
    borderLeftColor: C.border,
  },

  // Platform / scope badge
  platformBadge: {
    backgroundColor: C.surfaceAlt,
    borderRadius: R.sm,
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderWidth: 1,
    borderColor: C.border,
  },
  platformBadgeText: {
    fontSize: 10,
    fontWeight: "600",
    color: C.ink600,
  },

  // Chips
  chipRow: {
    paddingVertical: S.sm,
  },
  chips: {
    flexDirection: "row",
    flexWrap: "wrap",
    gap: S.sm,
    marginTop: S.sm,
  },
  chip: {
    paddingHorizontal: S.md,
    paddingVertical: 6,
    borderRadius: R.full,
    borderWidth: 1,
    borderColor: C.border,
    backgroundColor: C.surfaceAlt,
  },
  chipSelected: {
    borderColor: C.primary,
    backgroundColor: C.primaryMuted,
  },
  chipText: {
    fontSize: 13,
    color: C.ink600,
    fontWeight: "600",
  },
  chipTextSelected: {
    color: C.primary,
  },

  // Stepper
  stepperRow: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    paddingVertical: S.sm,
  },
  stepper: {
    flexDirection: "row",
    alignItems: "center",
    gap: S.sm,
  },
  stepBtn: {
    width: 32,
    height: 32,
    borderRadius: R.full,
    backgroundColor: C.primaryMuted,
    alignItems: "center",
    justifyContent: "center",
  },
  stepBtnText: {
    fontSize: 18,
    color: C.primary,
    fontWeight: "600",
    lineHeight: 22,
  },
  stepValue: {
    fontSize: 16,
    fontWeight: "600",
    color: C.ink900,
    minWidth: 24,
    textAlign: "center",
  },

  // Scan button
  scanBtn: {
    backgroundColor: C.primary,
    borderRadius: R.xl,
    padding: S.lg,
    alignItems: "center",
    justifyContent: "center",
    height: 54,
  },
  scanBtnDisabled: {
    opacity: 0.6,
  },
  scanBtnText: {
    fontSize: 16,
    fontWeight: "700",
    color: C.primaryFg,
    letterSpacing: 0.2,
  },

  // Fixture dump button
  fixtureBtn: {
    backgroundColor: C.surfaceAlt,
    borderRadius: R.md,
    paddingVertical: S.md,
    alignItems: "center",
    borderWidth: 1,
    borderColor: C.border,
  },
  fixtureBtnText: {
    fontSize: 13,
    fontWeight: "600",
    color: C.ink600,
  },

  // Rescan button
  rescanBtn: {
    borderRadius: R.xl,
    padding: S.lg,
    alignItems: "center",
    justifyContent: "center",
    height: 54,
    backgroundColor: C.surface,
    borderWidth: 1.5,
    borderColor: C.primary,
  },
  rescanBtnText: {
    fontSize: 16,
    fontWeight: "700",
    color: C.primary,
  },

  // Error
  errorCard: {
    backgroundColor: C.errorBg,
    borderRadius: R.lg,
    padding: S.lg,
    borderWidth: 1,
    borderColor: "#FECACA",
    marginBottom: 0,
  },
  errorTitle: {
    fontSize: 14,
    fontWeight: "700",
    color: C.errorFg,
    marginBottom: 4,
  },
  errorCode: {
    fontSize: 12,
    fontWeight: "600",
    color: C.errorFg,
    fontFamily: Platform.OS === "ios" ? "Menlo" : "monospace",
    marginBottom: 4,
  },
  errorMsg: {
    fontSize: 13,
    color: C.errorFg,
    lineHeight: 18,
  },

  // Status badge
  statusBadge: {
    borderRadius: R.lg,
    padding: S.md,
  },
  statusBadgeSuccess: {
    backgroundColor: C.successBg,
  },
  statusBadgeWarn: {
    backgroundColor: C.warnBg,
  },
  statusBadgeText: {
    fontSize: 14,
    fontWeight: "600",
  },
  statusBadgeTextSuccess: {
    color: C.successFg,
  },
  statusBadgeTextWarn: {
    color: C.warnFg,
  },

  // Nav bar
  navBar: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    paddingHorizontal: S.lg,
    paddingVertical: S.md,
    paddingTop: S.md + ANDROID_STATUS_BAR_INSET,
    backgroundColor: C.surface,
    borderBottomWidth: 1,
    borderBottomColor: C.border,
  },
  backBtn: {
    width: 60,
  },
  navSpacer: {
    width: 60,
  },
  backBtnText: {
    fontSize: 14,
    color: C.primary,
    fontWeight: "600",
  },
  navTitle: {
    fontSize: 16,
    fontWeight: "700",
    color: C.ink900,
  },

  // Image card
  imageCardTitle: {
    fontSize: 12,
    fontWeight: "600",
    color: C.ink400,
    marginBottom: S.sm,
    textTransform: "uppercase",
    letterSpacing: 0.8,
  },
  imagePreview: {
    width: "100%",
    height: 220,
    backgroundColor: C.surfaceAlt,
    borderRadius: R.md,
    marginBottom: S.md,
    overflow: "hidden",
  },

  // OCR geometry overlay
  ocrLineBox: {
    position: "absolute",
    borderWidth: 1,
    borderColor: C.primary,
    backgroundColor: "rgba(217, 82, 42, 0.16)",
    borderRadius: 2,
  },
  ocrScanLine: {
    position: "absolute",
    height: 2,
    backgroundColor: C.primary,
  },

  // Collapsible
  collapseHeader: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    paddingVertical: S.sm,
    borderTopWidth: 1,
    borderTopColor: C.border,
    marginTop: S.xs,
  },
  collapseTitle: {
    fontSize: 13,
    fontWeight: "600",
    color: C.ink600,
  },
  collapseChevron: {
    fontSize: 10,
    color: C.ink400,
  },
  collapseBody: {
    paddingTop: S.xs,
    paddingBottom: S.sm,
  },

  // Meta row
  metaRow: {
    flexDirection: "row",
    paddingVertical: 5,
  },
  metaLabel: {
    fontSize: 12,
    color: C.ink400,
    width: 80,
    flexShrink: 0,
  },
  metaValue: {
    fontSize: 12,
    color: C.ink900,
    flex: 1,
    fontFamily: Platform.OS === "ios" ? "Menlo" : "monospace",
  },

  // Origin badge
  originRow: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    marginBottom: S.md,
  },
  originRowLabel: {
    fontSize: 12,
    color: C.ink400,
  },
  originBadge: {
    borderRadius: R.full,
    paddingHorizontal: S.md,
    paddingVertical: 4,
  },
  originBadgeText: {
    fontSize: 12,
    fontWeight: "600",
  },

  // OCR
  ocrScroll: {
    maxHeight: 160,
  },
  ocrText: {
    fontSize: 12,
    color: C.ink600,
    lineHeight: 19,
    fontFamily: Platform.OS === "ios" ? "Menlo" : "monospace",
  },
  exifRawJson: {
    backgroundColor: C.surfaceAlt,
    borderRadius: R.md,
    padding: S.md,
  },
  diagnosticJson: {
    backgroundColor: C.surfaceAlt,
    borderRadius: R.md,
    marginTop: S.sm,
    padding: S.md,
  },
  exifNote: {
    fontSize: 12,
    color: C.ink400,
    fontStyle: "italic",
    lineHeight: 18,
  },
});
