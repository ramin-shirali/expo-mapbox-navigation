import { requireNativeModule } from "expo";

export { MapboxNavigationView, isNavigationAvailable } from "./ExpoMapboxNavigationView";
export type {
  MapboxNavigationViewProps,
  RouteProgress,
  NavigationProfile,
  NavigationTheme,
  LngLat
} from "./ExpoMapboxNavigation.types";

let NativeModule: { isNavigationActive(): boolean; stopNavigation(): void } | null = null;
try {
  NativeModule = requireNativeModule("ExpoMapboxNavigation");
} catch {
  NativeModule = null;
}

/**
 * True when a navigation session is alive — including when the nav screen has
 * been left (minimised). Drives the trip screen's "Resume navigation" affordance.
 */
export function isNavigationActive(): boolean {
  return NativeModule?.isNavigationActive?.() ?? false;
}

/** Fully end the running navigation session (the trip screen's "Stop"). */
export function stopNavigation(): void {
  NativeModule?.stopNavigation?.();
}
