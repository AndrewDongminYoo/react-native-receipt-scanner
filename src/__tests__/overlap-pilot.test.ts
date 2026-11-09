import { describe, expect, it } from "@jest/globals";
import { mergeWithPilot } from "../overlapPilot";
import type { PilotMatcher } from "../overlapPilot";
import type { ReceiptImage } from "../types";
import { runParity } from "../../pilot/harness";

const pages: ReceiptImage[] = [
  { uri: "file:///a", ocrText: " START \n ABCDE😀 \n한글 가나다 " },
  { uri: "file:///b", ocrText: "abcde😀\n한글 가나다\n END " },
].map((page) => ({
  ...page,
  fileName: "page.jpg",
  mimeType: "image/jpeg",
  width: 1,
  height: 1,
  fileSize: 1,
  imageOrigin: "camera",
}));

describe("internal overlap pilot host", () => {
  it("appends large pages without exceeding the engine argument limit", () => {
    const text = Array(200000).fill("x").join("\n");
    const result = mergeWithPilot(
      [
        { ...pages[0]!, ocrText: "prefix" },
        { ...pages[1]!, ocrText: text },
      ],
      [],
      { getAbiVersion: () => 1, findOverlap: () => 0 }
    );
    expect(result.text).toBe(`prefix\n${text}`);
  });

  it("does not count infrastructure failures as malformed-input rejection", () => {
    let calls = 0;
    expect(() =>
      runParity(
        {
          getAbiVersion: () => 1,
          findOverlap: () => {
            if (calls++ === 0) throw new Error("driver infrastructure failure");
            throw new Error("validation loop continued after infrastructure failure");
          },
        },
        { mergeCases: [] }
      )
    ).toThrow("driver infrastructure failure");
    expect(calls).toBe(1);
  });

  it("rejects an incompatible ABI before calling the matcher", () => {
    const matcher: PilotMatcher = {
      getAbiVersion: () => 2,
      findOverlap: () => {
        throw new Error("must not call incompatible ABI");
      },
    };
    expect(() => mergeWithPilot(pages, [], matcher)).toThrow("Unsupported receipt core ABI");
  });

  it("transports UTF-16 units and retains original trimmed output", () => {
    let calls = 0;
    const result = mergeWithPilot(pages, [], {
      getAbiVersion: () => 1,
      findOverlap: (left, leftRanges, right, rightRanges) => {
        calls++;
        expect(left).toEqual([
          115, 116, 97, 114, 116, 97, 98, 99, 100, 101, 55357, 56832, 54620, 44544, 32, 44032,
          45208, 45796,
        ]);
        expect(leftRanges).toEqual([0, 5, 5, 7, 12, 6]);
        expect(right.slice(0, 7)).toEqual([97, 98, 99, 100, 101, 55357, 56832]);
        expect(rightRanges).toEqual([0, 7, 7, 6, 13, 3]);
        return 2;
      },
    });
    expect(calls).toBe(1);
    expect(result).toEqual({
      text: "START\nABCDE😀\n한글 가나다\nEND",
      isComplete: true,
      pageUris: ["file:///a", "file:///b"],
      unmatchedBoundaryIndexes: [],
      rejectedPageIndexes: [],
    });
  });

  it("rejects malformed overlap counts instead of slicing silently", () => {
    for (const count of [NaN, Infinity, -1, 0.5, 4]) {
      expect(() =>
        mergeWithPilot(pages, [], {
          getAbiVersion: () => 1,
          findOverlap: () => count,
        })
      ).toThrow("Invalid native overlap count");
    }
  });
});
