import { TurboModuleRegistry, type TurboModule } from "react-native";

/**
 * TurboModule spec for the native `ReceiptScanner` module.
 *
 * Phase 1 deliberately uses `Object` for both options and result to keep
 * the codegen surface minimal — the typed shapes live in
 * `./types` and are enforced at the JS boundary (`scan.native.tsx`).
 *
 * Phase 2 will replace `Object` with concrete `ReadableMap`-compatible
 * shapes once the native fields are stable across both platforms.
 */
export interface Spec extends TurboModule {
  /**
   * Launches the native scan flow.
   *
   * @param options - Merged {@link import("./types").ScanReceiptOptions}
   *                  cast to `Object` for codegen compatibility.
   * @returns Promise resolving to a {@link import("./types").ScanReceiptResult}-shaped object.
   */
  scan(options: Object): Promise<Object>;
  /** Returns OCR capability without opening scanner UI or downloading models. */
  getOcrCapabilities(): Promise<Object>;
}

/**
 * The registered TurboModule. The string `"ReceiptScanner"` must stay in
 * lockstep with `ReceiptScannerPackage.kt` (Android) and `ReceiptScanner.mm`
 * (iOS) — the module name is what the native runtime resolves at lookup time.
 */
export default TurboModuleRegistry.getEnforcing<Spec>("ReceiptScanner");
