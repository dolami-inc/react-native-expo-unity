import { requireNativeModule } from "expo";

const ExpoUnityModule = requireNativeModule("ExpoUnity");

/**
 * Send a message to a Unity GameObject.
 */
export function postMessage(
  gameObject: string,
  methodName: string,
  message: string
): void {
  ExpoUnityModule.postMessage(gameObject, methodName, message);
}

/**
 * Pause Unity rendering and execution.
 */
export function pauseUnity(): void {
  ExpoUnityModule.pauseUnity(true);
}

/**
 * Resume Unity rendering and execution.
 */
export function resumeUnity(): void {
  ExpoUnityModule.pauseUnity(false);
}

/**
 * Unload Unity and free memory.
 * After this, Unity must be re-initialized (remount the view).
 */
export function unloadUnity(): void {
  ExpoUnityModule.unloadUnity();
}

/**
 * Check if Unity is currently initialized.
 */
export function isInitialized(): boolean {
  return ExpoUnityModule.isInitialized();
}
