#import "RNImageProcessor.h"
#import "RNQuadGeometry.h"
#import <UIKit/UIKit.h>
#import <CoreImage/CoreImage.h>
#import <UniformTypeIdentifiers/UniformTypeIdentifiers.h>

static CGImagePropertyOrientation RNOrientationFromUIImageOrientation(UIImageOrientation o) {
    switch (o) {
        case UIImageOrientationUp:            return kCGImagePropertyOrientationUp;
        case UIImageOrientationDown:          return kCGImagePropertyOrientationDown;
        case UIImageOrientationLeft:          return kCGImagePropertyOrientationLeft;
        case UIImageOrientationRight:         return kCGImagePropertyOrientationRight;
        case UIImageOrientationUpMirrored:    return kCGImagePropertyOrientationUpMirrored;
        case UIImageOrientationDownMirrored:  return kCGImagePropertyOrientationDownMirrored;
        case UIImageOrientationLeftMirrored:  return kCGImagePropertyOrientationLeftMirrored;
        case UIImageOrientationRightMirrored: return kCGImagePropertyOrientationRightMirrored;
    }
}

@implementation RNProcessedImage
@end

@implementation RNImageProcessor

+ (nullable RNProcessedImage *)processImage:(CGImageRef)cgImage
                                    quality:(double)quality
                                  sourceRef:(nullable CGImageSourceRef)sourceRef
                               includeExif:(BOOL)includeExif
                            includeGpsExif:(BOOL)includeGpsExif
                            includeRawExif:(BOOL)includeRawExif
                                     error:(NSError **)error {
    NSString *uuid = [[[NSUUID UUID] UUIDString] substringToIndex:8];
    NSURL *outputURL = [[self cacheDirURL]
        URLByAppendingPathComponent:[NSString stringWithFormat:@"receipt_%lld_%@.jpg",
                                     (long long)([[NSDate date] timeIntervalSince1970] * 1000),
                                     uuid]];

    NSString *uti = UTTypeJPEG.identifier;

    CGImageDestinationRef dest = CGImageDestinationCreateWithURL(
        (__bridge CFURLRef)outputURL,
        (__bridge CFStringRef)uti,
        1, NULL);

    if (!dest) {
        if (error) {
            *error = [NSError errorWithDomain:@"RNImageProcessor" code:1
                                     userInfo:@{NSLocalizedDescriptionKey: @"CGImageDestinationCreateWithURL failed"}];
        }
        return nil;
    }

    NSMutableDictionary *props = [NSMutableDictionary new];
    props[(NSString *)kCGImageDestinationLossyCompressionQuality] = @(quality);

    // CGImageRef carries no orientation metadata — the pixels ARE the final representation.
    // Always write orientation 1 (Up/normal) so viewers don't rotate an already-correct image.
    props[(NSString *)kCGImagePropertyOrientation] = @(kCGImagePropertyOrientationUp);

    NSDictionary *sourceProps = nil;
    if (includeExif && sourceRef) {
        sourceProps = (__bridge_transfer NSDictionary *)
            CGImageSourceCopyPropertiesAtIndex(sourceRef, 0, NULL);

        if (sourceProps[(NSString *)kCGImagePropertyExifDictionary]) {
            props[(NSString *)kCGImagePropertyExifDictionary] =
                sourceProps[(NSString *)kCGImagePropertyExifDictionary];
        }
        if (sourceProps[(NSString *)kCGImagePropertyTIFFDictionary]) {
            // Strip TIFF orientation — output pixels are already correctly oriented.
            NSMutableDictionary *tiff = [sourceProps[(NSString *)kCGImagePropertyTIFFDictionary] mutableCopy];
            tiff[(NSString *)kCGImagePropertyTIFFOrientation] = @(kCGImagePropertyOrientationUp);
            props[(NSString *)kCGImagePropertyTIFFDictionary] = [tiff copy];
        }
        if (includeGpsExif && sourceProps[(NSString *)kCGImagePropertyGPSDictionary]) {
            props[(NSString *)kCGImagePropertyGPSDictionary] =
                sourceProps[(NSString *)kCGImagePropertyGPSDictionary];
        }
    }

    CGImageDestinationAddImage(dest, cgImage, (__bridge CFDictionaryRef)props);

    BOOL ok = CGImageDestinationFinalize(dest);
    CFRelease(dest);

    if (!ok) {
        if (error) {
            *error = [NSError errorWithDomain:@"RNImageProcessor" code:2
                                     userInfo:@{NSLocalizedDescriptionKey: @"CGImageDestinationFinalize failed"}];
        }
        return nil;
    }

    NSError *attrsErr = nil;
    NSDictionary *attrs = [[NSFileManager defaultManager]
        attributesOfItemAtPath:outputURL.path error:&attrsErr];
    if (!attrs) {
        NSLog(@"RNImageProcessor: failed to read file attributes for %@: %@", outputURL.lastPathComponent, attrsErr);
    }

    RNProcessedImage *result = [RNProcessedImage new];
    result.fileURL  = outputURL;
    result.width    = (NSInteger)CGImageGetWidth(cgImage);
    result.height   = (NSInteger)CGImageGetHeight(cgImage);
    result.fileSize = [attrs[NSFileSize] integerValue];

    if (includeExif) {
        result.exifData = sourceProps
            ? [self buildExifDict:sourceProps includeGps:includeGpsExif includeRaw:includeRawExif]
            : [self buildDeviceExifDict];
    }

    return result;
}

