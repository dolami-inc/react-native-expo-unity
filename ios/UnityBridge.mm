#import "UnityBridge.h"

// ------------------------------------------------------------------
// UnityBridge — singleton that owns the UnityFramework lifecycle.
// Called from Swift via @objc interop.
//
// On Simulator, all methods are no-ops because Unity as a Library
// does not support the iOS Simulator target.
// ------------------------------------------------------------------

#if TARGET_OS_SIMULATOR

// MARK: - Simulator stubs

@implementation UnityBridge

static UnityBridge *_shared = nil;

+ (instancetype)shared {
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        _shared = [[UnityBridge alloc] init];
    });
    return _shared;
}

- (BOOL)isInitialized { return NO; }
- (void)initialize { NSLog(@"[ExpoUnity] Unity is not available on iOS Simulator"); }
- (nullable UIView *)unityRootView { return nil; }
- (nullable UIWindow *)unityWindow { return nil; }
- (void)sendMessage:(NSString *)gameObject methodName:(NSString *)methodName message:(NSString *)message {}
- (void)pause:(BOOL)pause {}
- (void)unload {}

@end

#else // !TARGET_OS_SIMULATOR

// MARK: - Device implementation

#import <UnityFramework/UnityFramework.h>
#import <UnityFramework/NativeCallProxy.h>

#ifdef DEBUG
#include <mach-o/ldsyms.h>
#endif

@interface UnityBridge () <NativeCallsProtocol, UnityFrameworkListener>

@property (nonatomic, strong, nullable) UnityFramework *ufwInternal;

@end

@implementation UnityBridge

static UnityBridge *_shared = nil;

+ (instancetype)shared {
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        _shared = [[UnityBridge alloc] init];
    });
    return _shared;
}

- (BOOL)isInitialized {
    return self.ufwInternal != nil && [self.ufwInternal appController] != nil;
}

- (void)initialize {
    if ([self isInitialized]) return;

    NSString *bundlePath = [[NSBundle mainBundle] bundlePath];
    bundlePath = [bundlePath stringByAppendingString:@"/Frameworks/UnityFramework.framework"];

    NSBundle *bundle = [NSBundle bundleWithPath:bundlePath];
    if (![bundle isLoaded]) [bundle load];

    UnityFramework *ufw = [bundle.principalClass getInstance];
    if (![ufw appController]) {
#ifdef DEBUG
        [ufw setExecuteHeader:&_mh_dylib_header];
#else
        [ufw setExecuteHeader:&_mh_execute_header];
#endif
    }

    [ufw setDataBundleId:[bundle.bundleIdentifier cStringUsingEncoding:NSUTF8StringEncoding]];

    // Boot Unity
    NSArray *args = [[NSProcessInfo processInfo] arguments];
    int argc = (int)args.count;
    char **argv = (char **)malloc((argc + 1) * sizeof(char *));
    for (int i = 0; i < argc; i++) {
        argv[i] = strdup([args[i] UTF8String]);
    }
    argv[argc] = NULL;

    [ufw runEmbeddedWithArgc:1 argv:argv appLaunchOpts:nil];
    [ufw appController].quitHandler = ^{ NSLog(@"[ExpoUnity] Unity quit handler called"); };

    // Register for callbacks
    [ufw registerFrameworkListener:self];
    [NSClassFromString(@"FrameworkLibAPI") registerAPIforNativeCalls:self];

    self.ufwInternal = ufw;

    // Hide Unity's window — we embed its rootView in our own view
    UIWindow *unityWindow = [ufw appController].window;
    if (unityWindow) {
        unityWindow.hidden = YES;
        unityWindow.userInteractionEnabled = NO;
    }

    NSLog(@"[ExpoUnity] Unity initialized");
}

- (nullable UIView *)unityRootView {
    if (![self isInitialized]) return nil;
    return [self.ufwInternal appController].rootView;
}

- (nullable UIWindow *)unityWindow {
    if (![self isInitialized]) return nil;
    return [self.ufwInternal appController].window;
}

- (void)sendMessage:(NSString *)gameObject
         methodName:(NSString *)methodName
            message:(NSString *)message {
    if (![self isInitialized]) return;
    dispatch_async(dispatch_get_main_queue(), ^{
        [self.ufwInternal sendMessageToGOWithName:[gameObject UTF8String]
                             functionName:[methodName UTF8String]
                                  message:[message UTF8String]];
    });
}

- (void)pause:(BOOL)pause {
    if (![self isInitialized]) return;
    dispatch_async(dispatch_get_main_queue(), ^{
        [self.ufwInternal pause:pause];
    });
}

- (void)unload {
    NSLog(@"[ExpoUnity] unload called, isInitialized=%d", [self isInitialized]);
    if (![self isInitialized]) return;
    UIWindow *mainWindow = [[[UIApplication sharedApplication] delegate] window];
    if (mainWindow) [mainWindow makeKeyAndVisible];
    [self.ufwInternal unloadApplication];
    NSLog(@"[ExpoUnity] unloadApplication called");
}

// MARK: - NativeCallsProtocol (Unity → RN)

- (void)sendMessageToMobileApp:(NSString *)message {
    if (self.onMessage) {
        self.onMessage(message);
    }
}

// MARK: - UnityFrameworkListener

- (void)unityDidUnload:(NSNotification *)notification {
    NSLog(@"[ExpoUnity] unityDidUnload notification received");
    [self.ufwInternal unregisterFrameworkListener:self];
    self.ufwInternal = nil;
    NSLog(@"[ExpoUnity] ufwInternal set to nil, ready for re-initialize");
}

- (void)unityDidQuit:(NSNotification *)notification {
    NSLog(@"[ExpoUnity] Unity did quit");
    [self.ufwInternal unregisterFrameworkListener:self];
    self.ufwInternal = nil;
}

@end

#endif // !TARGET_OS_SIMULATOR
