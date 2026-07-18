#import "RNOcrProcessor.h"
#import "RNOcrGeometry.h"
#import <UIKit/UIKit.h>
#import <Vision/Vision.h>

// Vision's default minimumTextHeight is 1/32 of the image height; text smaller
// than this is dropped before recognition. Receipts have small line items, so
// keep this tunable. Set to the platform default for now (behavior-preserving);
// lower it once on-device experiments quantify the small-line recall vs. noise
// trade-off.
static const CGFloat kReceiptMinTextHeight = 1.0f / 32.0f;

// Count-only rotation routing. PROVISIONAL — biased against rotating
// (a false rotation is worse than a missed one); calibrate with corpus logs.
static const NSInteger kMinLinesToJudgeOrientation = 3;  // fewer lines -> trust 0°
static const NSInteger kUprightLineCount = 8;            // 0° already clearly upright -> skip probing
static const double kRotateCommitRatio = 1.3;            // probe must find >= ratio x 0° lines to rotate

@interface RNOcrProcessor ()

+ (nullable NSString *)runOcrOnCIImage:(CIImage *)ciImage
                                  level:(VNRequestTextRecognitionLevel)level
                      minimumTextHeight:(double)minimumTextHeight
                             outResults:(NSMutableArray<VNRecognizedTextObservation *> * _Nullable)outResults
                                  error:(NSError **)error;

/** Number of observations whose top candidate has non-empty text. This is the
 *  count-only rotation signal: recognition-level-comparable, unlike
 *  Vision's quantized per-line confidence. */
+ (NSInteger)nonEmptyCountFromResults:(NSArray<VNRecognizedTextObservation *> *)results;

/** Mean per-line confidence in [0, 1]. Used for ocrQuality reporting. */
+ (double)meanConfidenceFromResults:(NSArray<VNRecognizedTextObservation *> *)results;

/** Per-line `OcrLine` dictionaries in top-left pixels of a `pixelSize` frame. */
+ (NSArray<NSDictionary *> *)linesFromResults:(NSArray<VNRecognizedTextObservation *> *)results
                                    pixelSize:(CGSize)pixelSize;

/** Trimmed-mean (10% top / 10% bottom) of each observation's normalized
 *  bounding-box `width / height`. DEBUG diagnostics only — the iOS mirror of
 *  Android's `lineAspectOf` (see docs/specs/ios-geometry-rotation-routing.md).
 *  Reporting-only: never read by the routing decision path. */
+ (double)meanBoxAspectFromResults:(NSArray<VNRecognizedTextObservation *> *)results;

/** Rotate ciImage by 90 / 180 / 270 degrees CCW and translate to a non-negative origin. */
+ (CIImage *)rotate:(CIImage *)ciImage byDegrees:(NSInteger)degrees;

/** Per-observation clockwise text angles, read off the observation quad. This is
 *  the iOS counterpart of ML Kit's `Text.Line.getAngle`, which Android gets for
 *  free. See docs/specs/ocr-angle-rotation-detection.md. */
+ (NSArray<NSNumber *> *)clockwiseAnglesFromResults:(NSArray<VNRecognizedTextObservation *> *)results;

/** Runs an accurate pass on `ciImage` rotated `ccwDegrees` and packages it as a
 *  result. Falls back to `fallbackResults` (already-recognized observations for
 *  the same rotation) when the accurate pass fails, so a failure degrades to
 *  coarser text rather than losing the rotation. */
+ (RNOcrResult *)resultByRotating:(CIImage *)ciImage
                       ccwDegrees:(NSInteger)ccwDegrees
                minimumTextHeight:(double)minimumTextHeight
                  fallbackResults:(NSArray<VNRecognizedTextObservation *> *)fallbackResults
                            error:(NSError **)error;

/** DEBUG-only diagnostics: logs observation count, mean confidence, and
 *  candidate-spread (top1 - top2 confidence) for one OCR pass. Compiled to a
 *  no-op in release builds. Used to calibrate confidence thresholds against a
 *  Korean receipt corpus; does not affect routing. */
