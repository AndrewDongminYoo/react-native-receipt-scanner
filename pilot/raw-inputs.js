// Runs only against JSI: host numeric-driver tests cannot establish JS types.
module.exports = function checkRawInputs(matcher) {
  const malformed = [
    [["97"], [], [], []],
    [[null], [], [], []],
    [[true], [], [], []],
    [[undefined], [], [], []],
    [[{}], [], [], []],
    [[], ["0", 0], [], []],
    [[], [0, null], [], []],
    [null, [], [], []],
    [{}, [], [], []],
  ];
  for (const [index, args] of malformed.entries()) {
    let rejected = false;
    try {
      matcher.findOverlap.apply(matcher, args);
    } catch (error) {
      rejected = error instanceof Error;
    }
    if (!rejected) throw new Error(`JSI malformed input accepted: ${index}`);
  }
  return malformed.length;
};
