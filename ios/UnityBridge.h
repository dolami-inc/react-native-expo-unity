#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

typedef void (^UnityMessageCallback)(NSString * _Nonnull message);

@interface UnityBridge : NSObject

@property (nonatomic, strong, nullable) id ufw;
@property (nonatomic, copy, nullable) UnityMessageCallback onMessage;

+ (instancetype)shared;

- (BOOL)isInitialized;
- (void)initialize;
- (void)sendMessage:(NSString *)gameObject
         methodName:(NSString *)methodName
            message:(NSString *)message;
- (void)pause:(BOOL)pause;
- (void)unload;
- (nullable UIView *)unityRootView;
- (nullable UIWindow *)unityWindow;

@end

NS_ASSUME_NONNULL_END