+ (void)logDiagnostics:(NSString *)label
               results:(NSArray<VNRecognizedTextObservation *> *)results;

@end

@implementation RNOcrResult
@end

@implementation RNOcrProcessor

+ (nullable RNOcrResult *)recognizeAndDetectRotationInImage:(UIImage *)image
                                          minimumTextHeight:(double)minimumTextHeight
                                                       error:(NSError **)error {
    CIImage *ciImage = [[CIImage alloc] initWithImage:image];
    if (!ciImage) {
        if (error) {
            *error = [NSError errorWithDomain:@"RNOcrProcessor" code:1
                                     userInfo:@{NSLocalizedDescriptionKey:
                                                    @"Failed to create CIImage from UIImage"}];
        }
        return nil;
    }

    CGSize size = image.size;
    double aspect = size.height > 0 ? (double)size.width / (double)size.height : 1.0;

    // Pass 1 — accurate at 0°
    NSMutableArray<VNRecognizedTextObservation *> *r0 = [NSMutableArray new];
    NSString *t0 = [self runOcrOnCIImage:ciImage
                                   level:VNRequestTextRecognitionLevelAccurate
                       minimumTextHeight:minimumTextHeight
                              outResults:r0
                                   error:error];
    if (!t0) return nil;

    NSInteger c0 = [self nonEmptyCountFromResults:r0];
    [self logDiagnostics:@"pass1 0deg accurate" results:r0];

    // Result skeleton — defaults to "0° accepted". Every early return below
    // ships these 0° boxes; the Pass-3 branches overwrite them.
    RNOcrResult *result = [RNOcrResult new];
    result.text = t0;
    result.rotationDegrees = 0;
    result.meanConfidence = [self meanConfidenceFromResults:r0];
    result.passSize = ciImage.extent.size;
    result.lines = [self linesFromResults:r0 pixelSize:result.passSize];

    // Too few recognized lines to judge orientation — return as-is.
    if (c0 < kMinLinesToJudgeOrientation) {
        return result;
    }

    // ---- Primary signal: the angle of the text itself ----
    // Read off the observation quad, which carries direction the line *count*
    // cannot. This deliberately runs ahead of the fast paths below: those return
    // on line count alone, which is precisely why a 180°-flipped portrait
    // receipt — plenty of lines, every one upside down — was never detected.
    // See docs/specs/ocr-angle-rotation-detection.md.
    NSInteger textAngle =
        [RNOcrGeometry dominantQuarterTurnFromAngles:[self clockwiseAnglesFromResults:r0]];
    if (textAngle != RNOcrGeometryQuarterTurnUnknown) {
        NSInteger correction = [RNOcrGeometry correctionForTextAngle:textAngle];
        // Only a quarter turn is acted on, never a confirmed 0. The probe loop
        // below is still the live path on iOS 16/17, where Vision is *not*
        // rotation-robust, so letting a 0 short-circuit it would regress those
        // versions if the angle turns out to be reported in Vision's own reading
        // frame rather than in image space — the one assumption this design
        // rests on that cannot be checked from source. Android can afford the
        // stricter reading because its fallback false-positives; this one does not.
        if (correction != 0) {
            // `correction` is clockwise (the canonical direction, §3.1); this
            // rotate: is counter-clockwise, so hand it the complement.
            // No fallback observations: r0 was measured on the *unrotated*
            // frame, so reusing it here would place boxes in the wrong frame.
            NSError *rotateErr = nil;
            RNOcrResult *rotatedResult = [self resultByRotating:ciImage
                                                     ccwDegrees:(360 - correction) % 360
                                              minimumTextHeight:minimumTextHeight
                                                fallbackResults:nil
                                                          error:&rotateErr];
            if (rotatedResult) return rotatedResult;
            // Re-reading the rotated frame failed. Ship the upright 0° result
            // rather than a rotation whose boxes we cannot place.
            return result;
        }
    }

    // Portrait fast path: a 0° read that already yields many lines is almost
    // certainly upright (Vision recovers far fewer lines from sideways text),
    // so skip probing. Count-only — no confidence gate.
    BOOL isPortrait = aspect <= 1.0;
    if (isPortrait && c0 >= kUprightLineCount) {
        return result;
    }

    // Landscape fast path: genuine landscape receipts (hotel folio, restaurant
    // tally) are ~1.0–1.5 aspect; trust 0° when it already reads many lines.
    if (!isPortrait && c0 >= kUprightLineCount && aspect <= 1.5) {
        return result;
    }

    // ---- Probe rotations ----
    // Portrait: probe 180° only (v1.0 behavior preserved).
    // Landscape: probe 90° / 180° / 270°.
    NSArray<NSNumber *> *probeDegrees = isPortrait ? @[@180] : @[@90, @180, @270];

    NSInteger bestDegrees = 0;
    NSInteger bestCount = c0;
    NSArray<VNRecognizedTextObservation *> *bestResults = r0;

    for (NSNumber *deg in probeDegrees) {
        CIImage *rotated = [self rotate:ciImage byDegrees:deg.integerValue];
        NSMutableArray<VNRecognizedTextObservation *> *rN = [NSMutableArray new];
        NSError *probeErr = nil;
        [self runOcrOnCIImage:rotated
                        level:VNRequestTextRecognitionLevelFast
            minimumTextHeight:minimumTextHeight
                   outResults:rN
                        error:&probeErr];
        NSInteger cN = [self nonEmptyCountFromResults:rN];
        [self logDiagnostics:[NSString stringWithFormat:@"probe %lddeg fast",
                                                        (long)deg.integerValue]
                     results:rN];
        if (cN > bestCount) {
            bestCount = cN;
            bestDegrees = deg.integerValue;
            bestResults = rN;
        }
    }

    // Commit a rotation only if a probe found substantially more lines than 0°.
    // Probes run at .fast (fewer lines than the .accurate baseline), so the
    // ratio biases against rotating — a false rotation is worse than a missed
    // one. PROVISIONAL ratio; calibrate with corpus logs.
    if (bestDegrees == 0 || bestCount < c0 * kRotateCommitRatio) {
        return result;
    }

    // Pass 3 — accurate on the chosen rotation. bestResults were measured on
    // that same rotated frame, so they are a usable fallback.
    RNOcrResult *rotatedResult = [self resultByRotating:ciImage
                                             ccwDegrees:bestDegrees
                                      minimumTextHeight:minimumTextHeight
                                        fallbackResults:bestResults
                                                  error:error];
    return rotatedResult ?: result;
}

