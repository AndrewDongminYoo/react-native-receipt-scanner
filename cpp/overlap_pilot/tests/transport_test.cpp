#include "../NumericTransport.h"

#include <cmath>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <thread>
#include <atomic>
#include <vector>

int main() {
  try {
    if (receipt::pilot::checkedInteger(65535, 65535) != 65535) {
      throw std::runtime_error("UTF-16 code unit must survive numeric transport");
    }
    for (double value : {-1.0, 0.5, 65536.0,
                         std::numeric_limits<double>::infinity(),
                         std::numeric_limits<double>::quiet_NaN()}) {
      bool rejected = false;
      try {
        receipt::pilot::checkedInteger(value, 65535);
      } catch (const std::invalid_argument &) {
        rejected = true;
      }
      if (!rejected) {
        throw std::runtime_error("invalid code unit must be rejected before cast");
      }
    }
    if (receipt::pilot::checkedInteger(4294967295.0, UINT32_MAX) != UINT32_MAX) {
      throw std::runtime_error("uint32 descriptor limit must remain exact");
    }
    bool overflowRejected = false;
    try {
      receipt::pilot::checkedInteger(4294967296.0, UINT32_MAX);
    } catch (const std::invalid_argument &) {
      overflowRejected = true;
    }
    if (!overflowRejected) throw std::runtime_error("descriptor overflow accepted");
    const auto decode = [](const std::vector<double> &units,
                           const std::vector<double> &ranges) {
      return receipt::pilot::decodePage(units.size(), [&](size_t i) { return units.at(i); },
                                        ranges.size(), [&](size_t i) { return ranges.at(i); });
    };
    for (const auto &ranges : std::vector<std::vector<double>>{
             {0}, {0, 2}, {2, 0}, {0.5, 0}, {0, -1},
             {4294967295.0, 1}, {4294967296.0, 0}}) {
      bool rejected = false;
      try { decode({97}, ranges); }
      catch (const std::invalid_argument &) { rejected = true; }
      if (!rejected) throw std::runtime_error("invalid descriptor accepted");
    }
    bool oddRejected = false;
    try {
      receipt::pilot::decodePage(0, [](size_t) { return 0.0; }, 1,
                                 [](size_t) { return 0.0; });
    } catch (const std::invalid_argument &) { oddRejected = true; }
    if (!oddRejected) throw std::runtime_error("odd descriptor count accepted");
    bool oversizedRejected = false;
    try {
      receipt::pilot::decodePage(static_cast<size_t>(UINT32_MAX) + 1,
          [](size_t) { throw std::runtime_error("reader reached before count validation"); return 0.0; },
          0, [](size_t) { return 0.0; });
    } catch (const std::invalid_argument &) { oversizedRejected = true; }
    if (!oversizedRejected) throw std::runtime_error("oversized buffer accepted");
    const auto page = decode({0xd800, 97, 98, 99, 100, 101, 102, 103, 104, 105, 106},
                             {0, 5, 5, 6});
    if (receipt::pilot::findOverlap(page, page, 1) != 2) {
      throw std::runtime_error("literal UTF-16 overlap changed");
    }
    const auto different = decode({0xd801, 97, 98, 99, 100, 101, 102, 103, 104, 105, 106},
                                  {0, 5, 5, 6});
    if (receipt::pilot::findOverlap(page, different, 1) != 0) {
      throw std::runtime_error("different lone surrogates collapsed");
    }
    bool abiRejected = false;
    try { receipt::pilot::findOverlap(page, page, 2); }
    catch (const std::invalid_argument &) { abiRejected = true; }
    if (!abiRejected) throw std::runtime_error("unsupported ABI accepted");
    const auto empty = decode({}, {});
    if (receipt::pilot::findOverlap(empty, empty, 1) != 0) throw std::runtime_error("empty page overlap");
    std::atomic<bool> failed{false};
    std::vector<std::thread> threads;
    for (int i = 0; i < 4; ++i) {
      threads.emplace_back([&] {
        for (int j = 0; j < 1000; ++j) {
          const auto local = decode({97, 98, 99, 100, 101, 102, 103, 104, 105, 106, 107}, {0, 5, 5, 6});
          if (receipt::pilot::findOverlap(local, local, 1) != 2) failed = true;
        }
      });
    }
    for (auto &thread : threads) thread.join();
    if (failed) throw std::runtime_error("concurrent transport mismatch");
    std::cout << "numeric transport passed\n";
    return 0;
  } catch (const std::exception &error) {
    std::cerr << error.what() << '\n';
    return 1;
  }
}
