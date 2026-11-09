#pragma once

#include "../receipt_core/receipt_core.h"

#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <stdexcept>
#include <vector>

namespace receipt::pilot {
inline uint32_t checkedInteger(double value, uint32_t maximum) {
  if (!std::isfinite(value) || value < 0 || value > maximum ||
      std::floor(value) != value) {
    throw std::invalid_argument("Expected an unsigned integer within transport bounds");
  }
  return static_cast<uint32_t>(value);
}

struct Page {
  std::vector<uint16_t> units;
  std::vector<receipt_core_line_v1> lines;
};

inline void validateCounts(size_t unitCount, size_t rangeCount) {
  if (unitCount > UINT32_MAX || rangeCount % 2 != 0 ||
      rangeCount / 2 > UINT32_MAX ||
      unitCount > static_cast<size_t>(PTRDIFF_MAX) / sizeof(uint16_t) ||
      rangeCount / 2 >
          static_cast<size_t>(PTRDIFF_MAX) / sizeof(receipt_core_line_v1)) {
    throw std::invalid_argument("Invalid UTF-16 buffer or descriptor count");
  }
}

// Readers return a checked JS number; validation always precedes narrowing.
template <typename UnitAt, typename RangeAt>
Page decodePage(size_t unitCount, UnitAt unitAt, size_t rangeCount,
                RangeAt rangeAt) {
  validateCounts(unitCount, rangeCount);
  Page page;
  page.units.reserve(unitCount);
  for (size_t index = 0; index < unitCount; ++index) {
    page.units.push_back(static_cast<uint16_t>(checkedInteger(unitAt(index), UINT16_MAX)));
  }
  page.lines.reserve(rangeCount / 2);
  for (size_t index = 0; index < rangeCount; index += 2) {
    const uint32_t offset = checkedInteger(rangeAt(index), UINT32_MAX);
    const uint32_t length = checkedInteger(rangeAt(index + 1), UINT32_MAX);
    if (offset > unitCount || length > unitCount - offset) {
      throw std::invalid_argument("Line descriptor exceeds UTF-16 buffer");
    }
    page.lines.push_back({offset, length});
  }
  return page;
}

inline uint32_t findOverlap(const Page &left, const Page &right,
                            uint32_t abiVersion) {
  if (abiVersion != RECEIPT_CORE_ABI_VERSION) {
    throw std::invalid_argument("Unsupported receipt core ABI version");
  }
  uint32_t overlap = 0;
  const auto result = receipt_core_find_overlap_v1(
      left.units.data(), static_cast<uint32_t>(left.units.size()),
      left.lines.data(), static_cast<uint32_t>(left.lines.size()),
      right.units.data(), static_cast<uint32_t>(right.units.size()),
      right.lines.data(), static_cast<uint32_t>(right.lines.size()), &overlap);
  if (result != RECEIPT_CORE_OK) {
    throw std::invalid_argument("Receipt core rejected input");
  }
  return overlap;
}
} // namespace receipt::pilot
