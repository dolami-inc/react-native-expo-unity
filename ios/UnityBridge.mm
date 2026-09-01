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
#import <QuartzCore/CATransaction.h>

#ifdef DEBUG
#include <mach-o/ldsyms.h>
#endif

@interface UnityBridge () <NativeCallsProtocol, UnityFrameworkListener>

@property (nonatomic, strong, nullable) UnityFramework *ufwInternal;
// Buffers Unity → RN messages emitted while no onMessage sink is attached.
@property (nonatomic, strong) NSMutableArray<NSString *> *pendingMessages;

@end

// Cap on buffered Unity → RN messages held while no sink is attached.
static const NSUInteger kMaxPendingMessages = 128;

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

    // Register the Unity -> native call handler BEFORE booting Unity.
    //
    // runEmbeddedWithArgc: loads the first scene synchronously, so the scene's
    // Awake/Start can call sendMessageToMobileApp() before it returns. That
    // extern "C" trampoline (Assets/Plugins/iOS/NativeCallProxy.mm) forwards to
    // a global `api` that is nil until this call, and a message sent to nil is
    // silently discarded — it never reaches -sendMessageToMobileApp: below, so
    // the pending-message buffer cannot rescue it either.
    //
    // Unity 6000.3 boots fast enough that RNBridgeReceiver.Start() lands inside
    // runEmbeddedWithArgc:, which made a one-shot `unity_ready` disappear 100%
    // of the time (dolami-inc/react-native-expo-unity#5). Registering first
    // turns that window into a buffered message instead of a lost one.
    [NSClassFromString(@"FrameworkLibAPI") registerAPIforNativeCalls:self];

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

    // Register for unload/quit notifications
    [ufw registerFrameworkListener:self];

    self.ufwInternal = ufw;

    // Hide Unity's window — we embed its rootView in our own view.
    // Wrap in CATransaction to commit layer tree changes atomically
    // before other main queue callbacks (e.g. SDWebImage) can fire
    // and attempt CA commits on a stale rendering context.
    [CATransaction begin];
    UIWindow *unityWindow = [ufw appController].window;
    if (unityWindow) {
        unityWindow.hidden = YES;
        unityWindow.userInteractionEnabled = NO;
    }
    [CATransaction commit];

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
    [self discardPendingMessages];
    if (![self isInitialized]) return;
    UIWindow *mainWindow = [[[UIApplication sharedApplication] delegate] window];
    if (mainWindow) [mainWindow makeKeyAndVisible];
    [self.ufwInternal unloadApplication];
    NSLog(@"[ExpoUnity] unloadApplication called");
}

// MARK: - NativeCallsProtocol (Unity → RN)

// View-bound sink. Set when an ExpoUnityView mounts and nil'd when it is
// removed (removeFromSuperview). Override the setter so that any messages
// buffered while no sink was attached are flushed — in arrival order — the
// moment a sink is (re)attached, instead of being silently dropped.
@synthesize onMessage = _onMessage;

- (UnityMessageCallback)onMessage {
    @synchronized (self) {
        return _onMessage;
    }
}

- (void)setOnMessage:(UnityMessageCallback)onMessage {
    UnityMessageCallback sink = [onMessage copy];

    // Install the sink and drain under the same lock that -sendMessageToMobileApp:
    // holds, so a message cannot be classified as sink-less and then appended
    // *after* this flush has already run (which would strand it until the next
    // attach). Same invariant as the Android bridge.
    NSArray<NSString *> *drained = nil;
    @synchronized (self) {
        _onMessage = sink;
        if (sink && self.pendingMessages.count > 0) {
            drained = [self.pendingMessages copy];
            [self.pendingMessages removeAllObjects];
        }
    }

    if (drained.count > 0) {
        NSLog(@"[ExpoUnity] flushing %lu message(s) buffered before sink attach", (unsigned long)drained.count);
        dispatch_async(dispatch_get_main_queue(), ^{
            for (NSString *message in drained) {
                sink(message);
            }
        });
    }
}

- (void)sendMessageToMobileApp:(NSString *)message {
    // Unity may emit from a scripting thread while the sink is installed on the
    // main thread, so the "is a sink attached?" check and the buffer write must
    // be mutually exclusive with -setOnMessage:. The sink only hops to the main
    // queue, so calling it under the lock is cheap and cannot deadlock.
    @synchronized (self) {
        UnityMessageCallback sink = _onMessage;
        if (sink) {
            sink(message);
            return;
        }
        // No sink attached — buffer instead of dropping. Flushed on (re)attach.
        if (!self.pendingMessages) {
            self.pendingMessages = [NSMutableArray array];
        }
        if (self.pendingMessages.count >= kMaxPendingMessages) {
            [self.pendingMessages removeObjectAtIndex:0];
        }
        [self.pendingMessages addObject:message];
    }
}

// Buffered messages describe a Unity process that is going away — replaying
// them into the next one would, for a one-shot event such as `unity_ready`,
// promote readiness before the new engine has loaded its first scene.
- (void)discardPendingMessages {
    @synchronized (self) {
        if (self.pendingMessages.count == 0) return;
        NSLog(@"[ExpoUnity] discarding %lu buffered message(s) from the unloaded Unity session",
              (unsigned long)self.pendingMessages.count);
        [self.pendingMessages removeAllObjects];
    }
}

// MARK: - UnityFrameworkListener

- (void)unityDidUnload:(NSNotification *)notification {
    NSLog(@"[ExpoUnity] unityDidUnload notification received");
    // Also drop anything Unity emitted between the unload request and here.
    [self discardPendingMessages];
    [self.ufwInternal unregisterFrameworkListener:self];
    self.ufwInternal = nil;
    NSLog(@"[ExpoUnity] ufwInternal set to nil, ready for re-initialize");
}

- (void)unityDidQuit:(NSNotification *)notification {
    NSLog(@"[ExpoUnity] Unity did quit");
    [self discardPendingMessages];
    [self.ufwInternal unregisterFrameworkListener:self];
    self.ufwInternal = nil;
}

@end

#endif // !TARGET_OS_SIMULATOR
