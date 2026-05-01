import type { ScanReceiptOptions, ScanReceiptResult } from "./types";

export async function scan(_options?: ScanReceiptOptions): Promise<ScanReceiptResult> {
  return { status: "cancelled", images: [] };
}