#pragma mark - Private helpers

+ (RNOcrResult *)resultByRotating:(CIImage *)ciImage
                       ccwDegrees:(NSInteger)ccwDegrees
                minimumTextHeight:(double)minimumTextHeight
                  fallbackResults:(NSArray<VNRecognizedTextObservation *> *)fallbackResults
                            error:(NSError **)error {
    CIImage *rotated = [self rotate:ciImage byDegrees:ccwDegrees];
    NSMutableArray<VNRecognizedTextObservation *> *r3 = [NSMutableArray new];
    NSString *t3 = [self runOcrOnCIImage:rotated
                                   level:VNRequestTextRecognitionLevelAccurate
                       minimumTextHeight:minimumTextHeight
                              outResults:r3
                                   error:error];

    // The accurate pass failed. Only the caller that already has observations
    // for *this* rotation can degrade gracefully; without them there is nothing
    // to place boxes against, so report the failure and let the caller keep its
    // own 0° result.
    NSArray<VNRecognizedTextObservation *> *chosen = t3 ? r3 : fallbackResults;
    if (!chosen) return nil;

    RNOcrResult *result = [RNOcrResult new];
    result.text = t3 ?: [self joinObservationsToText:chosen];
    result.rotationDegrees = ccwDegrees;
    result.meanConfidence = [self meanConfidenceFromResults:chosen];
    result.passSize = rotated.extent.size;
    result.lines = [self linesFromResults:chosen pixelSize:result.passSize];
    if (t3) [self logDiagnostics:@"pass3 chosen accurate" results:r3];
    return result;
}