+ (NSDictionary *)buildDeviceExifDict {
    NSDateFormatter *fmt = [NSDateFormatter new];
    fmt.dateFormat = @"yyyy:MM:dd HH:mm:ss";
    return @{
        @"make":              @"Apple",
        @"model":             [UIDevice currentDevice].model,
        @"orientation":       @(kCGImagePropertyOrientationUp),
        @"dateTimeOriginal":  [fmt stringFromDate:[NSDate date]],
    };
}

+ (nullable NSDictionary *)buildExifDict:(NSDictionary *)sourceProps
                              includeGps:(BOOL)includeGps
                              includeRaw:(BOOL)includeRaw {
    NSMutableDictionary *exif = [NSMutableDictionary new];

    NSDictionary *exifDict = sourceProps[(NSString *)kCGImagePropertyExifDictionary];
    NSDictionary *tiffDict = sourceProps[(NSString *)kCGImagePropertyTIFFDictionary];
    NSDictionary *gpsDict  = sourceProps[(NSString *)kCGImagePropertyGPSDictionary];

    // Output pixels are always orientation-normalized; report 1 (Up) to JS callers.
    // raw.Orientation (when includeRawExif) preserves the original value.
    exif[@"orientation"] = @(kCGImagePropertyOrientationUp);

    // ── Timestamps ──
    NSString *dateTimeOriginal  = exifDict[(NSString *)kCGImagePropertyExifDateTimeOriginal];
    NSString *dateTimeDigitized = exifDict[(NSString *)kCGImagePropertyExifDateTimeDigitized];
    NSString *dateTime          = tiffDict[(NSString *)kCGImagePropertyTIFFDateTime];
    if (dateTimeOriginal)  exif[@"dateTimeOriginal"]  = dateTimeOriginal;
    if (dateTimeDigitized) exif[@"dateTimeDigitized"] = dateTimeDigitized;
    if (dateTime)          exif[@"dateTime"]          = dateTime;

    // ── Device + software ──
    NSString *make  = tiffDict[(NSString *)kCGImagePropertyTIFFMake];
    NSString *model = tiffDict[(NSString *)kCGImagePropertyTIFFModel];
    if (make)  exif[@"make"]  = make;
    if (model) exif[@"model"] = model;

    // TIFF Software tag. iOS camera apps populate this with the OS version
    // (e.g. "17.0", "26.4.2"); Android camera apps may write a vendor / firmware
    // identifier (e.g. "F741NKSS3CZCS" on Galaxy Z Flip6). Editors and generators
    // write their own name. Forwarded as-is so the consumer can apply value-based
    // fraud rules on top of imageOrigin.
    NSString *software = tiffDict[(NSString *)kCGImagePropertyTIFFSoftware];
    if (software) exif[@"software"] = software;

    // ── Camera settings (numeric fields normalized to a single number) ──
    NSNumber *exposureTime = exifDict[(NSString *)kCGImagePropertyExifExposureTime];
    NSNumber *fNumber      = exifDict[(NSString *)kCGImagePropertyExifFNumber];
    NSNumber *focalLength  = exifDict[(NSString *)kCGImagePropertyExifFocalLength];
    NSNumber *flash        = exifDict[(NSString *)kCGImagePropertyExifFlash];
    NSNumber *whiteBalance = exifDict[(NSString *)kCGImagePropertyExifWhiteBalance];
    NSNumber *exposureMode    = exifDict[(NSString *)kCGImagePropertyExifExposureMode];
    NSNumber *exposureProgram = exifDict[(NSString *)kCGImagePropertyExifExposureProgram];
    NSNumber *meteringMode    = exifDict[(NSString *)kCGImagePropertyExifMeteringMode];
    NSNumber *colorSpace      = exifDict[(NSString *)kCGImagePropertyExifColorSpace];
    NSNumber *lightSource     = exifDict[(NSString *)kCGImagePropertyExifLightSource];
    NSString *exifVersion     = exifDict[(NSString *)kCGImagePropertyExifVersion];

    if (exposureTime)    exif[@"exposureTime"]    = exposureTime;
    if (fNumber)         exif[@"fNumber"]         = fNumber;
    if (focalLength)     exif[@"focalLength"]     = focalLength;
    if (flash)           exif[@"flash"]           = flash;
    if (whiteBalance)    exif[@"whiteBalance"]    = whiteBalance;
    if (exposureMode)    exif[@"exposureMode"]    = exposureMode;
    if (exposureProgram) exif[@"exposureProgram"] = exposureProgram;
    if (meteringMode)    exif[@"meteringMode"]    = meteringMode;
    if (colorSpace)      exif[@"colorSpace"]      = colorSpace;
    if (lightSource)     exif[@"lightSource"]     = lightSource;

    // ISOSpeedRatings is an NSArray on iOS (e.g. @[@50]) but Android exposes a
    // single string. Normalize to a single number for cross-platform consumers.
    id isoRaw = exifDict[(NSString *)kCGImagePropertyExifISOSpeedRatings];
    if ([isoRaw isKindOfClass:[NSArray class]] && [(NSArray *)isoRaw count] > 0) {
        exif[@"iso"] = [(NSArray *)isoRaw firstObject];
    } else if ([isoRaw isKindOfClass:[NSNumber class]]) {
        exif[@"iso"] = isoRaw;
    }

    // ExifVersion comes as either NSString ("0220") or NSArray of digits — coerce to string.
    if ([exifVersion isKindOfClass:[NSString class]]) {
        exif[@"exifVersion"] = exifVersion;
    } else if ([(id)exifVersion isKindOfClass:[NSArray class]]) {
        NSArray *parts = (NSArray *)exifVersion;
        NSMutableString *s = [NSMutableString new];
        for (id p in parts) [s appendFormat:@"%@", p];
        if (s.length > 0) exif[@"exifVersion"] = [s copy];
    }

    // ── GPS (white-list shape) ──
    if (includeGps && gpsDict) {
        NSNumber *lat = gpsDict[(NSString *)kCGImagePropertyGPSLatitude];
        NSNumber *lon = gpsDict[(NSString *)kCGImagePropertyGPSLongitude];
        NSString *latRef = gpsDict[(NSString *)kCGImagePropertyGPSLatitudeRef];
        NSString *lonRef = gpsDict[(NSString *)kCGImagePropertyGPSLongitudeRef];
        if (lat && lon) {
            double latitude  = [lat doubleValue] * ([latRef isEqualToString:@"S"] ? -1.0 : 1.0);
            double longitude = [lon doubleValue] * ([lonRef isEqualToString:@"W"] ? -1.0 : 1.0);
            NSMutableDictionary *gps = [@{@"latitude": @(latitude), @"longitude": @(longitude)} mutableCopy];

            NSNumber *altitude = gpsDict[(NSString *)kCGImagePropertyGPSAltitude];
            NSNumber *altRef   = gpsDict[(NSString *)kCGImagePropertyGPSAltitudeRef];
            if (altitude) gps[@"altitude"] = [altRef intValue] == 1
                ? @(-[altitude doubleValue]) : altitude;

            NSString *timestamp = gpsDict[(NSString *)kCGImagePropertyGPSTimeStamp];
            if (timestamp) gps[@"timestamp"] = timestamp;

            NSNumber *speed = gpsDict[(NSString *)kCGImagePropertyGPSSpeed];
            if (speed) gps[@"speed"] = speed;

            NSNumber *heading = gpsDict[(NSString *)kCGImagePropertyGPSImgDirection]
                ?: gpsDict[(NSString *)kCGImagePropertyGPSDestBearing];
            if (heading) gps[@"heading"] = heading;

            exif[@"gps"] = [gps copy];
        }
    }

    // ── Raw passthrough (flat map; binary fields excluded) ──
    if (includeRaw) {
        NSDictionary *raw = [self flattenRaw:sourceProps includeGps:includeGps];
        if (raw.count > 0) exif[@"raw"] = raw;
    }

    return exif.count > 0 ? [exif copy] : nil;
}

