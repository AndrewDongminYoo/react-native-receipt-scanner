import { useReducer, useRef, useState } from "react";
import {
  KeyboardAvoidingView,
  Platform,
  Pressable,
  SafeAreaView,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
} from "react-native";
import {
  canShareFixture,
  fixturePayload,
  fixtureReviewReducer,
  shareReviewedFixture,
  type FixtureReview,
} from "./fixtureReview";
import { ANDROID_STATUS_BAR_INSET, C, R, S } from "./theme";

// This screen owns a detached snapshot. Sharing never reads the scan result again.
export function FixtureReviewEditor({
  initial,
  onClose,
}: {
  initial: FixtureReview;
  onClose: () => void;
}) {
  const [draft, dispatch] = useReducer(fixtureReviewReducer, initial);
  const [sharing, setSharing] = useState(false);
  const [error, setError] = useState("");
  const busy = useRef(false);
  async function share() {
    if (busy.current || !canShareFixture(draft)) return;
    busy.current = true;
    setSharing(true);
    setError("");
    try {
      await shareReviewedFixture(draft);
    } catch {
      setError("공유하지 못했어요. 다시 시도해 주세요.");
    } finally {
      busy.current = false;
      setSharing(false);
    }
  }
  return (
    <SafeAreaView style={styles.screen}>
      <KeyboardAvoidingView
        style={styles.fill}
        behavior={Platform.OS === "ios" ? "padding" : undefined}
      >
        <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
          <Text style={styles.title}>Fixture 공유 전 검토</Text>
          <Text style={styles.body}>
            아직 외부로 공유되지 않았어요. 아래 OCR에서 이름, 전화·카드·사업자·거래·회원 번호, 상세
            주소와 분점명을 가려 주세요. 자동 익명화는 제공하지 않아요.
          </Text>
          <Text style={styles.label}>공유할 OCR</Text>
          <TextInput
            accessibilityLabel="공유할 OCR"
            style={styles.input}
            multiline
            value={draft.text}
            editable={!sharing}
            autoCorrect={false}
            autoComplete="off"
            spellCheck={false}
            onChangeText={(value) => dispatch({ type: "text", value })}
          />
          <Text style={styles.label}>익명화 처리 내역</Text>
          <Text style={styles.body}>
            어떤 항목을 가렸는지 적어 주세요. 이 내역도 공유되므로 개인정보를 넣지 마세요.
          </Text>
          <TextInput
            accessibilityLabel="익명화 처리 내역"
            style={styles.input}
            multiline
            value={draft.notes}
            editable={!sharing}
            autoCorrect={false}
            autoComplete="off"
            spellCheck={false}
            onChangeText={(value) => dispatch({ type: "notes", value })}
          />
          <Text style={styles.label}>최종 공유 JSON</Text>
          <Text selectable style={styles.preview}>
            {fixturePayload(draft)}
          </Text>
          <View style={styles.confirm}>
            <Switch
              accessibilityLabel="최종 JSON 개인정보 검토 완료"
              value={draft.reviewed}
              disabled={sharing}
              onValueChange={(value) => dispatch({ type: "reviewed", value })}
            />
            <Text style={[styles.body, styles.fill]}>최종 JSON 전체에서 개인정보를 제거했어요</Text>
          </View>
          <Text style={styles.body}>
            OCR과 처리 내역을 입력하고 검토를 완료하면 공유할 수 있어요. 내용을 바꾸면 다시 확인해
            주세요.
          </Text>
          {!!error && (
            <Text accessibilityRole="alert" style={styles.body}>
              {error}
            </Text>
          )}
          <Pressable
            accessibilityRole="button"
            disabled={sharing || !canShareFixture(draft)}
            style={[styles.button, (sharing || !canShareFixture(draft)) && styles.disabled]}
            onPress={share}
          >
            <Text style={styles.buttonText}>{sharing ? "공유 중…" : "검토한 JSON 공유"}</Text>
          </Pressable>
          <Pressable
            accessibilityRole="button"
            disabled={sharing}
            style={styles.cancel}
            onPress={onClose}
          >
            <Text style={styles.body}>취소하고 돌아가기</Text>
          </Pressable>
        </ScrollView>
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}
const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: C.bg, paddingTop: ANDROID_STATUS_BAR_INSET },
  fill: { flex: 1 },
  content: { padding: S.xl, gap: S.md },
  title: { fontSize: 22, fontWeight: "700", color: C.ink900 },
  body: { fontSize: 14, lineHeight: 22, color: C.ink600 },
  label: { fontSize: 16, fontWeight: "600", color: C.ink900 },
  input: {
    minHeight: 100,
    padding: S.md,
    borderWidth: 1,
    borderColor: C.border,
    borderRadius: R.sm,
    backgroundColor: C.surface,
    color: C.ink900,
    textAlignVertical: "top",
  },
  preview: {
    padding: S.md,
    backgroundColor: C.surface,
    color: C.ink900,
    fontFamily: Platform.OS === "ios" ? "Menlo" : "monospace",
    fontSize: 12,
  },
  confirm: { flexDirection: "row", alignItems: "center", gap: S.md },
  button: { padding: S.lg, backgroundColor: C.primary, borderRadius: R.md, alignItems: "center" },
  disabled: { opacity: 0.4 },
  buttonText: { color: C.primaryFg, fontWeight: "600", fontSize: 16 },
  cancel: { padding: S.md, alignItems: "center" },
});