+ (NSArray<NSNumber *> *)clockwiseAnglesFromResults:(NSArray<VNRecognizedTextObservation *> *)results {
    NSMutableArray<NSNumber *> *angles = [NSMutableArray arrayWithCapacity:results.count];
    for (VNRecognizedTextObservation *obs in results) {
        VNRecognizedText *top = [[obs topCandidates:1] firstObject];
        if (top.string.length == 0) continue;
        [angles addObject:@([RNOcrGeometry clockwiseAngleFromTopLeft:obs.topLeft
                                                            topRight:obs.topRight])];
    }
    return [angles copy];
}

+ (nullable NSString *)runOcrOnCIImage:(CIImage *)ciImage
                                  level:(VNRequestTextRecognitionLevel)level
                      minimumTextHeight:(double)minimumTextHeight
                             outResults:(NSMutableArray<VNRecognizedTextObservation *> * _Nullable)outResults
                                  error:(NSError **)error {
    VNRecognizeTextRequest *request = [[VNRecognizeTextRequest alloc] init];
    request.recognitionLanguages = @[@"ko-KR", @"en-US"];
    request.recognitionLevel = level;
    // Receipts: disable language correction. Prices, product codes, and short
    // tokens are not dictionary words, so correction over-corrects and distorts
    // them. Applies to both .fast and .accurate passes.
    request.usesLanguageCorrection = NO;
    // 0 (or unset) means "use the package default"; a caller-provided fraction
    // in (0, 1] overrides it. iOS only — Android (ML Kit) has no equivalent.
    request.minimumTextHeight = minimumTextHeight > 0 ? minimumTextHeight : kReceiptMinTextHeight;

    VNImageRequestHandler *handler =
        [[VNImageRequestHandler alloc] initWithCIImage:ciImage options:@{}];
    if (![handler performRequests:@[request] error:error]) {
        return nil;
    }

    NSArray<VNRecognizedTextObservation *> *results = request.results;
    if (outResults) {
        [outResults addObjectsFromArray:results];
    }

    return [self joinObservationsToText:results];
}

+ (NSString *)joinObservationsToText:(NSArray<VNRecognizedTextObservation *> *)results {
    NSMutableArray<NSString *> *lines = [NSMutableArray new];
    for (VNRecognizedTextObservation *obs in results) {
        VNRecognizedText *top = [[obs topCandidates:1] firstObject];
        if (top.string.length > 0) [lines addObject:top.string];
    }
    return [lines componentsJoinedByString:@"\n"];
}

+ (NSInteger)nonEmptyCountFromResults:(NSArray<VNRecognizedTextObservation *> *)results {
    NSInteger count = 0;
    for (VNRecognizedTextObservation *obs in results) {
        VNRecognizedText *top = [[obs topCandidates:1] firstObject];
        if (top.string.length > 0) count++;
    }
    return count;
}

+ (double)meanConfidenceFromResults:(NSArray<VNRecognizedTextObservation *> *)results {
    if (results.count == 0) return 0.0;
    double sumConf = 0.0;
    for (VNRecognizedTextObservation *obs in results) {
        VNRecognizedText *top = [[obs topCandidates:1] firstObject];
        if (top) sumConf += top.confidence;
    }
    return sumConf / results.count;
}

+ (NSArray<NSDictionary *> *)linesFromResults:(NSArray<VNRecognizedTextObservation *> *)results
                                    pixelSize:(CGSize)pixelSize {
    NSMutableArray<NSDictionary *> *lines = [NSMutableArray arrayWithCapacity:results.count];
    for (VNRecognizedTextObservation *obs in results) {
        VNRecognizedText *top = [[obs topCandidates:1] firstObject];
        if (top.string.length == 0) continue;
        CGRect frame = [RNOcrGeometry rectFromNormalizedBox:obs.boundingBox pixelSize:pixelSize];
        if (frame.size.width <= 0 || frame.size.height <= 0) continue;
        [lines addObject:@{
            @"text": top.string,
            @"frame": @{
                @"x":      @(frame.origin.x),
                @"y":      @(frame.origin.y),
                @"width":  @(frame.size.width),
                @"height": @(frame.size.height),
            },
            @"confidence": @(top.confidence),
        }];
    }
    return [lines copy];
}

