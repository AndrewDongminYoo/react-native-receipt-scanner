import { describe, it, expect, beforeEach, jest } from "@jest/globals";
import { mergeOcrPages } from "../mergeOcrPages";
import type { ReceiptImage } from "../types";
import { scan } from "../scan.native";
import NativeReceiptScanner from "../NativeReceiptScanner";

jest.mock("../NativeReceiptScanner", () => ({
  __esModule: true,
  default: {
    scan: jest.fn(),
  },
}));

const mockNative = NativeReceiptScanner as jest.Mocked<typeof NativeReceiptScanner>;

/** Builds a page whose only interesting property is its OCR text. */
const page = (index: number, ocrText?: string): ReceiptImage => ({
  uri: `file:///tmp/page-${index}.jpg`,
  width: 1200,
  height: 2640,
  fileName: `page-${index}.jpg`,
  mimeType: "image/jpeg",
  fileSize: 100_000,
  imageOrigin: "camera",
  ...(ocrText === undefined ? {} : { ocrText }),
});

describe("mergeOcrPages", () => {
  it("removes an exact two-line overlap exactly once", () => {
    const merged = mergeOcrPages([
      page(0, "GS25 강남점\n2026-01-15 14:30\n삼각김밥 1,500원\n바나나우유 1,800원"),
      page(1, "삼각김밥 1,500원\n바나나우유 1,800원\n생수 900원\n합계 4,200원"),
    ]);

    expect(merged.text).toBe(
      [
        "GS25 강남점",
        "2026-01-15 14:30",
        "삼각김밥 1,500원",
        "바나나우유 1,800원",
        "생수 900원",
        "합계 4,200원",
      ].join("\n")
    );
    expect(merged.isComplete).toBe(true);
    expect(merged.unmatchedBoundaryIndexes).toEqual([]);
    expect(merged.rejectedPageIndexes).toEqual([]);
  });

  it("removes a fuzzy Korean-plus-Latin overlap once", () => {
    // The same two lines re-recognized with plausible OCR noise — the letter O
    // for two zeros — so the seam is proven by similarity, not string equality.
    const merged = mergeOcrPages([
      page(0, "영수증 상단\nAmericano HOT 4,500원\n카페라떼 ICE 5,000원"),
      page(1, "Americano HOT 4,5OO원\n카페라떼 ICE 5,000원\n합계 9,500원"),
    ]);

    expect(merged.text).toBe(
      ["영수증 상단", "Americano HOT 4,500원", "카페라떼 ICE 5,000원", "합계 9,500원"].join("\n")
    );
    expect(merged.isComplete).toBe(true);
  });

  it("keeps both pages and records the boundary when similarity is below threshold", () => {
    const merged = mergeOcrPages([
      page(0, "첫 번째 페이지 내용입니다\n두 번째 줄입니다"),
      page(1, "완전히 다른 문장이 여기 있다\n또 다른 줄이 이어진다"),
    ]);

    expect(merged.text).toBe(
      [
        "첫 번째 페이지 내용입니다",
        "두 번째 줄입니다",
        "완전히 다른 문장이 여기 있다",
        "또 다른 줄이 이어진다",
      ].join("\n")
    );
    expect(merged.unmatchedBoundaryIndexes).toEqual([0]);
    expect(merged.isComplete).toBe(false);
  });

  it("holds a single-line candidate to the stricter 24-character threshold", () => {
    // 17 characters: clears the 12-char multi-line floor, misses the 24-char
    // single-line one, so a lone identical line must not prove a seam.
    const shortLine = "합계 12,345,678원";
    expect(shortLine.length).toBeGreaterThanOrEqual(12);
    expect(shortLine.length).toBeLessThan(24);

    const merged = mergeOcrPages([
      page(0, `상단 문구\n${shortLine}`),
      page(1, `${shortLine}\n하단 문구`),
    ]);

    expect(merged.unmatchedBoundaryIndexes).toEqual([0]);
    expect(merged.text.split("\n").filter((line) => line === shortLine)).toHaveLength(2);
  });

  it("accepts a single-line candidate once it is long enough", () => {
    const longLine = "서울특별시 강남구 테헤란로 152 강남파이낸스센터";
    expect(longLine.length).toBeGreaterThanOrEqual(24);

    const merged = mergeOcrPages([
      page(0, `가맹점 주소\n${longLine}`),
      page(1, `${longLine}\n대표 홍길동`),
    ]);

    expect(merged.text).toBe(["가맹점 주소", longLine, "대표 홍길동"].join("\n"));
    expect(merged.isComplete).toBe(true);
  });

  it("preserves repeated lines that are away from the seam", () => {
    // "합계 4,200원" appears on both pages but never in the compared windows,
    // so a seam match must not remove it globally.
    const merged = mergeOcrPages([
      page(0, "합계 4,200원\n중간 구분선 표시\n영수증 계속 이어짐 표시"),
      page(1, "중간 구분선 표시\n영수증 계속 이어짐 표시\n합계 4,200원"),
    ]);

    expect(merged.text.split("\n").filter((line) => line === "합계 4,200원")).toHaveLength(2);
    expect(merged.isComplete).toBe(true);
  });

  it("marks a page with no text rejected and both its boundaries unmatched", () => {
    const merged = mergeOcrPages([
      page(0, "첫 페이지 본문 내용입니다\n두 번째 줄"),
      page(1),
      page(2, "세 번째 페이지 본문입니다\n마지막 줄"),
    ]);

    expect(merged.rejectedPageIndexes).toEqual([1]);
    expect(merged.unmatchedBoundaryIndexes).toEqual([0, 1]);
    expect(merged.isComplete).toBe(false);
  });

  it("keeps floor-rejected pages in pageUris and reports them incomplete", () => {
    const pages = [page(0, "가맹점 이름 표시줄\n금액 표시줄"), page(1, "금액 표시줄\n마지막 줄")];
    const merged = mergeOcrPages(pages, [1]);

    expect(merged.pageUris).toEqual([pages[0]?.uri, pages[1]?.uri]);
    expect(merged.rejectedPageIndexes).toEqual([1]);
    expect(merged.isComplete).toBe(false);
  });

  it("rejects an out-of-range rejected index instead of ignoring it", () => {
    expect(() => mergeOcrPages([page(0, "한 페이지 본문입니다\n두 번째 줄")], [3])).toThrow(
      RangeError
    );
  });

  it("does not mutate its inputs", () => {
    const pages = [page(0, "가맹점 이름 표시줄\n금액 표시줄"), page(1, "금액 표시줄\n마지막 줄")];
    const snapshot = JSON.stringify(pages);
    const rejected = [1];

    mergeOcrPages(pages, rejected);

    expect(JSON.stringify(pages)).toBe(snapshot);
    expect(rejected).toEqual([1]);
  });

  it("treats a single non-empty page as complete", () => {
    const merged = mergeOcrPages([page(0, "한 장으로 끝난 영수증\n합계 1,000원")]);

    expect(merged.isComplete).toBe(true);
    expect(merged.unmatchedBoundaryIndexes).toEqual([]);
  });

  it("merges ten pages of 200 lines within the recorded bound", () => {
    // Measured at 59 ms on the development Mac mini (2026-07-31). The bound is
    // deliberately ~17x that so CI machine variance cannot make this flaky; it
    // is a guard against an accidental blow-up, not a performance target.
    const overlap = Array.from({ length: 4 }, (_unused, line) => `겹치는 줄 번호 ${line} 표시`);
    const pages = Array.from({ length: 10 }, (_unused, index) => {
      const body = Array.from(
        { length: 196 },
        (_line, line) => `페이지 ${index} 항목 ${line} 금액 ${line * 100}원`
      );
      return page(index, [...(index === 0 ? [] : overlap), ...body, ...overlap].join("\n"));
    });

    const started = performance.now();
    const merged = mergeOcrPages(pages);
    const elapsed = performance.now() - started;

    expect(merged.unmatchedBoundaryIndexes).toEqual([]);
    expect(elapsed).toBeLessThan(1000);
  });
});

