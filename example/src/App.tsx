import { useState } from "react";
import {
  ActivityIndicator,
  Image,
  Platform,
  Pressable,
  SafeAreaView,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  View,
} from "react-native";
import {
  scan,
  type ReceiptImage,
  type ScanReceiptOptions,
  type ScanReceiptResult,
} from "react-native-receipt-scanner";

// ─── Design tokens ────────────────────────────────────────────────────────────
const C = {
  bg: "#F4F3EF",
  surface: "#FFFFFF",
  surfaceAlt: "#F9F9F7",
  primary: "#D9522A",
  primaryFg: "#FFFFFF",
  primaryMuted: "#FAEEE9",
  ink900: "#1C1917",
  ink600: "#57534E",
  ink400: "#A8A29E",
  border: "#E7E5E4",
  successBg: "#ECFDF5",
  successFg: "#065F46",
  errorBg: "#FEF2F2",
  errorFg: "#991B1B",
  warnBg: "#FFFBEB",
  warnFg: "#92400E",
};

const R = { sm: 8, md: 12, lg: 16, xl: 20, full: 999 };
const S = { "xs": 4, "sm": 8, "md": 12, "lg": 16, "xl": 20, "2xl": 28, "3xl": 40 };

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

function ToggleRow({
  label,
  value,
  onToggle,
}: {
  label: string;
  value: boolean;
  onToggle: () => void;
}) {
  return (
    <Pressable style={styles.toggleRow} onPress={onToggle}>
      <Text style={styles.toggleLabel}>{label}</Text>
      <View style={[styles.toggleTrack, value && styles.toggleTrackOn]}>
        <View style={[styles.toggleThumb, value && styles.toggleThumbOn]} />
      </View>
    </Pressable>
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

// ─── Image detail card ────────────────────────────────────────────────────────

function ImageDetailCard({ image, index }: { image: ReceiptImage; index: number }) {
  const sizeKb = (image.fileSize / 1024).toFixed(1);
  return (
    <Card style={{ marginBottom: S.md }}>
      <Text style={styles.imageCardTitle}>페이지 {index + 1}</Text>

      <Image source={{ uri: image.uri }} style={styles.imagePreview} resizeMode="contain" />

      <Collapsible title="파일 정보">
        <MetaRow label="파일명" value={image.fileName} />
        <MetaRow label="해상도" value={`${image.width} × ${image.height}`} />
        <MetaRow label="크기" value={`${sizeKb} KB`} />
        <MetaRow label="형식" value={image.mimeType} />
      </Collapsible>

      {image.ocrText !== undefined && (
        <Collapsible title="OCR 텍스트" defaultOpen>
          <ScrollView style={styles.ocrScroll} nestedScrollEnabled>
            <Text style={styles.ocrText}>{image.ocrText.trim() || "(인식된 텍스트 없음)"}</Text>
          </ScrollView>
        </Collapsible>
      )}

      {image.exif && (
        <Collapsible title="EXIF 메타데이터">
          {image.exif.dateTimeOriginal && (
            <MetaRow label="촬영일시" value={image.exif.dateTimeOriginal} />
          )}
          {image.exif.make && <MetaRow label="제조사" value={image.exif.make} />}
          {image.exif.model && <MetaRow label="기기 모델" value={image.exif.model} />}
          {image.exif.orientation !== undefined && (
            <MetaRow label="방향 태그" value={String(image.exif.orientation)} />
          )}
          {image.exif.gps && (
            <MetaRow
              label="GPS"
              value={`${image.exif.gps.latitude.toFixed(5)}, ${image.exif.gps.longitude.toFixed(5)}`}
            />
          )}
        </Collapsible>
      )}
    </Card>
  );
}

// ─── Scan page ────────────────────────────────────────────────────────────────

type ScanPageProps = {
  source: "camera" | "gallery";
  setSource: (v: "camera" | "gallery") => void;
  ocrEnabled: boolean;
  setOcrEnabled: (v: boolean) => void;
  exifEnabled: boolean;
  setExifEnabled: (v: boolean) => void;
  maxPages: number;
  setMaxPages: (v: number) => void;
  scanning: boolean;
  error: { code: string; message: string } | null;
  lastResult: ScanReceiptResult | null;
  onScan: () => void;
};

function ScanPage({
  source,
  setSource,
  ocrEnabled,
  setOcrEnabled,
  exifEnabled,
  setExifEnabled,
  maxPages,
  setMaxPages,
  scanning,
  error,
  lastResult,
  onScan,
}: ScanPageProps) {
  return (
    <SafeAreaView style={styles.safeArea}>
      <StatusBar barStyle="dark-content" backgroundColor={C.bg} />
      <ScrollView contentContainerStyle={styles.scrollContent} showsVerticalScrollIndicator={false}>
        {/* 헤더 */}
        <View style={{ marginBottom: S["3xl"] }}>
          <Text style={styles.headerTitle}>Receipt Scanner</Text>
          <Text style={styles.headerSubtitle}>
            New Architecture · Korean OCR · Interactive Crop
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
              style={[styles.sourceCard, source === "camera" && styles.sourceCardSelected]}
              onPress={() => setSource("camera")}
            >
              <Text style={styles.sourceIcon}>📷</Text>
              <Text style={[styles.sourceLabel, source === "camera" && styles.sourceLabelSelected]}>
                카메라
              </Text>
              <Text style={styles.sourceSublabel}>문서 스캐너</Text>
            </Pressable>

            <Pressable
              style={[styles.sourceCard, source === "gallery" && styles.sourceCardSelected]}
              onPress={() => setSource("gallery")}
            >
              <Text style={styles.sourceIcon}>🖼️</Text>
              <Text
                style={[styles.sourceLabel, source === "gallery" && styles.sourceLabelSelected]}
              >
                갤러리
              </Text>
              <Text style={styles.sourceSublabel}>사진 가져오기 + 원근 보정</Text>
            </Pressable>
          </View>

          {source === "gallery" && (
            <View style={styles.infoBadge}>
              <Text style={styles.infoText}>
                {Platform.OS === "ios"
                  ? "📐 iOS: VNDetectRectangles가 문서 모서리를 자동 감지하고 드래그 핸들로 원근 보정이 가능합니다"
                  : "📷 Android: 카메라 화면이 먼저 열립니다. 하단 갤러리 버튼을 탭해 사진을 선택하세요"}
              </Text>
            </View>
          )}
        </View>

        {/* 섹션 2 — 스캔 옵션 */}
        <View style={styles.section}>
          <SectionHeader title="스캔 옵션" description="처리할 데이터와 품질을 설정하세요" />
          <Card>
            <ToggleRow
              label="OCR — 한국어 + 라틴 텍스트 인식"
              value={ocrEnabled}
              onToggle={() => setOcrEnabled(!ocrEnabled)}
            />
            <View style={styles.divider} />
            <ToggleRow
              label="EXIF 메타데이터 포함"
              value={exifEnabled}
              onToggle={() => setExifEnabled(!exifEnabled)}
            />
            <View style={styles.divider} />
            <View style={styles.stepperRow}>
              <Text style={styles.toggleLabel}>최대 페이지 수</Text>
              <View style={styles.stepper}>
                <Pressable
                  style={styles.stepBtn}
                  onPress={() => maxPages > 1 && setMaxPages(maxPages - 1)}
                >
                  <Text style={styles.stepBtnText}>−</Text>
                </Pressable>
                <Text style={styles.stepValue}>{maxPages}</Text>
                <Pressable
                  style={styles.stepBtn}
                  onPress={() => maxPages < 10 && setMaxPages(maxPages + 1)}
                >
                  <Text style={styles.stepBtnText}>+</Text>
                </Pressable>
              </View>
            </View>
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
                {source === "camera" ? "📷 카메라로 스캔" : "🖼️ 갤러리에서 가져오기"}
              </Text>
            )}
          </Pressable>
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
                {lastResult.status === "success"
                  ? `✅ 스캔 성공 — ${lastResult.images.length}페이지`
                  : "⚪ 스캔 취소됨"}
              </Text>
            </View>
          </View>
        )}
      </ScrollView>
    </SafeAreaView>
  );
}