+ (double)meanBoxAspectFromResults:(NSArray<VNRecognizedTextObservation *> *)results {
    NSMutableArray<NSNumber *> *ratios = [NSMutableArray new];
    for (VNRecognizedTextObservation *obs in results) {
        CGRect box = obs.boundingBox;  // normalized [0, 1], bottom-left origin
        if (box.size.width <= 0.0 || box.size.height <= 0.0) continue;
        [ratios addObject:@(box.size.width / box.size.height)];
    }
    if (ratios.count == 0) return 1.0;
    [ratios sortUsingSelector:@selector(compare:)];
    // Trim 10% from each end once there are enough samples (mirrors Android).
    BOOL trimmable = ratios.count >= 5;
    NSUInteger from = trimmable ? (NSUInteger)(ratios.count * 0.10) : 0;
    NSUInteger to = trimmable ? ratios.count - from : ratios.count;
    if (to <= from) return 1.0;
    double sum = 0.0;
    for (NSUInteger i = from; i < to; i++) sum += ratios[i].doubleValue;
    return sum / (double)(to - from);
}

+ (void)logDiagnostics:(NSString *)label
               results:(NSArray<VNRecognizedTextObservation *> *)results {
#if DEBUG
    if (results.count == 0) {
        NSLog(@"[ReceiptScanner.Ocr] %@ count=0", label);
        return;
    }
    double sumSpread = 0.0;
    NSInteger spreadCount = 0;
    for (VNRecognizedTextObservation *obs in results) {
        NSArray<VNRecognizedText *> *candidates = [obs topCandidates:2];
        if (candidates.count >= 2) {
            sumSpread += (candidates[0].confidence - candidates[1].confidence);
            spreadCount++;
        }
    }
    double meanConf = [self meanConfidenceFromResults:results];
    double meanSpread = spreadCount > 0 ? sumSpread / spreadCount : 0.0;
    // boxAspect: iOS mirror of Android lineAspect, for geometry-routing
    // calibration (future work). Reporting-only; see
    // docs/specs/ios-geometry-rotation-routing.md.
    double meanBoxAspect = [self meanBoxAspectFromResults:results];
    // Full quarter-turn histogram, not just the winner: calibrating
    // RNOcrGeometryAngleMajority needs the spread, and an [n,0,0,0] shape on an
    // image boxAspect calls sideways is the fingerprint of Vision reporting the
    // quad in its own reading frame rather than in image space — the assumption
    // this routing rests on. See docs/specs/ocr-angle-rotation-detection.md.
    NSArray<NSNumber *> *bins =
        [RNOcrGeometry quarterTurnHistogramFromAngles:[self clockwiseAnglesFromResults:results]];
    NSLog(@"[ReceiptScanner.Ocr] %@ count=%lu meanConf=%.3f candidateSpread=%.3f boxAspect=%.3f "
          @"angleBins=[%@,%@,%@,%@]",
          label, (unsigned long)results.count, meanConf, meanSpread, meanBoxAspect,
          bins[0], bins[1], bins[2], bins[3]);
#endif
}

+ (CIImage *)rotate:(CIImage *)ciImage byDegrees:(NSInteger)degrees {
    if (degrees == 0) return ciImage;
    CGFloat radians;
    switch (degrees) {
        case 90:  radians = M_PI_2;  break;
        case 180: radians = M_PI;    break;
        case 270: radians = -M_PI_2; break;
        default:  return ciImage;
    }
    CIImage *rotated = [ciImage imageByApplyingTransform:CGAffineTransformMakeRotation(radians)];
    CGRect ext = rotated.extent;
    return [rotated imageByApplyingTransform:
        CGAffineTransformMakeTranslation(-ext.origin.x, -ext.origin.y)];
}

@end
