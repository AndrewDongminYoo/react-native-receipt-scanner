#import "RNReceiptOverlapPilotProvider.h"
#include "../cpp/overlap_pilot/ReceiptOverlapPilot.h"

@implementation RNReceiptOverlapPilotProvider
- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params {
  return std::make_shared<facebook::react::ReceiptOverlapPilot>(params.jsInvoker);
}
@end