/** Build the raw EXIF flat map. Keys are standard EXIF tag names (Make, Software,
 *  FNumber, GPSLatitude, …). Binary fields and bridge-incompatible types are skipped.
 *  GPS keys are excluded entirely when includeGps is NO. */
+ (NSDictionary *)flattenRaw:(NSDictionary *)sourceProps includeGps:(BOOL)includeGps {
    NSMutableDictionary *raw = [NSMutableDictionary new];
    NSDictionary *tiff = sourceProps[(NSString *)kCGImagePropertyTIFFDictionary];
    NSDictionary *exif = sourceProps[(NSString *)kCGImagePropertyExifDictionary];
    NSDictionary *gps  = sourceProps[(NSString *)kCGImagePropertyGPSDictionary];

    // Tag names to skip — large binary blobs that bloat the IPC payload.
    static NSSet<NSString *> *deny;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        deny = [NSSet setWithArray:@[
            @"MakerNote",
            @"UserComment",
            @"ComponentsConfiguration",
            @"FileSource",
            @"SceneType",
            @"InteroperabilityIndex",
        ]];
    });

    void (^addFrom)(NSDictionary *, NSString *) = ^(NSDictionary *src, NSString *prefix) {
        for (NSString *key in src) {
            if ([deny containsObject:key]) continue;
            id value = src[key];
            // Skip values the bridge can't marshall cleanly (binary, dictionaries).
            if ([value isKindOfClass:[NSData class]]) continue;
            if ([value isKindOfClass:[NSDictionary class]]) continue;
            if (![value isKindOfClass:[NSString class]] &&
                ![value isKindOfClass:[NSNumber class]] &&
                ![value isKindOfClass:[NSArray class]]) continue;
            NSString *outKey = (prefix && ![key hasPrefix:prefix])
                ? [prefix stringByAppendingString:key]
                : key;
            raw[outKey] = value;
        }
    };
    if (tiff) addFrom(tiff, nil);
    if (exif) addFrom(exif, nil);
    if (includeGps && gps) addFrom(gps, @"GPS");

    return [raw copy];
}

