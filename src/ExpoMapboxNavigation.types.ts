import type { ViewStyle } from "react-native";

/** A coordinate in GeoJSON / Mapbox order: `[longitude, latitude]`. */
export type LngLat = [number, number];

export type NavigationProfile = "driving-traffic" | "driving";

/** Payload for {@link MapboxNavigationViewProps.onRouteProgress}. */
export interface RouteProgress {
  /** Metres remaining to the final destination. */
  distanceRemaining: number;
  /** Seconds remaining to the final destination (traffic-aware on `driving-traffic`). */
  durationRemaining: number;
  /** 0–1 fraction of the route travelled so far. */
  fractionTraveled: number;
}

/** Brand/appearance hooks. The native side maps these onto the SDK's day/night styling. */
export interface NavigationTheme {
  /** Primary accent (route line / maneuver tint), e.g. the brand gold. Hex string. */
  primary?: string;
  /** Force the dark/night style. Defaults to following the system. */
  night?: boolean;
}

export interface MapboxNavigationViewProps {
  /**
   * Ordered waypoints `[origin, ...stops, destination]` in `[lng, lat]` order.
   * v1 passes `[origin, destination]`. The route is (re)calculated whenever this
   * changes; the first coordinate is the start, the last is the final destination.
   */
  coordinates: LngLat[];
  /** Routing profile. Defaults to `"driving-traffic"`. */
  profile?: NavigationProfile;
  /** Mute turn-by-turn voice guidance. Defaults to `false`. */
  mute?: boolean;
  /** Appearance hooks (brand accent, day/night). */
  theme?: NavigationTheme;

  /** Fired continuously as the chauffeur progresses along the route. */
  onRouteProgress?: (e: RouteProgress) => void;
  /** Fired once the final destination is reached. */
  onArrival?: () => void;
  /** Fired when the user exits/cancels navigation from the native UI. */
  onCancel?: () => void;
  /** Fired when the SDK detects an off-route condition and recalculates. */
  onReroute?: () => void;
  /** Fired on a non-recoverable navigation error (route fetch failed, etc.). */
  onError?: (e: { message: string }) => void;

  style?: ViewStyle;
}
