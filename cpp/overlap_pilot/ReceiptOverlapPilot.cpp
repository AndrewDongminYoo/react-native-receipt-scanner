#include "ReceiptOverlapPilot.h"
#include "NumericTransport.h"

namespace facebook::react {
ReceiptOverlapPilot::ReceiptOverlapPilot(std::shared_ptr<CallInvoker> invoker)
    : NativeReceiptOverlapPilotCxxSpec(std::move(invoker)) {}

double ReceiptOverlapPilot::getAbiVersion(jsi::Runtime &) {
  return receipt_core_abi_version();
}

double ReceiptOverlapPilot::findOverlap(jsi::Runtime &runtime,
                                        jsi::Array leftUnits,
                                        jsi::Array leftRanges,
                                        jsi::Array rightUnits,
                                        jsi::Array rightRanges) {
  const auto decode = [&runtime](const jsi::Array &units,
                                 const jsi::Array &ranges) {
    const auto numberAt = [&runtime](const jsi::Array &array, size_t index) {
      const auto value = array.getValueAtIndex(runtime, index);
      if (!value.isNumber()) {
        throw std::invalid_argument("Numeric transport requires numbers");
      }
      return value.getNumber();
    };
    return receipt::pilot::decodePage(
        units.size(runtime), [&](size_t index) { return numberAt(units, index); },
        ranges.size(runtime), [&](size_t index) { return numberAt(ranges, index); });
  };
  try {
    // Check before decoding or calling the versioned entry point.
    const auto version = receipt_core_abi_version();
    if (version != RECEIPT_CORE_ABI_VERSION) {
      throw std::invalid_argument("Unsupported receipt core ABI version");
    }
    const auto left = decode(leftUnits, leftRanges);
    const auto right = decode(rightUnits, rightRanges);
    return receipt::pilot::findOverlap(left, right, version);
  } catch (const std::exception &error) {
    throw jsi::JSError(runtime, error.what());
  }
}
} // namespace facebook::react
