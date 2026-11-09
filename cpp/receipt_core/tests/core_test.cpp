#include "receipt_core.h"
#include <atomic>
#include <cstddef>
#include <cstdlib>
#include <iostream>
#include <thread>
#include <vector>

#define CHECK(value) do { if (!(value)) { \
  std::cerr << "failed at line " << __LINE__ << ": " #value "\n"; \
  std::exit(1); } } while (false)

static uint32_t match(const uint16_t *data, uint32_t length,
                      const receipt_core_line_v1 *lines, uint32_t count,
                      uint32_t *out) {
  return receipt_core_find_overlap_v1(data, length, lines, count,
                                    data, length, lines, count, out);
}

int main() {
  static_assert(sizeof(receipt_core_line_v1) == 8, "ABI size");
  static_assert(alignof(receipt_core_line_v1) == 4, "ABI alignment");
  static_assert(offsetof(receipt_core_line_v1, length) == 4, "ABI offset");
  const uint16_t data[] = {'a','a','a','a','a','b','b','b','b','b','b'};
  const receipt_core_line_v1 lines[] = {{0, 5}, {5, 6}};
  uint32_t out = 99;
  CHECK(receipt_core_abi_version() == RECEIPT_CORE_ABI_VERSION);
  CHECK(match(data, 11, lines, 2, &out) == RECEIPT_CORE_OK && out == 2);
  const uint16_t upper[] = {'A','A','A','A','A','B','B','B','B','B','B'};
  out = 99;
  CHECK(receipt_core_find_overlap_v1(upper, 11, lines, 2, data, 11,
        lines, 2, &out) == RECEIPT_CORE_OK && out == 0);
  CHECK(match(data, 10, lines, 2, &out) == RECEIPT_CORE_INVALID_INPUT && out == 0);
  CHECK(match(nullptr, 0, nullptr, 0, &out) == RECEIPT_CORE_OK && out == 0);
  CHECK(match(nullptr, 1, nullptr, 0, &out) == RECEIPT_CORE_INVALID_INPUT);
  CHECK(match(data, 11, nullptr, 1, &out) == RECEIPT_CORE_INVALID_INPUT);
  CHECK(match(data, 11, lines, 2, nullptr) == RECEIPT_CORE_INVALID_INPUT);
  const receipt_core_line_v1 invalid[] = {{UINT32_MAX, 2}, {1, UINT32_MAX}};
  for (const auto &line : invalid) {
    out = 99;
    CHECK(match(data, 11, &line, 1, &out) == RECEIPT_CORE_INVALID_INPUT && out == 0);
    // An empty opposite page must not bypass validation.
    CHECK(receipt_core_find_overlap_v1(nullptr, 0, nullptr, 0, data, 11,
          &line, 1, &out) == RECEIPT_CORE_INVALID_INPUT);
    CHECK(receipt_core_find_overlap_v1(data, 11, &line, 1, nullptr, 0,
          nullptr, 0, &out) == RECEIPT_CORE_INVALID_INPUT);
  }
  const receipt_core_line_v1 repeated[] = {{0, 5}, {0, 5}, {0, 5}};
  CHECK(match(data, 11, repeated, 3, &out) == RECEIPT_CORE_OK && out == 0);
  const receipt_core_line_v1 short_lines[] = {{0, 5}, {5, 5}};
  CHECK(match(data, 11, short_lines, 2, &out) == RECEIPT_CORE_OK && out == 0);
  const receipt_core_line_v1 empty[] = {{11, 0}, {0, 11}};
  CHECK(match(data, 11, empty, 2, &out) == RECEIPT_CORE_OK && out == 2);
  const receipt_core_line_v1 null_empty[] = {{0, 0}};
  CHECK(match(nullptr, 0, null_empty, 1, &out) == RECEIPT_CORE_OK && out == 0);
  // A fully equal constant window cannot gain a distinct line at smaller depths.
  const std::vector<uint16_t> constant_data(100, 'a');
  const std::vector<receipt_core_line_v1> constant_lines(10000, {0, 100});
  CHECK(match(constant_data.data(), 100, constant_lines.data(), 10000, &out)
        == RECEIPT_CORE_OK && out == 0);
  // Shared borrowed input, independent output, repeated concurrent calls.
  std::atomic<bool> failed{false};
  std::vector<std::thread> threads;
  for (int i = 0; i < 4; ++i) threads.emplace_back([&] {
    for (int j = 0; j < 10000; ++j) {
      uint32_t result = 99;
      if (match(data, 11, lines, 2, &result) != RECEIPT_CORE_OK || result != 2)
        failed = true;
    }
  });
  for (auto &thread : threads) thread.join();
  CHECK(!failed);
}
