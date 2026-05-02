# ADR-004: iOS Crop Editor — Real-Device Implementation Fixes

## Status

Accepted

## Context

After implementing `RNCropEditorViewController` and `RNGalleryPickerDelegate`, real-device
testing on a Face ID iPhone revealed three independent bugs that did not appear in the
simulator:

1. **"사진 사용" button had no response** — tapping the confirm button did nothing.
2. **Button bar positioned inside the home-indicator gesture zone** — the bar was rendered
   too close to the physical bottom of the screen.
3. **Perspective-corrected crop included content outside the selected quadrilateral** —
   the crop result was slightly wrong even after the user manually adjusted the corner handles.

---

## Decision 1: Replace UIToolbar + UIBarButtonItem with UIView + UIButton

### Problem

The original implementation used `UIToolbar` with `UIBarButtonItem` items, anchored to
`safeAreaLayoutGuide.bottomAnchor`. On the device, tapping the confirm button produced
no response.

Two root causes were identified:

- **Safe area mis-report**: `safeAreaLayoutGuide.bottomAnchor` can return `0` (no inset)
  when a view controller is presented via React Native's `RCTPresentedViewController`
  path. With a `0` bottom inset, the toolbar was placed at `view.bottom`, i.e., inside
  the home-indicator gesture zone (bottom ~34 pt on Face ID devices). The system
  consumed all taps in that zone before they reached UIKit.

- **UIBarButtonItem target-action routing**: `UIBarButtonItem` dispatches through the
  responder chain and can silently fail when the presented view controller's responder
  chain is not fully wired — a known issue in some React Native modal presentation paths.

### Decision

Replace `UIToolbar` / `UIBarButtonItem` with a plain `UIView` (`buttonBar`) containing
two `UIButton` instances wired via `UIControlEventTouchUpInside`. Anchor `buttonBar` to
`view.bottomAnchor constant:-34` (not `safeAreaLayoutGuide.bottomAnchor`).

```objc
// NOT this — UIBarButtonItem can silently fail in RN modal context:
UIToolbar *toolbar = ...;
UIBarButtonItem *confirm = [[UIBarButtonItem alloc] initWithTitle:@"사진 사용" ...];

// This — direct UIButton TouchUpInside, always fires:
UIView *buttonBar = [UIView new];
UIButton *confirmBtn = [UIButton buttonWithType:UIButtonTypeSystem];
[confirmBtn addTarget:self action:@selector(handleConfirm)
    forControlEvents:UIControlEventTouchUpInside];

// NOT safeAreaLayoutGuide (can report 0 in RN presentation path):
[buttonBar.bottomAnchor constraintEqualToAnchor:self.view.bottomAnchor constant:-34]
```

`-34` is the height of the home-indicator gesture zone on Face ID iPhones. Hard-coding
this offset is intentional: the safe area guide is the correct mechanism in theory, but
its value is unreliable in this presentation context. `34 pt` has been stable across all
Face ID iPhone models to date.

### Consequences

- Button taps are reliable regardless of React Native's presentation path.
- The `-34` constant must be revisited if Apple changes the home-indicator zone height.
- `UIToolbar` visual styling is replaced with a custom dark bar (`colorWithWhite:0.12`),
  which is acceptable for an ephemeral scan-editing UI.

---

## Decision 2: subview ordering for hit-test priority

### Problem

After adding the button bar below the handles in `viewDidLoad`, handle circles positioned
near the bottom of the image were absorbing taps intended for the button bar.

UIKit's `hitTest:withEvent:` iterates subviews in **reverse** order (last-added subview
checked first). Handles added after `buttonBar` would intercept taps in the overlap area.

### Decision

Add all four handle subviews **before** `buttonBar` in `viewDidLoad`. `buttonBar` is
added last, giving it the highest hit-test priority.

```objc
// viewDidLoad order:
// 1. imageView
// 2. overlayLayer  (CALayer, not UIView — no hit-test participation)
// 3. handles[0..3]   ← added first, lower hit-test priority
// 4. buttonBar        ← added last, highest hit-test priority
```

### Consequences

- Handle circles that visually overlap the button bar area cannot block button taps.
- Handle dragging still works correctly everywhere above the button bar.

