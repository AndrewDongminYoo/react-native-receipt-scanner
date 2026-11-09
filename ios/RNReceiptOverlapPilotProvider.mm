#import "RNReceiptOverlapPilotProvider.h"

#if defined(REACT_NATIVE_VERSION_MAJOR) && defined(REACT_NATIVE_VERSION_MINOR) && \
    (REACT_NATIVE_VERSION_MAJOR > 0 || REACT_NATIVE_VERSION_MINOR >= 79)
#include "../cpp/overlap_pilot/ReceiptOverlapPilot.h"

@implementation RNReceiptOverlapPilotProvider
- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params {
  return std::make_shared<facebook::react::ReceiptOverlapPilot>(params.jsInvoker);
}
@end
#endif
