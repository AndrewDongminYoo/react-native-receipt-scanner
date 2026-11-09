#include "receipt_core.h"
#include <iostream>
#include <vector>

struct Page {
  std::vector<uint16_t> data;
  std::vector<receipt_core_line_v1> lines;
  bool read() {
    uint32_t length, count;
    if (!(std::cin >> length >> count)) return false;
    data.resize(length);
    lines.resize(count);
    for (auto &unit : data) if (!(std::cin >> unit)) return false;
    for (auto &line : lines)
      if (!(std::cin >> line.offset >> line.length)) return false;
    return true;
  }
};

int main() {
  Page left, right;
  while (left.read()) {
    if (!right.read()) return 1;
    uint32_t overlap = 99;
    const auto status = receipt_core_find_overlap_v1(
        left.data.data(), static_cast<uint32_t>(left.data.size()),
        left.lines.data(), static_cast<uint32_t>(left.lines.size()),
        right.data.data(), static_cast<uint32_t>(right.data.size()),
        right.lines.data(), static_cast<uint32_t>(right.lines.size()), &overlap);
    if (status != RECEIPT_CORE_OK) return 2;
    std::cout << overlap << '\n';
  }
  return std::cin.eof() ? 0 : 3;
}
