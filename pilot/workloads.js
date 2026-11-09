// Frozen synthetic workload generator v1. No timestamps or runtime randomness.
const SEED = 0x69c0ffee;
const SIZES = { small: [2, 20, 24], typical: [5, 100, 48], stress: [20, 500, 96] };
const ALPHABETS = {
  ascii: "AbCdEf",
  korean: "가나다라마바",
  supplementary: "😀🚀🧾",
  variants: "Ab \tCd",
};

function workloads() {
  const output = [];
  for (const [size, [pageCount, lineCount, width]] of Object.entries(SIZES)) {
    for (const [alphabet, characters] of Object.entries(ALPHABETS)) {
      for (const mode of ["none", "half", "full", "near"]) {
        let state = SEED;
        const row = (index) => {
          state = (Math.imul(state, 1664525) + 1013904223 + 4294967296) % 4294967296;
          const prefix = `${index.toString(36)}-${state.toString(36)}:`;
          return (prefix + characters.repeat(width)).slice(0, width);
        };
        const pages = [];
        let previous = [];
        for (let page = 0; page < pageCount; page++) {
          let lines = Array.from({ length: lineCount }, (_, index) =>
            row(page * lineCount + index)
          );
          if (page && mode === "half") {
            lines.splice(0, lineCount / 2, ...previous.slice(lineCount / 2));
          } else if (page && mode === "full") {
            lines = [...previous];
          } else if (mode === "near") {
            // Every possible depth reaches a different mismatch; depths near
            // the full page compare many equal rows before failing.
            lines = Array.from({ length: lineCount }, () =>
              characters.repeat(width).slice(0, width)
            );
            lines[(page * 17 + 7) % lineCount] = row(page);
          }
          previous = lines;
          pages.push(lines.join("\n"));
        }
        output.push({ id: `${size}/${alphabet}/${mode}`, pages });
      }
    }
  }
  return output;
}

module.exports = { SEED, workloads };
