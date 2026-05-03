#import "RNImageProcessor.h"
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
                                     error:(NSError **)error {
    NSString *uuid = [[[NSUUID UUID] UUIDString] substringToIndex:8];
    NSURL *outputURL = [[self cacheDirURL]
        URLByAppendingPathComponent:[NSString stringWithFormat:@"receipt_%lld_%@.jpg",
                                     (long long)([[NSDate date] timeIntervalSince1970] * 1000),
                                     uuid]];

    NSString *uti;
    if (@available(iOS 14, *)) {
        uti = UTTypeJPEG.identifier;
    } else {
        uti = @"public.jpeg";
    }

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
            ? [self buildExifDict:sourceProps includeGps:includeGpsExif]
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

+ (nullable NSDictionary *)buildExifDict:(NSDictionary *)sourceProps includeGps:(BOOL)includeGps {
    NSMutableDictionary *exif = [NSMutableDictionary new];

    NSDictionary *exifDict = sourceProps[(NSString *)kCGImagePropertyExifDictionary];
    NSDictionary *tiffDict = sourceProps[(NSString *)kCGImagePropertyTIFFDictionary];
    NSDictionary *gpsDict  = sourceProps[(NSString *)kCGImagePropertyGPSDictionary];

    // Output pixels are always orientation-normalized; report 1 (Up) to JS callers.
    exif[@"orientation"] = @(kCGImagePropertyOrientationUp);

    NSString *dateTimeOriginal = exifDict[(NSString *)kCGImagePropertyExifDateTimeOriginal];
    if (dateTimeOriginal) exif[@"dateTimeOriginal"] = dateTimeOriginal;

    NSString *make  = tiffDict[(NSString *)kCGImagePropertyTIFFMake];
    NSString *model = tiffDict[(NSString *)kCGImagePropertyTIFFModel];
    if (make)  exif[@"make"]  = make;
    if (model) exif[@"model"] = model;

    if (includeGps && gpsDict) {
        NSNumber *lat = gpsDict[(NSString *)kCGImagePropertyGPSLatitude];
        NSNumber *lon = gpsDict[(NSString *)kCGImagePropertyGPSLongitude];
        NSString *latRef = gpsDict[(NSString *)kCGImagePropertyGPSLatitudeRef];
        NSString *lonRef = gpsDict[(NSString *)kCGImagePropertyGPSLongitudeRef];
        if (lat && lon) {
            double latitude  = [lat doubleValue] * ([latRef isEqualToString:@"S"] ? -1.0 : 1.0);
            double longitude = [lon doubleValue] * ([lonRef isEqualToString:@"W"] ? -1.0 : 1.0);
            exif[@"gps"] = @{@"latitude": @(latitude), @"longitude": @(longitude)};
        }
    }

    return exif.count > 0 ? [exif copy] : nil;
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