describe("scan (mergeOcrPages orchestration)", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("throws before calling native when ocr is disabled", async () => {
    await expect(scan({ mergeOcrPages: true, ocr: false, maxPages: 3 })).rejects.toThrow(
      /requires ocr: true/
    );
    expect(mockNative.scan).not.toHaveBeenCalled();
  });

  it("throws before calling native when maxPages is below two", async () => {
    await expect(scan({ mergeOcrPages: true })).rejects.toThrow(/requires maxPages >= 2/);
    expect(mockNative.scan).not.toHaveBeenCalled();
  });

  it("omits mergedOcr when the option is off", async () => {
    mockNative.scan.mockResolvedValueOnce({
      status: "success",
      images: [page(0, "가맹점 이름 표시줄\n금액 표시줄")],
      rejectedImages: [],
    });

    const result = await scan();

    expect(result.mergedOcr).toBeUndefined();
  });

  it("restores native page order after the floor gate partitions the images", async () => {
    // Page 1 falls below the floor, so the gate moves it into rejectedImages
    // and the accepted list no longer carries capture order.
    mockNative.scan.mockResolvedValueOnce({
      status: "success",
      images: [
        page(0, "첫 페이지 본문 내용입니다\n공통 구분선 표시줄"),
        page(1, "짧음"),
        page(2, "공통 구분선 표시줄\n세 번째 페이지 본문입니다"),
      ],
      rejectedImages: [],
    });

    const result = await scan({ mergeOcrPages: true, maxPages: 3 });

    expect(result.mergedOcr?.pageUris).toEqual([
      "file:///tmp/page-0.jpg",
      "file:///tmp/page-1.jpg",
      "file:///tmp/page-2.jpg",
    ]);
    expect(result.mergedOcr?.rejectedPageIndexes).toEqual([1]);
    expect(result.mergedOcr?.isComplete).toBe(false);
  });

  it("attaches mergedOcr even when every page falls below the floor", async () => {
    mockNative.scan.mockResolvedValueOnce({
      status: "success",
      images: [page(0, "짧음"), page(1, "짧음")],
      rejectedImages: [],
    });

    const result = await scan({ mergeOcrPages: true, maxPages: 2 });

    expect(result.status).toBe("rejected");
    expect(result.mergedOcr).toBeDefined();
    expect(result.mergedOcr?.rejectedPageIndexes).toEqual([0, 1]);
  });

  it("omits mergedOcr for a cancelled scan", async () => {
    mockNative.scan.mockResolvedValueOnce({
      status: "cancelled",
      images: [],
      rejectedImages: [],
    });

    const result = await scan({ mergeOcrPages: true, maxPages: 3 });

    expect(result.status).toBe("cancelled");
    expect(result.mergedOcr).toBeUndefined();
  });

  it("throws on a duplicate page URI rather than reordering silently", async () => {
    mockNative.scan.mockResolvedValueOnce({
      status: "success",
      images: [
        page(0, "첫 페이지 본문 내용입니다\n두 번째 줄"),
        page(0, "같은 URI 를 가진 페이지\n두 번째 줄"),
      ],
      rejectedImages: [],
    });

    await expect(scan({ mergeOcrPages: true, maxPages: 2 })).rejects.toThrow(
      /Duplicate receipt page URI/
    );
  });
});
