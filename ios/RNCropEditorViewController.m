#import <UIKit/UIKit.h>
#import <CoreImage/CoreImage.h>

static const CGFloat kHandleRadius = 16.0;
static const NSInteger kTopLeft     = 0;
static const NSInteger kTopRight    = 1;
static const NSInteger kBottomRight = 2;
static const NSInteger kBottomLeft  = 3;

NS_ASSUME_NONNULL_BEGIN

@interface RNCropEditorViewController : UIViewController
- (instancetype)initWithImage:(UIImage *)image
                      corners:(nullable NSArray<NSValue *> *)corners
                   completion:(void (^)(CGImageRef _Nullable cgImage))completion;
@end

NS_ASSUME_NONNULL_END

@interface RNCropEditorViewController ()
@property (nonatomic, strong) UIImage              *sourceImage;
@property (nonatomic, strong) NSMutableArray<NSValue *> *corners; // CGPoint, CIImage coords
@property (nonatomic, copy)   void (^completion)(CGImageRef _Nullable);

@property (nonatomic, strong) UIImageView          *imageView;
@property (nonatomic, strong) NSMutableArray<UIView *> *handles;
@property (nonatomic, strong) CAShapeLayer         *overlayLayer;
@end

@implementation RNCropEditorViewController

- (instancetype)initWithImage:(UIImage *)image
                      corners:(nullable NSArray<NSValue *> *)corners
                   completion:(void (^)(CGImageRef _Nullable))completion {
    self = [super initWithNibName:nil bundle:nil];
    if (self) {
        _sourceImage = image;
        _completion  = completion;

        CGFloat W = image.size.width;
        CGFloat H = image.size.height;

        if (corners && corners.count == 4) {
            _corners = [corners mutableCopy];
        } else {
            // Default to full image corners (CIImage bottom-left origin)
            _corners = [@[
                [NSValue valueWithCGPoint:CGPointMake(0, H)],  // topLeft
                [NSValue valueWithCGPoint:CGPointMake(W, H)],  // topRight
                [NSValue valueWithCGPoint:CGPointMake(W, 0)],  // bottomRight
                [NSValue valueWithCGPoint:CGPointMake(0, 0)],  // bottomLeft
            ] mutableCopy];
        }
    }
    return self;
}