---

## Decision 3: VNImageRequestHandler — use initWithCGImage:orientation: not initWithCIImage:

### Problem

Rectangle auto-detection was placing the initial corner handles in wrong positions on
portrait photos taken on iPhone. The auto-detected corners were offset from the actual
document edges, forcing the user to manually adjust all four handles.

Root cause: `VNImageRequestHandler initWithCIImage:` ignores any orientation transform
embedded in the `CIImage`. For a portrait photo (raw data stored as landscape with EXIF
orientation = `.right`), Vision processed the raw landscape pixels and returned
normalized coordinates relative to the landscape dimensions. The code then multiplied
by `image.size.width/height` (portrait logical dimensions), producing wrong pixel
coordinates.

```objc
// BEFORE — Vision sees raw landscape pixels; coordinates do not match image.size:
CIImage *ciImage = [[CIImage alloc] initWithImage:image];  // orientation embedded but ignored by VN
VNImageRequestHandler *handler =
    [[VNImageRequestHandler alloc] initWithCIImage:ciImage options:@{}];

// AFTER — explicit orientation; Vision processes portrait-oriented pixels:
VNImageRequestHandler *handler =
    [[VNImageRequestHandler alloc] initWithCGImage:image.CGImage
                                       orientation:CIOrientationFromUIOrientation(image.imageOrientation)
                                           options:@{}];
```

With `initWithCGImage:orientation:`, Vision applies the orientation before detection and
returns normalized coordinates in the logically-oriented space. Multiplying by
`image.size.width/height` then gives correct CIImage pixel coordinates.

### Consequences

- Auto-detected handles are placed accurately on portrait photos.
- Users need fewer (or no) manual adjustments for well-lit, flat receipts.
- The fix adds a `CIOrientationFromUIOrientation` helper that maps
  `UIImageOrientation` → `CGImagePropertyOrientation` by name correspondence
  (e.g., `.right` → `kCGImagePropertyOrientationRight`).

---

## Decision 4: CIPerspectiveCorrection input — use initWithCGImage: + imageByApplyingOrientation:

### Problem

Even after the user manually adjusted the corner handles to the correct positions,
the perspective-corrected crop included content from outside the selected quadrilateral.
Content from the right side of the original landscape image appeared at the top of the
cropped result.

Root cause: `[CIImage initWithImage:]` embeds the UIImage's orientation as a lazy
transform but does not bake it into the pixel data. `CIPerspectiveCorrection` (and
some other CIFilters) can operate on the **raw (un-oriented) pixel buffer** rather than
the logically-oriented view. Corner coordinates stored in `_corners` are in portrait
space (derived from `_sourceImage.size`), but the filter receives them as if the image
were landscape — a classic axes-swap bug.

```objc
// BEFORE — initWithImage: orientation may not be applied to filter input:
CIImage *ciInput = [[CIImage alloc] initWithImage:self.sourceImage];

// AFTER — orientation baked in before the filter sees the image:
CGImagePropertyOrientation exifOrientation =
    CIOrientationFromUIOrientation(self.sourceImage.imageOrientation);
CIImage *ciInput = [[[CIImage alloc] initWithCGImage:self.sourceImage.CGImage]
    imageByApplyingOrientation:exifOrientation];
// Normalize to (0,0) in case the rotation produces a non-zero extent origin:
CGRect ext = ciInput.extent;
if (ext.origin.x != 0 || ext.origin.y != 0) {
    ciInput = [ciInput imageByApplyingTransform:
        CGAffineTransformMakeTranslation(-ext.origin.x, -ext.origin.y)];
}
```

`imageByApplyingOrientation:` bakes the rotation into the CIImage's coordinate space.
The resulting `ciInput.extent.size` matches `_sourceImage.size` (portrait), so
`_corners` coordinates are valid.

### Consequences

- Perspective correction output matches the user-selected quadrilateral exactly.
- `initWithCGImage:` bypasses UIImage's lazy orientation — the raw CGImage is always
  available on `UIImage.CGImage` without a re-decode.
- The origin normalization step is a defensive guard; in practice `imageByApplyingOrientation:`
  produces a `(0,0)`-origin extent for all standard EXIF orientations on iOS.
