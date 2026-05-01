#import "RNOcrProcessor.h"
#import <UIKit/UIKit.h>
#import <Vision/Vision.h>

@implementation RNOcrProcessor

+ (nullable NSString *)recognizeTextInImage:(UIImage *)image
                                      error:(NSError **)error {
    CIImage *ciImage = [[CIImage alloc] initWithImage:image];
    if (!ciImage) {
        if (error) {
            *error = [NSError errorWithDomain:@"RNOcrProcessor" code:1
                                     userInfo:@{NSLocalizedDescriptionKey: @"Failed to create CIImage from UIImage"}];
        }
        return nil;
    }

    NSArray<NSString *> *languages;
    if (@available(iOS 16, *)) {
        languages = @[@"ko-KR", @"en-US"];
    } else {
        languages = @[@"en-US"];
    }

    VNRecognizeTextRequest *request = [[VNRecognizeTextRequest alloc] init];
    request.recognitionLanguages = languages;
    request.recognitionLevel = VNRequestTextRecognitionLevelAccurate;
    request.usesLanguageCorrection = YES;

    VNImageRequestHandler *handler =
        [[VNImageRequestHandler alloc] initWithCIImage:ciImage options:@{}];
    if (![handler performRequests:@[request] error:error]) {
        return nil;
    }

    NSMutableArray<NSString *> *lines = [NSMutableArray new];
    for (VNRecognizedTextObservation *obs in request.results) {
        VNRecognizedText *top = [[obs topCandidates:1] firstObject];
        if (top.string.length > 0) [lines addObject:top.string];
    }
    return [lines componentsJoinedByString:@"\n"];
}

@end