- (void)viewDidLoad {
    [super viewDidLoad];
    self.view.backgroundColor = UIColor.blackColor;

    _imageView = [[UIImageView alloc] initWithImage:self.sourceImage];
    _imageView.contentMode = UIViewContentModeScaleAspectFit;
    _imageView.translatesAutoresizingMaskIntoConstraints = NO;
    [self.view addSubview:_imageView];

    _overlayLayer = [CAShapeLayer layer];
    _overlayLayer.fillColor   = [UIColor colorWithRed:0 green:0.5 blue:1 alpha:0.2].CGColor;
    _overlayLayer.strokeColor = [UIColor colorWithRed:0 green:0.5 blue:1 alpha:0.9].CGColor;
    _overlayLayer.lineWidth   = 2.0;
    [self.view.layer addSublayer:_overlayLayer];

    // Handles are added BEFORE the toolbar so that hitTest:withEvent: (which iterates
    // subviews in reverse) checks the toolbar first. This prevents handle circles that
    // fall near the bottom of the image from absorbing taps on toolbar buttons.
    _handles = [NSMutableArray new];
    for (NSInteger i = 0; i < 4; i++) {
        UIView *handle = [[UIView alloc] initWithFrame:CGRectMake(0, 0, kHandleRadius*2, kHandleRadius*2)];
        handle.backgroundColor = UIColor.whiteColor;
        handle.layer.cornerRadius = kHandleRadius;
        handle.layer.borderColor  = UIColor.systemBlueColor.CGColor;
        handle.layer.borderWidth  = 2.0;
        handle.tag = i;
        UIPanGestureRecognizer *pan = [[UIPanGestureRecognizer alloc]
            initWithTarget:self action:@selector(handlePan:)];
        [handle addGestureRecognizer:pan];
        [self.view addSubview:handle];
        [_handles addObject:handle];
    }

    // Plain UIView + UIButton bar — added LAST for highest hit-test priority.
    // UIToolbar + UIBarButtonItem was replaced here because UIBarButtonItem target-action
    // routing can silently fail when the presented VC's safe area insets are mis-reported
    // (a known issue in some RN presentation paths). UIButton fires TouchUpInside directly.
    UIView *buttonBar = [UIView new];
    buttonBar.translatesAutoresizingMaskIntoConstraints = NO;
    buttonBar.backgroundColor = [UIColor colorWithWhite:0.12 alpha:1.0];

    UIButton *cancelBtn = [UIButton buttonWithType:UIButtonTypeSystem];
    [cancelBtn setTitle:@"취소" forState:UIControlStateNormal];
    [cancelBtn setTitleColor:UIColor.systemBlueColor forState:UIControlStateNormal];
    cancelBtn.titleLabel.font = [UIFont systemFontOfSize:17];
    cancelBtn.translatesAutoresizingMaskIntoConstraints = NO;
    [cancelBtn addTarget:self action:@selector(handleCancel)
        forControlEvents:UIControlEventTouchUpInside];

    UIButton *confirmBtn = [UIButton buttonWithType:UIButtonTypeSystem];
    [confirmBtn setTitle:@"사진 사용" forState:UIControlStateNormal];
    [confirmBtn setTitleColor:UIColor.systemBlueColor forState:UIControlStateNormal];
    confirmBtn.titleLabel.font = [UIFont boldSystemFontOfSize:17];
    confirmBtn.translatesAutoresizingMaskIntoConstraints = NO;
    [confirmBtn addTarget:self action:@selector(handleConfirm)
        forControlEvents:UIControlEventTouchUpInside];

    [buttonBar addSubview:cancelBtn];
    [buttonBar addSubview:confirmBtn];
    [self.view addSubview:buttonBar];

    // Anchor to view.bottomAnchor - 34 pt (home-indicator zone height on Face ID devices).
    // safeAreaLayoutGuide.bottomAnchor is not used here because it can report 0 in some
    // RN modal presentation paths, pushing the bar into the system gesture zone.
    [NSLayoutConstraint activateConstraints:@[
        [_imageView.topAnchor constraintEqualToAnchor:self.view.safeAreaLayoutGuide.topAnchor],
        [_imageView.leadingAnchor constraintEqualToAnchor:self.view.leadingAnchor],
        [_imageView.trailingAnchor constraintEqualToAnchor:self.view.trailingAnchor],
        [_imageView.bottomAnchor constraintEqualToAnchor:buttonBar.topAnchor],

        [buttonBar.leadingAnchor constraintEqualToAnchor:self.view.leadingAnchor],
        [buttonBar.trailingAnchor constraintEqualToAnchor:self.view.trailingAnchor],
        [buttonBar.bottomAnchor constraintEqualToAnchor:self.view.bottomAnchor constant:-34],
        [buttonBar.heightAnchor constraintEqualToConstant:50],

        [cancelBtn.leadingAnchor constraintEqualToAnchor:buttonBar.leadingAnchor constant:20],
        [cancelBtn.centerYAnchor constraintEqualToAnchor:buttonBar.centerYAnchor],
        [cancelBtn.heightAnchor constraintEqualToConstant:44],

        [confirmBtn.trailingAnchor constraintEqualToAnchor:buttonBar.trailingAnchor constant:-20],
        [confirmBtn.centerYAnchor constraintEqualToAnchor:buttonBar.centerYAnchor],
        [confirmBtn.heightAnchor constraintEqualToConstant:44],
    ]];
}

- (void)viewDidLayoutSubviews {
    [super viewDidLayoutSubviews];
    _overlayLayer.frame = self.view.bounds;
    [self updateHandlePositions];
    [self updateOverlayPath];
}

- (CGRect)imageRectInView {
    CGSize ivSize    = _imageView.bounds.size;
    CGSize imgSize   = _sourceImage.size;
    CGFloat ivAspect  = ivSize.width  / ivSize.height;
    CGFloat imgAspect = imgSize.width / imgSize.height;
    CGRect r;
    if (ivAspect > imgAspect) {
        CGFloat h = ivSize.height;
        CGFloat w = h * imgAspect;
        r = CGRectMake((ivSize.width - w) / 2 + _imageView.frame.origin.x,
                       _imageView.frame.origin.y, w, h);
    } else {
        CGFloat w = ivSize.width;
        CGFloat h = w / imgAspect;
        r = CGRectMake(_imageView.frame.origin.x,
                       (ivSize.height - h) / 2 + _imageView.frame.origin.y, w, h);
    }
    return r;
}

