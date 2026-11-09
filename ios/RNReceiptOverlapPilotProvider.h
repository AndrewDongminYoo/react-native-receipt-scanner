#include <cxxreact/ReactNativeVersion.h>

#if defined(REACT_NATIVE_VERSION_MAJOR) && defined(REACT_NATIVE_VERSION_MINOR) && \
    (REACT_NATIVE_VERSION_MAJOR > 0 || REACT_NATIVE_VERSION_MINOR >= 79)
#import <ReactCommon/RCTTurboModule.h>

@interface RNReceiptOverlapPilotProvider : NSObject <RCTModuleProvider>
@end
#endif
