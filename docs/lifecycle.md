# Lifecycle Deep Dive

## Unity States

```
Not Initialized ──mount──→ Running ──pause──→ Paused
                              ↑                  │
                              └────resume─────────┘
                              │
                           unload
                              │
                              ↓
                          Unloaded ──mount──→ Running (fresh state)
```

- **Pause/Resume** — cheap, instant, state preserved (avatar, scene, camera position)
- **Unload** — frees most memory, but ~80-180MB retained by Unity. Next mount = full reinit (~1-2s), state reset.

## Navigation Scenarios

Stack: `Gallery → AR Camera → Add Caption`

### Gallery → AR Camera (push)

| What happens | Detail |
|---|---|
| AR Camera mounts | `<UnityView />` renders |
| Unity initializes | First time: ~1-2s. If paused (not unloaded): instant resume |

### AR Camera → Add Caption (push)

| What happens | Detail |
|---|---|
| AR Camera stays mounted | React Navigation keeps stack screens alive |
| AR Camera loses focus | `useFocusEffect` cleanup fires |
| You call `pauseUnity()` | Unity freezes, state preserved |

### Add Caption → AR Camera (back)

| What happens | Detail |
|---|---|
| AR Camera regains focus | `useFocusEffect` fires |
| You call `resumeUnity()` | Unity resumes instantly, all state preserved |

### AR Camera → Gallery (back)

| What happens | Detail |
|---|---|
| AR Camera unmounts | Component removed from stack |
| `autoUnloadOnUnmount=true` | Unity unloads, memory freed, state reset |
| `autoUnloadOnUnmount=false` | Unity pauses only, state preserved, memory stays |

## Patterns

### Pattern A: Fresh state every time

Unity resets when user leaves. Safest, but slower re-entry.

```tsx
<UnityView
  ref={unityRef}
  style={{ flex: 1 }}
  autoUnloadOnUnmount={true}  // default
  onUnityMessage={handleMessage}
/>
```

```tsx
useFocusEffect(
  useCallback(() => {
    unityRef.current?.resumeUnity();
    return () => unityRef.current?.pauseUnity();
  }, [])
);
```

### Pattern B: Keep state alive

Unity stays in memory for instant re-entry. Better UX, higher memory.

```tsx
<UnityView
  ref={unityRef}
  style={{ flex: 1 }}
  autoUnloadOnUnmount={false}  // pause instead of unload
  onUnityMessage={handleMessage}
/>
```

```tsx
useFocusEffect(
  useCallback(() => {
    unityRef.current?.resumeUnity();
    return () => unityRef.current?.pauseUnity();
  }, [])
);
```

### Pattern C: Force reset on blur

Guarantee fresh state even if the component doesn't unmount.

```tsx
useFocusEffect(
  useCallback(() => {
    unityRef.current?.resumeUnity();
    return () => unityRef.current?.unloadUnity();  // force unload
  }, [])
);
```

## Memory

| State | Typical memory |
|---|---|
| App without Unity | ~100-200MB |
| Unity running | ~200-500MB+ (depends on scene/assets) |
| Unity paused | Same as running (frozen in RAM) |
| Unity unloaded | ~80-180MB retained (Unity limitation) |

**Paused Unity does not use CPU/GPU.** No battery drain. Only concern is memory pressure — iOS may kill your app if system memory is low.

## Auto vs Manual

| Behavior | Handled by | You need to |
|---|---|---|
| Initialize on mount | Auto | Nothing |
| Unload/pause on unmount | Auto (configurable via `autoUnloadOnUnmount`) | Nothing |
| App background/foreground | Auto (pause/resume) | Nothing |
| Screen focus/blur | **Manual** | Use `useFocusEffect` |
| Force unload mid-session | **Manual** | Call `unloadUnity()` |