// ─── Result page ──────────────────────────────────────────────────────────────

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
  const hasImages = result.status === "success" && result.images.length > 0;
  const hasOcr = hasImages && result.images.some((img) => img.ocrText);
  const hasExif = hasImages && result.images.some((img) => img.exif);

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
              result.status === "success" ? styles.statusBadgeSuccess : styles.statusBadgeWarn,
            ]}
          >
            <Text
              style={[
                styles.statusBadgeText,
                result.status === "success"
                  ? styles.statusBadgeTextSuccess
                  : styles.statusBadgeTextWarn,
              ]}
            >
              {result.status === "success"
                ? `✅ 스캔 성공 — ${result.images.length}페이지`
                : "⚪ 사용자가 스캔을 취소했습니다"}
            </Text>
          </View>
        </View>

        {hasImages && (
          <>
            {/* 섹션 A — 갤러리에서 영수증 사진 가져오기 */}
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

            {/* 섹션 B — 영수증 내용 확인하기 */}
            {hasOcr && (
              <View style={styles.section}>
                <SectionHeader
                  title="영수증 내용 확인"
                  description="온디바이스 OCR로 추출한 텍스트입니다 (한국어 + 라틴 문자, 네트워크 불필요)"
                />
                {result.images.map((img, i) =>
                  img.ocrText ? (
                    <Card key={i} style={{ marginBottom: S.md }}>
                      <Text style={styles.imageCardTitle}>페이지 {i + 1} — OCR 전문</Text>
                      <ScrollView style={styles.ocrFullScroll} nestedScrollEnabled>
                        <Text style={styles.ocrFullText}>{img.ocrText.trim()}</Text>
                      </ScrollView>
                    </Card>
                  ) : null
                )}
              </View>
            )}

            {/* 섹션 C — EXIF 메타데이터 */}
            {hasExif && (
              <View style={styles.section}>
                <SectionHeader
                  title="EXIF 메타데이터"
                  description="원본 이미지에서 추출한 카메라 및 기기 정보입니다"
                />
                {result.images.map((img, i) =>
                  img.exif ? (
                    <Card key={i} style={{ marginBottom: S.md }}>
                      <Text style={styles.imageCardTitle}>페이지 {i + 1}</Text>
                      {img.exif.dateTimeOriginal && (
                        <MetaRow label="촬영일시" value={img.exif.dateTimeOriginal} />
                      )}
                      {img.exif.make && <MetaRow label="제조사" value={img.exif.make} />}
                      {img.exif.model && <MetaRow label="기기 모델" value={img.exif.model} />}
                      {img.exif.orientation !== undefined && (
                        <MetaRow label="방향 태그" value={String(img.exif.orientation)} />
                      )}
                      {img.exif.gps && (
                        <MetaRow
                          label="GPS"
                          value={`${img.exif.gps.latitude.toFixed(5)}, ${img.exif.gps.longitude.toFixed(5)}`}
                        />
                      )}
                    </Card>
                  ) : null
                )}
              </View>
            )}
          </>
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

  const [source, setSource] = useState<"camera" | "gallery">("camera");
  const [ocrEnabled, setOcrEnabled] = useState(true);
  const [exifEnabled, setExifEnabled] = useState(true);
  const [maxPages, setMaxPages] = useState(1);

  async function handleScan() {
    setError(null);
    setScanning(true);
    try {
      const options: ScanReceiptOptions = {
        source,
        ocr: ocrEnabled,
        includeExif: exifEnabled,
        maxPages,
      };
      const scanResult = await scan(options);
      setResult(scanResult);
      if (scanResult.status === "success") {
        setPage("result");
      }
    } catch (e: unknown) {
      const err = e as { code?: string; message?: string };
      setError({
        code: err?.code ?? "UNKNOWN",
        message: err?.message ?? String(e),
      });
    } finally {
      setScanning(false);
    }
  }

  if (page === "result" && result) {
    return (
      <ResultPage
        result={result}
        source={source}
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
      source={source}
      setSource={setSource}
      ocrEnabled={ocrEnabled}
      setOcrEnabled={setOcrEnabled}
      exifEnabled={exifEnabled}
      setExifEnabled={setExifEnabled}
      maxPages={maxPages}
      setMaxPages={setMaxPages}
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
    flex: 1,
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
  divider: {
    height: 1,
    backgroundColor: C.border,
    marginVertical: 2,
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
  ocrFullScroll: {
    maxHeight: 280,
  },
  ocrFullText: {
    fontSize: 14,
    color: C.ink900,
    lineHeight: 24,
  },
});
