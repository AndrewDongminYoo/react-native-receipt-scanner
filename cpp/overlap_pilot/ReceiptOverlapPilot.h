#pragma once

#include <ReceiptScannerSpecJSI.h>
#include <memory>

namespace facebook::react {
class ReceiptOverlapPilot
    : public NativeReceiptOverlapPilotCxxSpec<ReceiptOverlapPilot> {
public:
  explicit ReceiptOverlapPilot(std::shared_ptr<CallInvoker> invoker);
  double getAbiVersion(jsi::Runtime &runtime);
  double findOverlap(jsi::Runtime &runtime, jsi::Array leftUnits,
                     jsi::Array leftRanges, jsi::Array rightUnits,
                     jsi::Array rightRanges);
};
} // namespace facebook::react