+ (nullable CGImageRef)perspectiveCorrectedCGImage:(UIImage *)image
                                           corners:(NSArray<NSValue *> *)corners
    CF_RETURNS_RETAINED {
    CGPoint tl = [corners[0] CGPointValue];
    CGPoint tr = [corners[1] CGPointValue];
    CGPoint br = [corners[2] CGPointValue];
    CGPoint bl = [corners[3] CGPointValue];

    CGImagePropertyOrientation orientation = RNOrientationFromUIImageOrientation(image.imageOrientation);
    CIImage *ciInput = [[[CIImage alloc] initWithCGImage:image.CGImage]
        imageByApplyingOrientation:orientation];
    CGRect ext = ciInput.extent;
    if (ext.origin.x != 0 || ext.origin.y != 0) {
        ciInput = [ciInput imageByApplyingTransform:
            CGAffineTransformMakeTranslation(-ext.origin.x, -ext.origin.y)];
    }
    if ([RNQuadGeometry isDistorted:corners]) {
        // Distorted quad → crop the axis-aligned bbox in ciInput space, no warp.
        CGFloat minX = MIN(MIN(tl.x, tr.x), MIN(br.x, bl.x));
        CGFloat maxX = MAX(MAX(tl.x, tr.x), MAX(br.x, bl.x));
        CGFloat minY = MIN(MIN(tl.y, tr.y), MIN(br.y, bl.y));
        CGFloat maxY = MAX(MAX(tl.y, tr.y), MAX(br.y, bl.y));
        CGRect bbox = CGRectIntersection(ciInput.extent,
                                         CGRectMake(minX, minY, maxX - minX, maxY - minY));
        if (CGRectIsNull(bbox) || bbox.size.width < 1 || bbox.size.height < 1) return NULL;
        CIImage *croppedCI = [[ciInput imageByCroppingToRect:bbox]
            imageByApplyingTransform:CGAffineTransformMakeTranslation(-bbox.origin.x, -bbox.origin.y)];
        CIContext *bboxCtx = [CIContext context];
        return [bboxCtx createCGImage:croppedCI fromRect:croppedCI.extent];
    }
    CIFilter *filter = [CIFilter filterWithName:@"CIPerspectiveCorrection"];
    [filter setValue:ciInput              forKey:kCIInputImageKey];
    [filter setValue:[CIVector vectorWithX:tl.x Y:tl.y] forKey:@"inputTopLeft"];
    [filter setValue:[CIVector vectorWithX:tr.x Y:tr.y] forKey:@"inputTopRight"];
    [filter setValue:[CIVector vectorWithX:br.x Y:br.y] forKey:@"inputBottomRight"];
    [filter setValue:[CIVector vectorWithX:bl.x Y:bl.y] forKey:@"inputBottomLeft"];
    CIImage *output = filter.outputImage;
    if (!output) return NULL;

    // Allocate a fresh CIContext per call so concurrent callers (maxPages > 1) are safe.
    // CIContext is not thread-safe; a shared static instance would race under multi-image picks.
    CIContext *ctx = [CIContext context];
    return [ctx createCGImage:output fromRect:output.extent];
}

