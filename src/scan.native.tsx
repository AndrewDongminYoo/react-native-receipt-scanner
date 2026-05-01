import NativeReceiptScanner from "./NativeReceiptScanner";
import { DEFAULT_SCAN_OPTIONS } from "./types";
import type { ScanReceiptOptions, ScanReceiptResult } from "./types";

export async function scan(options?: ScanReceiptOptions): Promise<ScanReceiptResult> {
  const merged = { ...DEFAULT_SCAN_OPTIONS, ...options };
  return NativeReceiptScanner.scan(merged) as Promise<ScanReceiptResult>;
}
