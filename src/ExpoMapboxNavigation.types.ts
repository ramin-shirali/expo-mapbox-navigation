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

/** Payload for {@link MapboxNavigationViewProps.onWaypointArrival}. */
export interface WaypointArrival {
  /**
   * Index of the reached waypoint in the {@link MapboxNavigationViewProps.coordinates}
   * array. The origin is `0`, so intermediate stops are `1..coordinates.length - 2`.
   * The final destination fires {@link MapboxNavigationViewProps.onArrival}, not this.
   */
  index: number;
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
   * The first coordinate is the start, the last is the final destination, and
   * any coordinates in between are intermediate stops routed through in order.
   * Reaching an intermediate stop fires {@link onWaypointArrival}; reaching the
   * last fires {@link onArrival}. The route is (re)calculated whenever the full
   * list changes (so editing a stop re-routes; an unchanged list resumes the
   * live session with no recalculation).
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
  /**
   * Fired each time an **intermediate** waypoint (a stop, not the final
   * destination) is reached, carrying its index in {@link coordinates}.
   */
  onWaypointArrival?: (e: WaypointArrival) => void;
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
