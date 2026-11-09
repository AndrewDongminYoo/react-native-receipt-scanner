#include "../NumericTransport.h"
#include <iostream>
#include <string>
#include <vector>

receipt::pilot::Page readPage() {
  size_t unitCount, rangeCount;
  if (!(std::cin >> unitCount >> rangeCount)) throw std::invalid_argument("Missing counts");
  receipt::pilot::validateCounts(unitCount, rangeCount);
  std::vector<double> units(unitCount), ranges(rangeCount);
  for (auto *values : {&units, &ranges}) {
    for (auto &value : *values) {
      std::string token;
      if (!(std::cin >> token)) throw std::invalid_argument("Missing value");
      value = std::stod(token);
    }
  }
  return receipt::pilot::decodePage(unitCount, [&](size_t i) { return units.at(i); },
                                    rangeCount, [&](size_t i) { return ranges.at(i); });
}

int main() {
  try {
    const auto left = readPage();
    const auto right = readPage();
    std::cout << receipt::pilot::findOverlap(left, right, receipt_core_abi_version()) << '\n';
    return 0;
  } catch (const std::invalid_argument &error) {
    std::cerr << "PILOT_INVALID_INPUT: " << error.what() << '\n';
    return 2;
  } catch (const std::exception &error) {
    std::cerr << error.what() << '\n';
    return 1;
  }
}
