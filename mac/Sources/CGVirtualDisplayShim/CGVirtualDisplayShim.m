#import "CGVirtualDisplayShim.h"

// Private CoreGraphics interfaces (macOS 11+). Only declared for typing: classes are looked up at runtime
// with NSClassFromString so a missing API is a nil, not a launch-time link failure.
@interface CGVirtualDisplayDescriptor : NSObject
@property (retain, nonatomic) dispatch_queue_t queue;
@property (retain, nonatomic) NSString *name;
@property (nonatomic) unsigned int maxPixelsHigh;
@property (nonatomic) unsigned int maxPixelsWide;
@property (nonatomic) CGSize sizeInMillimeters;
@property (nonatomic) unsigned int serialNum;
@property (nonatomic) unsigned int productID;
@property (nonatomic) unsigned int vendorID;
@property (copy, nonatomic) void (^terminationHandler)(id, id);
@end

@interface CGVirtualDisplayMode : NSObject
- (instancetype)initWithWidth:(unsigned int)width height:(unsigned int)height refreshRate:(double)refreshRate;
@end

@interface CGVirtualDisplaySettings : NSObject
@property (retain, nonatomic) NSArray *modes;
@property (nonatomic) unsigned int hiDPI;
@end

@interface CGVirtualDisplay : NSObject
- (instancetype)initWithDescriptor:(CGVirtualDisplayDescriptor *)descriptor;
- (BOOL)applySettings:(CGVirtualDisplaySettings *)settings;
@property (readonly, nonatomic) unsigned int displayID;
@end

NSObject *CVDCreate(unsigned pointsWide, unsigned pointsHigh, BOOL hiDPI, NSString *name,
                    CGDirectDisplayID *outID, NSString **error) {
    Class descC = NSClassFromString(@"CGVirtualDisplayDescriptor");
    Class modeC = NSClassFromString(@"CGVirtualDisplayMode");
    Class setC = NSClassFromString(@"CGVirtualDisplaySettings");
    Class dispC = NSClassFromString(@"CGVirtualDisplay");
    if (!descC || !modeC || !setC || !dispC) {
        if (error) *error = @"CGVirtualDisplay classes not found";
        return nil;
    }
    unsigned scale = hiDPI ? 2 : 1;
    CGVirtualDisplayDescriptor *desc = [[descC alloc] init];
    desc.queue = dispatch_get_global_queue(QOS_CLASS_DEFAULT, 0);
    desc.name = name;
    desc.maxPixelsWide = pointsWide * scale;
    desc.maxPixelsHigh = pointsHigh * scale;
    desc.sizeInMillimeters = CGSizeMake(597, 336); // ~27" 16:9
    desc.vendorID = 0x4D43; // arbitrary, non-zero
    desc.productID = 0x0001;
    desc.serialNum = 0x0001;
    desc.terminationHandler = ^(id a, id b) { NSLog(@"virtual display terminated"); };

    CGVirtualDisplay *display = [[dispC alloc] initWithDescriptor:desc];
    if (!display || display.displayID == 0) {
        if (error) *error = @"initWithDescriptor failed";
        return nil;
    }
    CGVirtualDisplaySettings *settings = [[setC alloc] init];
    settings.hiDPI = hiDPI ? 1 : 0;
    settings.modes = @[[[modeC alloc] initWithWidth:pointsWide height:pointsHigh refreshRate:60]];
    if (![display applySettings:settings]) {
        if (error) *error = @"applySettings failed";
        return nil;
    }
    if (outID) *outID = display.displayID;
    return display;
}
