#import <UIKit/UIKit.h>
#import <UniformTypeIdentifiers/UniformTypeIdentifiers.h>
#import "RNImageProcessor.h"

static NSUInteger failures = 0;
static void check(BOOL matches, NSString *label) {
    if (!matches) {
        fprintf(stderr, "FAIL %s\n", label.UTF8String);
        failures++;
    }
}

static NSDictionary *properties(CGImageSourceRef source) {
    return CFBridgingRelease(CGImageSourceCopyPropertiesAtIndex(source, 0, NULL));
}

static void checkOutput(CGImageRef pixels, CGImageSourceRef source,
                        BOOL includeExif, BOOL includeGps, BOOL includeRaw) {
    NSError *error = nil;
    RNProcessedImage *result = [RNImageProcessor processImage:pixels quality:0.82
        sourceRef:source includeExif:includeExif includeGpsExif:includeGps
        includeRawExif:includeRaw error:&error];
    check(result != nil && error == nil, @"production encoder succeeds");
    if (!result) return;
    CGImageSourceRef output = CGImageSourceCreateWithURL((__bridge CFURLRef)result.fileURL, NULL);
    check(output != NULL, @"read encoded JPEG");
    if (!output) return;
    NSDictionary *p = properties(output);
    NSDictionary *exif = p[@"{Exif}"], *tiff = p[@"{TIFF}"], *gps = p[@"{GPS}"];
    check([p[@"Orientation"] isEqual:@1], @"file orientation normalized");
    check([tiff[@"Orientation"] isEqual:@1], @"TIFF orientation normalized");
    check([p[@"PixelWidth"] unsignedIntegerValue] == CGImageGetWidth(pixels) &&
          [p[@"PixelHeight"] unsignedIntegerValue] == CGImageGetHeight(pixels), @"encoded dimensions");

    // These literal fields must survive the fixture writer, then be stripped by production.
    for (NSString *key in @[@"UserComment", @"CameraOwnerName", @"BodySerialNumber",
                            @"LensSerialNumber", @"ImageUniqueID", @"PixelXDimension", @"PixelYDimension"]) {
        if ([key hasPrefix:@"Pixel"]) {
            NSNumber *expected = [key isEqual:@"PixelXDimension"] ? p[@"PixelWidth"] : p[@"PixelHeight"];
            check(!exif[key] || [exif[key] isEqual:expected], @"source EXIF dimensions cannot survive crop/rotation");
        } else {
            check(exif[key] == nil, [@"strip EXIF " stringByAppendingString:key]);
        }
    }
    for (NSString *key in @[@"Artist", @"Copyright", @"ImageDescription", @"HostComputer"]) {
        check(tiff[key] == nil, [@"strip TIFF " stringByAppendingString:key]);
    }
    check((gps.count > 0) == (includeExif && includeGps), @"file GPS opt-in and EXIF precedence");
    check((result.exifData != nil) == includeExif, @"JS EXIF opt-in");
    if (includeExif) {
        NSDictionary *original = properties(source);
        for (NSString *key in @[@"DateTimeOriginal", @"DateTimeDigitized", @"ExposureTime", @"FNumber",
                                @"FocalLength", @"Flash", @"WhiteBalance", @"ExposureMode", @"ExposureProgram",
                                @"MeteringMode", @"LightSource", @"ExifVersion", @"ISOSpeedRatings"]) {
            check([exif[key] isEqual:original[@"{Exif}"][key]], [@"retain EXIF " stringByAppendingString:key]);
        }
        // ImageIO may rewrite ColorSpace after CoreImage rotation creates a new pixel space.
        check([p[@"ColorModel"] isEqual:@"RGB"] &&
              (!exif[@"ColorSpace"] || [@[@1, @65535] containsObject:exif[@"ColorSpace"]]),
              @"encoded color information describes output pixels");
        check([result.exifData[@"colorSpace"] isEqual:@1], @"JS color space retains source value");
        for (NSString *key in @[@"Make", @"Model", @"Software", @"DateTime"]) {
            check([tiff[key] isEqual:original[@"{TIFF}"][key]], [@"retain TIFF " stringByAppendingString:key]);
        }
        check([result.exifData[@"orientation"] isEqual:@1], @"JS orientation normalized");
        check([result.exifData[@"make"] isEqual:@"Synthetic Make"] &&
              [result.exifData[@"iso"] isEqual:@100], @"JS normalized fields retained");
        check((result.exifData[@"gps"] != nil) == includeGps, @"JS GPS opt-in");
        check((result.exifData[@"raw"] != nil) == includeRaw, @"JS raw opt-in");
        if (includeRaw) {
            NSDictionary *raw = result.exifData[@"raw"];
            check([raw[@"Artist"] isEqual:@"Synthetic Artist"], @"raw source tag independent of file allowlist");
            check([raw[@"Orientation"] isEqual:@6], @"raw original orientation retained");
            check((raw[@"GPSLatitude"] != nil) == includeGps, @"raw GPS opt-in");
        }
    } else {
        check(exif[@"DateTimeOriginal"] == nil && exif[@"ExposureTime"] == nil &&
              tiff[@"Make"] == nil && tiff[@"Model"] == nil && tiff[@"Software"] == nil,
              @"disabled EXIF strips source fields from file");
    }
    CFRelease(output);
    check([[NSFileManager defaultManager] removeItemAtURL:result.fileURL error:&error], @"remove test JPEG");
}

