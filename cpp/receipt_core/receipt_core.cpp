#include "receipt_core.h"
#include <cstddef>
#include <cstdint>

namespace {
bool valid_page(const uint16_t *data, uint32_t length,
                const receipt_core_line_v1 *lines, uint32_t count) noexcept {
  if ((!data && length != 0) || (!lines && count != 0)) return false;
  // Check byte-size arithmetic even on targets with a narrower address space.
  const uintmax_t limit = SIZE_MAX < PTRDIFF_MAX ? SIZE_MAX : PTRDIFF_MAX;
  if (static_cast<uintmax_t>(length) > limit / sizeof(uint16_t) ||
      static_cast<uintmax_t>(count) > limit / sizeof(receipt_core_line_v1)) return false;
  for (uint32_t i = 0; i < count; ++i) {
    if (lines[i].offset > length || lines[i].length > length - lines[i].offset)
      return false;
  }
  return true;
}

bool equal_line(const uint16_t *left, receipt_core_line_v1 a,
                const uint16_t *right, receipt_core_line_v1 b) noexcept {
  if (a.length != b.length) return false;
  for (uint32_t i = 0; i < a.length; ++i)
    if (left[a.offset + i] != right[b.offset + i]) return false;
  return true;
}
} // namespace

uint32_t RECEIPT_CORE_CALL receipt_core_abi_version(void) noexcept {
  return RECEIPT_CORE_ABI_VERSION;
}

uint32_t RECEIPT_CORE_CALL receipt_core_find_overlap_v1(
    const uint16_t *left, uint32_t left_length,
    const receipt_core_line_v1 *left_lines, uint32_t left_count,
    const uint16_t *right, uint32_t right_length,
    const receipt_core_line_v1 *right_lines, uint32_t right_count,
    uint32_t *out_overlap) noexcept {
  if (!out_overlap) return RECEIPT_CORE_INVALID_INPUT;
  *out_overlap = 0;
  if (!valid_page(left, left_length, left_lines, left_count) ||
      !valid_page(right, right_length, right_lines, right_count))
    return RECEIPT_CORE_INVALID_INPUT;

  uint32_t depth = left_count < right_count ? left_count : right_count;
  for (; depth >= 2; --depth) {
    const uint32_t start = left_count - depth;
    bool equal = true;
    bool distinct = false;
    uint32_t units = 0;
    for (uint32_t i = 0; i < depth; ++i) {
      if (!equal_line(left, left_lines[start + i], right, right_lines[i])) {
        equal = false;
        break;
      }
      if (i != 0 && !distinct)
        distinct = !equal_line(right, right_lines[0], right, right_lines[i]);
      // Saturate at the threshold instead of adding unbounded line lengths.
      if (i != 0 && units < 12) ++units;
      const uint32_t remaining = 12 - units;
      units += right_lines[i].length < remaining ? right_lines[i].length : remaining;
    }
    if (equal && distinct && units == 12) {
      *out_overlap = depth;
      break;
    }
  }
  return RECEIPT_CORE_OK;
}
