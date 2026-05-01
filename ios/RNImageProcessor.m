#import "RNImageProcessor.h"
#import <UIKit/UIKit.h>
#import <UniformTypeIdentifiers/UniformTypeIdentifiers.h>

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

    NSDictionary *sourceProps = nil;
    if (includeExif && sourceRef) {
        sourceProps = (__bridge_transfer NSDictionary *)
            CGImageSourceCopyPropertiesAtIndex(sourceRef, 0, NULL);

        if (sourceProps[(NSString *)kCGImagePropertyExifDictionary]) {
            props[(NSString *)kCGImagePropertyExifDictionary] =
                sourceProps[(NSString *)kCGImagePropertyExifDictionary];
        }
        if (sourceProps[(NSString *)kCGImagePropertyTIFFDictionary]) {
            props[(NSString *)kCGImagePropertyTIFFDictionary] =
                sourceProps[(NSString *)kCGImagePropertyTIFFDictionary];
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

    NSDictionary *attrs = [[NSFileManager defaultManager]
        attributesOfItemAtPath:outputURL.path error:nil];

    RNProcessedImage *result = [RNProcessedImage new];
    result.fileURL  = outputURL;
    result.width    = (NSInteger)CGImageGetWidth(cgImage);
    result.height   = (NSInteger)CGImageGetHeight(cgImage);
    result.fileSize = [attrs[NSFileSize] integerValue];

    if (includeExif && sourceProps) {
        result.exifData = [self buildExifDict:sourceProps includeGps:includeGpsExif];
    }

    return result;
}

+ (nullable NSDictionary *)buildExifDict:(NSDictionary *)sourceProps includeGps:(BOOL)includeGps {
    NSMutableDictionary *exif = [NSMutableDictionary new];

    NSDictionary *exifDict = sourceProps[(NSString *)kCGImagePropertyExifDictionary];
    NSDictionary *tiffDict = sourceProps[(NSString *)kCGImagePropertyTIFFDictionary];
    NSDictionary *gpsDict  = sourceProps[(NSString *)kCGImagePropertyGPSDictionary];

    NSNumber *orientation = sourceProps[(NSString *)kCGImagePropertyOrientation];
    if (orientation) exif[@"orientation"] = orientation;

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
