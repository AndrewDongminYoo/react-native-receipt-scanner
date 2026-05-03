import NativeReceiptScanner from "./NativeReceiptScanner";
import { DEFAULT_SCAN_OPTIONS } from "./types";
import type { ScanReceiptOptions, ScanReceiptResult } from "./types";

export async function scan(options?: ScanReceiptOptions): Promise<ScanReceiptResult> {
  const merged = { ...DEFAULT_SCAN_OPTIONS, ...options };
  // Type assertion is intentional: the TurboModule Spec uses `Object` for Phase 1.
  // Phase 2 will tighten this once the native shape is stabilized.
  return NativeReceiptScanner.scan(merged) as Promise<ScanReceiptResult>;
}
