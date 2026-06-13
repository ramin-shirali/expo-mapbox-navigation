import { requireNativeView } from "expo";
import * as React from "react";

import type { MapboxNavigationViewProps, RouteProgress, WaypointArrival } from "./ExpoMapboxNavigation.types";

/**
 * The native turn-by-turn view only exists in a custom dev/EAS build with this
 * module compiled in — NOT in Expo Go. We resolve it lazily so importing this
 * package never throws; when it's absent, `isNavigationAvailable` is false and
 * the app falls back to its OS-maps hand-off (see TurnByTurnNav.tsx).
 */
type NativeProps = Omit<
  MapboxNavigationViewProps,
  "onRouteProgress" | "onWaypointArrival" | "onArrival" | "onCancel" | "onReroute" | "onError"
> & {
  onRouteProgress?: (e: { nativeEvent: RouteProgress }) => void;
  onWaypointArrival?: (e: { nativeEvent: WaypointArrival }) => void;
  onArrival?: (e: { nativeEvent: Record<string, never> }) => void;
  onCancel?: (e: { nativeEvent: Record<string, never> }) => void;
  onReroute?: (e: { nativeEvent: Record<string, never> }) => void;
  onError?: (e: { nativeEvent: { message: string } }) => void;
};

let NativeView: React.ComponentType<NativeProps> | null = null;
try {
  NativeView = requireNativeView<NativeProps>("ExpoMapboxNavigation");
} catch {
  NativeView = null;
}

/** True when the native Mapbox Navigation module is compiled into this build. */
export const isNavigationAvailable = NativeView != null;

/**
 * Full-screen, voice-guided turn-by-turn navigation backed by the Mapbox
 * Navigation SDK v3. Renders nothing when the native module is absent — callers
 * should gate on {@link isNavigationAvailable} and provide a fallback.
 *
 * Native events arrive wrapped as `{ nativeEvent }`; this component unwraps them
 * so consumers get clean payloads.
 */
export function MapboxNavigationView({
  onRouteProgress,
  onWaypointArrival,
  onArrival,
  onCancel,
  onReroute,
  onError,
  ...rest
}: MapboxNavigationViewProps) {
  if (!NativeView) return null;
  return (
    <NativeView
      {...rest}
      onRouteProgress={onRouteProgress ? (e) => onRouteProgress(e.nativeEvent) : undefined}
      onWaypointArrival={onWaypointArrival ? (e) => onWaypointArrival(e.nativeEvent) : undefined}
      onArrival={onArrival ? () => onArrival() : undefined}
      onCancel={onCancel ? () => onCancel() : undefined}
      onReroute={onReroute ? () => onReroute() : undefined}
      onError={onError ? (e) => onError(e.nativeEvent) : undefined}
    />
  );
}
