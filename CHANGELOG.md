# Changelog

## [0.1.0](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/compare/347aaf4...v0.1.0) (2026-05-01)

### Features

#### iOS

- **ios:** implement `scan()` — dispatches to VisionKit camera or PHPickerViewController gallery path ([5a545cd](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/5a545cd))
- **ios:** add `RNGalleryPickerDelegate` — PHPicker, `VNDetectRectanglesRequest`, interactive perspective-crop editor ([b33be13](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/b33be13))
- **ios:** add `RNCropEditorViewController` — 4-corner drag-handle overlay with `CIPerspectiveCorrection` ([07f6e1c](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/07f6e1c))
- **ios:** add `RNDocumentCameraDelegate` — `VNDocumentCameraViewController` camera scan delegate ([ec2bc2f](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/ec2bc2f))
- **ios:** add `RNOcrProcessor` — `VNRecognizeTextRequest` with `ko-KR` / `en-US` language support ([8c3d0d7](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/8c3d0d7))
- **ios:** add `RNImageProcessor` — JPEG recompression, EXIF extraction, session cache cleanup ([9ce7514](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/9ce7514))
- **ios:** add `RNScanOptions` — options parsing with `NSNull` guards and clamped defaults ([b1fc9a3](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/b1fc9a3))

#### Android

- **android:** integrate ML Kit Document Scanner (`GmsDocumentScannerOptions`) for camera and gallery import ([1001caf](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/1001caf))
- **android:** add `OcrProcessor` — ML Kit Korean Text Recognition (Hangul + Latin characters) ([38bf937](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/38bf937))
- **android:** add `ImageProcessor` — JPEG recompression, `ExifInterface` extraction, session cache cleanup ([7aa71e2](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/7aa71e2))
- **android:** add `ResultBuilder` — serialises processing results to `WritableMap` / `WritableArray` ([a32a6c9](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/a32a6c9))
- **android:** add `ScanOptions` data class — `ReadableMap` parsing with typed defaults ([c367ddf](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/c367ddf))

#### JS / Cross-platform

- implement `scan()` with `ScanReceiptOptions` defaults merging and native delegation ([83ef32f](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/83ef32f))
- add TypeScript types: `ScanReceiptOptions`, `ScanReceiptResult`, `ReceiptImage`, `ReceiptExif` ([2a5a0fa](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/2a5a0fa))
- add TurboModule spec `NativeReceiptScanner` — New Architecture (JSI) on both platforms ([8f6ff4d](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/8f6ff4d))
- add web / JS stub returning `{ status: "cancelled", images: [] }` ([83ef32f](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/83ef32f))

### Bug Fixes

#### iOS

- **ios:** fix `CGImageRef` use-after-free in gallery crop path ([702ee73](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/702ee73))
- **ios:** use `__typeof__` instead of `typeof` in `.mm` files — `typeof` is not valid in C++ mode ([bae0871](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/bae0871))
- **ios:** cast ternary result to `NSNumber *` before property access — resolves ObjC/C++ ambiguity ([453693d](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/453693d))
- **ios:** fix build errors — ObjC `?:` inside `[]` subscript ambiguity and missing `RCTUtils` header ([1518df3](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/1518df3))
- **ios:** eliminate duplicate `CGImageSourceCopyPropertiesAtIndex` call; add UUID suffix to output filenames to prevent collisions ([053d1ae](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/053d1ae))
- **ios:** address code quality findings — weak reference handling, retain cycle prevention ([021cce2](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/021cce2))

#### Android

- **android:** fix `onNewIntent` signature — `Intent` parameter must be non-nullable per `ActivityEventListener` spec ([3f7fe7c](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/3f7fe7c))

### Documentation

- rewrite README — add comparison table against competing libraries, full iOS / Android setup guide, error code reference ([d85f56c](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/d85f56c))
- add API contract and scan pipeline specs ([c9d628f](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/c9d628f))
- add Phase 1 (JS), Phase 2 (Android), Phase 3 (iOS) implementation plans ([78272cf](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/commit/78272cf))
