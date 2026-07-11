import { Platform, StatusBar } from "react-native";

// ─── Design tokens ────────────────────────────────────────────────────────────
// Shared design tokens for the example app screens.
export const C = {
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

export const R = { sm: 8, md: 12, lg: 16, xl: 20, full: 999 };
export const S = { "xs": 4, "sm": 8, "md": 12, "lg": 16, "xl": 20, "2xl": 28, "3xl": 40 };

// RN 0.85 enables Android edge-to-edge by default; the system status bar is a
// translucent overlay over our content. The legacy RN `SafeAreaView` only insets
// for iOS, so on Android we add this manually wherever content meets the top.
export const ANDROID_STATUS_BAR_INSET =
  Platform.OS === "android" ? (StatusBar.currentHeight ?? 0) : 0;
