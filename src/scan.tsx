import type { ScanReceiptOptions, ScanReceiptResult } from "./types";

/**
 * Web / non-native fallback for {@link scan}. The package targets iOS and
 * Android; on any other platform Metro resolves this file (since
 * `scan.native.tsx` only loads on native targets).
 *
 * Always resolves with `status: "cancelled"` so consumer code can call the
 * API unconditionally without a platform guard. Update this entry if a
 * pure-JS scan path is ever added.
 */
export async function scan(_options?: ScanReceiptOptions): Promise<ScanReceiptResult> {
  return { status: "cancelled", images: [], rejectedImages: [] };
}
