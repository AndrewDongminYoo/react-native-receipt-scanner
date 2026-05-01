#import <Foundation/Foundation.h>

@class UIImage;

NS_ASSUME_NONNULL_BEGIN

@interface RNOcrProcessor : NSObject

/**
 * Run text recognition on image. Returns recognized text joined by newlines.
 * Uses ko-KR + en-US on iOS 16+; falls back to en-US only on iOS 15.
 * Call on a background thread — this blocks until recognition completes.
 */
+ (nullable NSString *)recognizeTextInImage:(UIImage *)image
                                      error:(NSError **)error;

@end

NS_ASSUME_NONNULL_END
