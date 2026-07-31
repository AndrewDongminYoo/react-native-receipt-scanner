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

  it("matches across whitespace and case differences but not a misread character", () => {
    // Normalization absorbs spacing and case, so this seam still proves.
    const spaced = mergeOcrPages([
      page(0, "영수증 상단\nAmericano  HOT   4,500원\n카페라떼 ICE 5,000원"),
      page(1, "americano HOT 4,500원\n카페라떼   ICE 5,000원\n합계 9,500원"),
    ]);
    expect(spaced.isComplete).toBe(true);
    expect(spaced.text).toBe(
      ["영수증 상단", "Americano  HOT   4,500원", "카페라떼 ICE 5,000원", "합계 9,500원"].join("\n")
    );

    // One misread character (the letter O for two zeros) is no longer merged.
    // Approximate matching was removed because no similarity floor separates
    // "the same line, misread" from "a different purchase" on receipt text.
    // The seam is reported instead, which the consumer can see.
    const misread = mergeOcrPages([
      page(0, "영수증 상단\nAmericano HOT 4,500원\n카페라떼 ICE 5,000원"),
      page(1, "Americano HOT 4,5OO원\n카페라떼 ICE 5,000원\n합계 9,500원"),
    ]);
    expect(misread.unmatchedBoundaryIndexes).toEqual([0]);
    expect(misread.isComplete).toBe(false);
    // Nothing dropped.
    expect(misread.text).toContain("Americano HOT 4,500원");
    expect(misread.text).toContain("Americano HOT 4,5OO원");
  });

  it("does not delete later purchases that merely look like the previous rows", () => {
    // Regression: a two-line window of similarly formatted purchase rows scored
    // 0.8974 over 39 normalized characters and cleared the old 0.85 multi-line
    // floor, deleting both later purchases while reporting isComplete. The
    // single-line exact-match guard did not cover this — the whole approximate
    // path had to go.
    const earlier = ["서울우유 1L 흰우유 1 2,000", "서울우유 1L 흰우유 2 4,000"];
    const later = ["서울우유 1L 흰우유 3 6,000", "서울우유 1L 흰우유 4 8,000"];

    const merged = mergeOcrPages([
      page(0, ["구매 내역 시작", ...earlier].join("\n")),
      page(1, [...later, "합계 20,000원"].join("\n")),
    ]);

    expect(merged.text).toBe(["구매 내역 시작", ...earlier, ...later, "합계 20,000원"].join("\n"));
    expect(merged.unmatchedBoundaryIndexes).toEqual([0]);
    expect(merged.isComplete).toBe(false);
  });

  it("keeps both pages and records the boundary when no window matches", () => {
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

  it("holds a two-line overlap to the 12-character floor", () => {
    // Two distinct lines, but only 9 characters between them — too little text
    // to be evidence of anything.
    const first = "합계";
    const second = "1,200원";
    expect(`${first} ${second}`.length).toBeLessThan(12);

    const merged = mergeOcrPages([
      page(0, `상단 문구\n${first}\n${second}`),
      page(1, `${first}\n${second}\n하단 문구`),
    ]);

    expect(merged.unmatchedBoundaryIndexes).toEqual([0]);
    expect(merged.text.split("\n").filter((line) => line === first)).toHaveLength(2);
  });

  it("does not delete a repeat purchase that differs only in quantity and amount", () => {
    // Regression: with a 0.92 fuzzy floor these two rows scored exactly 0.9200
    // over 25 normalized characters and were merged, silently deleting the
    // second purchase while still reporting isComplete. A lone line now has to
    // match exactly.
    const first = "서울우유 1L 흰우유 990ml    1     2,000";
    const second = "서울우유 1L 흰우유 990ml    2     4,000";

    const merged = mergeOcrPages([
      page(0, `구매 내역 시작\n${first}`),
      page(1, `${second}\n합계 6,000원`),
    ]);

    expect(merged.text).toBe(["구매 내역 시작", first, second, "합계 6,000원"].join("\n"));
    expect(merged.unmatchedBoundaryIndexes).toEqual([0]);
    expect(merged.isComplete).toBe(false);
  });

  it("merges an overlap of any depth, with no window ceiling", () => {
    const overlap = [
      "서울우유 1L         1    2,900",
      "신라면 5개입        1    4,250",
      "농심 새우깡         2    2,400",
      "바나나 1송이        1    3,980",
      "계란 한판 30구      1    7,900",
      "삼다수 2L 6입       1    5,400",
      "김치 500g          1    6,200",
      "두부 부침용        2    3,000",
      "대파 1단           1    2,480",
    ];
    const merged = mergeOcrPages([
      page(0, ["가맹점명 표시줄", ...overlap].join("\n")),
      page(1, [...overlap, "합계          38,510"].join("\n")),
    ]);

    expect(merged.text).toBe(["가맹점명 표시줄", ...overlap, "합계          38,510"].join("\n"));
    expect(merged.isComplete).toBe(true);
  });

  it("takes the deepest overlap, not a shallower one that coincides inside it", () => {
    // Regression: a receipt that repeats a separator and a section header at
    // both ends of the overlapped region matched at two lines as readily as at
    // nine. Merging on the shallow match dropped two lines, left seven
    // duplicated, and still reported isComplete — 18 lines where 11 were right.
    const separator = "구분선 ──────────────";
    const header = "상품 내역 계속 이어짐";
    const overlap = [
      separator,
      header,
      "우유 1 2,900",
      "라면 1 4,250",
      "새우깡 2 2,400",
      "바나나 1 3,980",
      "계란 1 7,900",
      separator,
      header,
    ];
    const merged = mergeOcrPages([
      page(0, ["가맹점명 표시줄 여기", ...overlap].join("\n")),
      page(1, [...overlap, "합계   21,430"].join("\n")),
    ]);

    expect(merged.text.split("\n")).toHaveLength(11);
    expect(merged.text).toBe(["가맹점명 표시줄 여기", ...overlap, "합계   21,430"].join("\n"));
    expect(merged.isComplete).toBe(true);
  });

  it("does not treat a single repeated row as a seam", () => {
    // Regression: buying two of one item prints the identical row twice, so a
    // page ending with it and the next page starting with it match exactly
    // without overlapping at all. Exact equality cannot tell that apart from a
    // recaptured line, so one row is never enough evidence — however long it is.
    const row = "서울우유 1L 흰우유 990ml    1     2,000";
    expect(row.length).toBeGreaterThanOrEqual(24);

    const merged = mergeOcrPages([
      page(0, `구매 내역 시작\n${row}`),
      page(1, `${row}\n합계 4,000원`),
    ]);

    expect(merged.text).toBe(["구매 내역 시작", row, row, "합계 4,000원"].join("\n"));
    expect(merged.unmatchedBoundaryIndexes).toEqual([0]);
    expect(merged.isComplete).toBe(false);
  });

  it("does not treat a run of one repeated row as a seam either", () => {
    // The same failure at greater depth: four identical rows split 2/2 across
    // the boundary match at depth 2 without overlapping.
    const row = "서울우유 1L 흰우유 990ml    1     2,000";
    const merged = mergeOcrPages([
      page(0, ["구매 내역 시작", row, row].join("\n")),
      page(1, [row, row, "합계 8,000원"].join("\n")),
    ]);

    expect(merged.text.split("\n").filter((line) => line === row)).toHaveLength(4);
    expect(merged.isComplete).toBe(false);
  });

  it("accepts a two-line overlap once it carries two distinct lines", () => {
    const address = "서울특별시 강남구 테헤란로 152 강남파이낸스센터";
    const owner = "대표 홍길동";

    const merged = mergeOcrPages([
      page(0, `가맹점 주소\n${address}\n${owner}`),
      page(1, `${address}\n${owner}\n사업자 123-45-67890`),
    ]);

    expect(merged.text).toBe(["가맹점 주소", address, owner, "사업자 123-45-67890"].join("\n"));
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
    // Measured at 3 ms on the development Mac mini (2026-07-31), down from 59 ms
    // under the previous Levenshtein matcher — comparing pre-normalized line
    // arrays with an early exit is far cheaper than building joined windows and
    // running an edit distance over them, even though the deepest-first scan is
    // O(n²) in the worst case. The bound is deliberately far above that so CI
    // machine variance cannot make this flaky; it guards against an accidental
    // blow-up, not a performance target.
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
    await expect(scan({ mergeOcrPages: true })).rejects.toThrow(/maxPages to be an integer >= 2/);
    expect(mockNative.scan).not.toHaveBeenCalled();
  });

  it("throws before calling native when maxPages is not a finite integer", async () => {
    // `NaN < 2` is false, so a bare comparison would dispatch to the scanner.
    for (const maxPages of [Number.NaN, Number.POSITIVE_INFINITY, 2.5]) {
      await expect(scan({ mergeOcrPages: true, maxPages })).rejects.toThrow(
        /maxPages to be an integer >= 2/
      );
    }
    expect(mockNative.scan).not.toHaveBeenCalled();
  });

  it("treats an explicitly undefined option as omitted", async () => {
    // exactOptionalPropertyTypes is off, so `{ ocr: undefined }` type-checks —
    // common when options are assembled from optional values. A plain spread
    // would let it overwrite the default and reject a valid call.
    mockNative.scan.mockResolvedValueOnce({
      status: "success",
      images: [
        page(0, "가맹점 이름 표시줄\n금액 표시줄"),
        page(1, "가맹점 이름 표시줄\n금액 표시줄\n합계 1,000원"),
      ],
      rejectedImages: [],
    });

    const result = await scan({ mergeOcrPages: true, maxPages: 2, ocr: undefined });

    expect(mockNative.scan).toHaveBeenCalled();
    expect(result.mergedOcr).toBeDefined();
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
