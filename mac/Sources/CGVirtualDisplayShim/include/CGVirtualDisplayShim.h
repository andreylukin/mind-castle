#import <Foundation/Foundation.h>
#import <CoreGraphics/CoreGraphics.h>

NS_ASSUME_NONNULL_BEGIN

/// Creates a virtual display via CoreGraphics' private CGVirtualDisplay classes (what DeskPad/BetterDisplay use).
/// The display lives as long as the returned object is retained; release it to destroy the display.
/// `pointsWide`/`pointsHigh` is the mode size; with `hiDPI` the backing store is 2x that in pixels.
/// Returns nil (and logs the reason to `error`) if the classes are missing or creation fails.
NSObject *_Nullable CVDCreate(unsigned pointsWide, unsigned pointsHigh, BOOL hiDPI, NSString *name,
                              CGDirectDisplayID *outID, NSString *_Nullable *_Nullable error);

NS_ASSUME_NONNULL_END
