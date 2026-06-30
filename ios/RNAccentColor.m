#import "RNAccentColor.h"

@implementation RNAccentColor

+ (UIColor *)cropAccent {
    static UIColor *color;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        UIColor *light = [UIColor colorWithRed:0x98 / 255.0
                                         green:0x04 / 255.0
                                          blue:0xF9 / 255.0
                                         alpha:1.0];
        UIColor *dark = [UIColor colorWithRed:0xB9 / 255.0
                                        green:0x68 / 255.0
                                         blue:0xFF / 255.0
                                        alpha:1.0];
        color = [UIColor colorWithDynamicProvider:^UIColor *(UITraitCollection *traits) {
            return traits.userInterfaceStyle == UIUserInterfaceStyleDark ? dark : light;
        }];
    });
    return color;
}

@end