int main(void) {
    @autoreleasepool {
        // Asymmetric pixels and stale EXIF dimensions exercise real transforms and re-encoding.
        UIGraphicsImageRendererFormat *format = [UIGraphicsImageRendererFormat defaultFormat];
        format.scale = 1;
        format.preferredRange = UIGraphicsImageRendererFormatRangeStandard;
        UIGraphicsImageRenderer *renderer = [[UIGraphicsImageRenderer alloc]
            initWithSize:CGSizeMake(24, 16) format:format];
        UIImage *image = [renderer imageWithActions:^(UIGraphicsImageRendererContext *context) {
            (void)context;
            [UIColor.redColor setFill]; UIRectFill(CGRectMake(0, 0, 24, 16));
            [UIColor.blueColor setFill]; UIRectFill(CGRectMake(0, 0, 8, 8));
        }];
        NSDictionary *fixture = @{
            @"Orientation": @6,
            @"{TIFF}": @{@"Orientation": @6, @"Make": @"Synthetic Make", @"Model": @"Synthetic Model",
                @"Software": @"Synthetic Editor", @"DateTime": @"2026:01:02 03:04:05",
                @"Artist": @"Synthetic Artist", @"Copyright": @"Synthetic Copyright",
                @"ImageDescription": @"Synthetic Description", @"HostComputer": @"Synthetic Host"},
            @"{Exif}": @{@"DateTimeOriginal": @"2026:01:02 03:04:05", @"DateTimeDigitized": @"2026:01:02 03:04:06",
                @"ExposureTime": @0.01, @"FNumber": @2.0, @"FocalLength": @4.0, @"Flash": @0,
                @"WhiteBalance": @1, @"ExposureMode": @1, @"ExposureProgram": @2, @"MeteringMode": @5,
                @"ColorSpace": @1, @"LightSource": @1, @"ExifVersion": @"0232", @"ISOSpeedRatings": @[@100],
                @"UserComment": @"Synthetic Comment", @"CameraOwnerName": @"Synthetic Owner",
                @"BodySerialNumber": @"SYNTHETIC-BODY", @"LensSerialNumber": @"SYNTHETIC-LENS",
                @"ImageUniqueID": @"00000000000000000000000000000000",
                @"PixelXDimension": @999, @"PixelYDimension": @777},
            @"{GPS}": @{@"Latitude": @12.5, @"LatitudeRef": @"N", @"Longitude": @34.5, @"LongitudeRef": @"E"},
        };
        NSMutableData *data = [NSMutableData new];
        CGImageDestinationRef writer = CGImageDestinationCreateWithData((__bridge CFMutableDataRef)data,
            (__bridge CFStringRef)UTTypeJPEG.identifier, 1, NULL);
        CGImageDestinationAddImage(writer, image.CGImage, (__bridge CFDictionaryRef)fixture);
        check(CGImageDestinationFinalize(writer), @"write synthetic source");
        CFRelease(writer);
        CGImageSourceRef source = CGImageSourceCreateWithData((__bridge CFDataRef)data, NULL);
        if (!source) return 2;
        NSDictionary *original = properties(source);
        for (NSString *group in @[@"{TIFF}", @"{Exif}", @"{GPS}"]) {
            for (NSString *key in fixture[group]) {
                check(original[group][key] != nil, [@"fixture contains " stringByAppendingString:key]);
            }
        }
        // Fail closed if ImageIO did not encode the fields this regression promises to read.
        if (failures) { CFRelease(source); return 2; }
        UIImage *oriented = [UIImage imageWithCGImage:image.CGImage scale:1 orientation:UIImageOrientationRight];
        UIImage *normalized = [RNImageProcessor normalizeOrientation:oriented];
        NSUInteger cases = 0;
        for (NSNumber *degrees in @[@0, @90, @180, @270]) {
            CGImageRef pixels = [RNImageProcessor cgImageByRotating:normalized.CGImage degrees:degrees.integerValue];
            if (!pixels) { check(NO, @"pixel rotation succeeds"); continue; }
            for (NSUInteger flags = 0; flags < 8; flags++) {
                checkOutput(pixels, source, (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0);
                cases++;
            }
            CFRelease(pixels);
        }
        // VisionKit has no source metadata; the JS-only device synthesis remains supported.
        NSError *error = nil;
        RNProcessedImage *camera = [RNImageProcessor processImage:image.CGImage quality:0.82 sourceRef:NULL
            includeExif:YES includeGpsExif:YES includeRawExif:YES error:&error];
        check(camera != nil && [camera.exifData[@"make"] isEqual:@"Apple"] &&
              camera.exifData[@"gps"] == nil && camera.exifData[@"raw"] == nil, @"camera synthetic JS metadata");
        if (camera) [[NSFileManager defaultManager] removeItemAtURL:camera.fileURL error:&error];
        CFRelease(source);
        check(cases == 32, @"complete flag and rotation matrix");
        printf("iOS JPEG metadata: %lu cases, %lu failures\n", (unsigned long)cases, (unsigned long)failures);
        return failures ? 1 : 0;
    }
}