- (CGPoint)viewPointFromCIPoint:(CGPoint)ci {
    CGRect rect = [self imageRectInView];
    CGFloat x = rect.origin.x + (ci.x / _sourceImage.size.width)  * rect.size.width;
    CGFloat y = rect.origin.y + (1.0 - ci.y / _sourceImage.size.height) * rect.size.height;
    return CGPointMake(x, y);
}

- (CGPoint)ciPointFromViewPoint:(CGPoint)view {
    CGRect rect = [self imageRectInView];
    CGFloat ciX = ((view.x - rect.origin.x) / rect.size.width)  * _sourceImage.size.width;
    CGFloat ciY = (1.0 - (view.y - rect.origin.y) / rect.size.height) * _sourceImage.size.height;
    return CGPointMake(ciX, ciY);
}

- (void)updateHandlePositions {
    for (NSInteger i = 0; i < 4; i++) {
        CGPoint ci   = [_corners[i] CGPointValue];
        CGPoint view = [self viewPointFromCIPoint:ci];
        _handles[i].center = view;
    }
}

- (void)updateOverlayPath {
    if (_handles.count < 4) return;
    UIBezierPath *path = [UIBezierPath bezierPath];
    [path moveToPoint:_handles[kTopLeft].center];
    [path addLineToPoint:_handles[kTopRight].center];
    [path addLineToPoint:_handles[kBottomRight].center];
    [path addLineToPoint:_handles[kBottomLeft].center];
    [path closePath];
    _overlayLayer.path = path.CGPath;
}

- (void)handlePan:(UIPanGestureRecognizer *)pan {
    UIView *handle = pan.view;
    CGPoint translation = [pan translationInView:self.view];

    CGPoint newCenter = CGPointMake(handle.center.x + translation.x,
                                    handle.center.y + translation.y);

    CGRect rect = [self imageRectInView];
    newCenter.x = MAX(rect.origin.x, MIN(rect.origin.x + rect.size.width,  newCenter.x));
    newCenter.y = MAX(rect.origin.y, MIN(rect.origin.y + rect.size.height, newCenter.y));

    handle.center = newCenter;
    _corners[handle.tag] = [NSValue valueWithCGPoint:[self ciPointFromViewPoint:newCenter]];
    [pan setTranslation:CGPointZero inView:self.view];
    [self updateOverlayPath];
}

- (void)handleCancel {
    __weak typeof(self) weakSelf = self;
    [self dismissViewControllerAnimated:YES completion:^{
        __strong typeof(weakSelf) strongSelf = weakSelf;
        if (strongSelf.completion) strongSelf.completion(nil);
    }];
}

- (void)handleConfirm {
    CGPoint tl = [_corners[kTopLeft]     CGPointValue];
    CGPoint tr = [_corners[kTopRight]    CGPointValue];
    CGPoint br = [_corners[kBottomRight] CGPointValue];
    CGPoint bl = [_corners[kBottomLeft]  CGPointValue];

    CIImage *ciInput  = [[CIImage alloc] initWithImage:self.sourceImage];
    CIFilter *filter  = [CIFilter filterWithName:@"CIPerspectiveCorrection"];
    [filter setValue:ciInput forKey:kCIInputImageKey];
    [filter setValue:[CIVector vectorWithX:tl.x Y:tl.y] forKey:@"inputTopLeft"];
    [filter setValue:[CIVector vectorWithX:tr.x Y:tr.y] forKey:@"inputTopRight"];
    [filter setValue:[CIVector vectorWithX:br.x Y:br.y] forKey:@"inputBottomRight"];
    [filter setValue:[CIVector vectorWithX:bl.x Y:bl.y] forKey:@"inputBottomLeft"];

    CIImage *output = filter.outputImage;
    if (!output) {
        [self dismissViewControllerAnimated:YES completion:^{
            if (self.completion) self.completion(nil);
        }];
        return;
    }

    // Capture completion before dismissal so the VC can be released by UIKit
    // without waiting for the background render to finish.
    void (^completion)(CGImageRef) = self.completion;
    self.completion = nil;

    // Dismiss immediately so the tap feels responsive. CIContext rendering
    // (createCGImage:fromRect:) is expensive on full-resolution photos and must
    // not run on the main thread.
    [self dismissViewControllerAnimated:YES completion:^{
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
            static CIContext *ctx;
            static dispatch_once_t once;
            dispatch_once(&once, ^{ ctx = [CIContext context]; });
            CGImageRef cropped = [ctx createCGImage:output fromRect:output.extent];
            if (completion) completion(cropped);
            else if (cropped) CGImageRelease(cropped);
        });
    }];
}

@end