+ (UIImage *)normalizeOrientation:(UIImage *)image {
    if (image.imageOrientation == UIImageOrientationUp) return image;
    UIGraphicsImageRenderer *renderer =
        [[UIGraphicsImageRenderer alloc] initWithSize:image.size];
    return [renderer imageWithActions:^(UIGraphicsImageRendererContext *ctx) {
        [image drawInRect:CGRectMake(0, 0, image.size.width, image.size.height)];
    }];
}

+ (nullable CGImageRef)cgImageByRotating:(CGImageRef)cgImage
                                 degrees:(NSInteger)degrees
    CF_RETURNS_RETAINED {
    if (degrees == 0) {
        CGImageRetain(cgImage);
        return cgImage;
    }
    CGFloat radians;
    switch (degrees) {
        case 90:  radians = M_PI_2;  break;
        case 180: radians = M_PI;    break;
        case 270: radians = -M_PI_2; break;
        default:
            CGImageRetain(cgImage);
            return cgImage;
    }
    CIImage *ci = [CIImage imageWithCGImage:cgImage];
    CIImage *rotated = [ci imageByApplyingTransform:CGAffineTransformMakeRotation(radians)];
    CGRect ext = rotated.extent;
    if (ext.origin.x != 0 || ext.origin.y != 0) {
        rotated = [rotated imageByApplyingTransform:
            CGAffineTransformMakeTranslation(-ext.origin.x, -ext.origin.y)];
    }
    // Allocate a fresh CIContext per call so concurrent callers (maxPages > 1) are safe.
    CIContext *ctx = [CIContext context];
    return [ctx createCGImage:rotated fromRect:rotated.extent];
}

+ (void)deletePreviousSessionFiles {
    NSURL *cacheDir = [self cacheDirURL];
    NSArray<NSURL *> *files = [[NSFileManager defaultManager]
        contentsOfDirectoryAtURL:cacheDir
      includingPropertiesForKeys:nil
                         options:0
                           error:nil];
    for (NSURL *file in files) {
        if ([file.lastPathComponent hasPrefix:@"receipt_"] &&
            [file.pathExtension isEqualToString:@"jpg"]) {
            [[NSFileManager defaultManager] removeItemAtURL:file error:nil];
        }
    }
}

+ (NSURL *)cacheDirURL {
    return [[[NSFileManager defaultManager]
        URLsForDirectory:NSCachesDirectory inDomains:NSUserDomainMask] firstObject];
}

@end
