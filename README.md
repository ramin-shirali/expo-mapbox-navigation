# expo-mapbox-navigation

A first-party [Expo module](https://docs.expo.dev/modules/overview/) wrapping the
**Mapbox Navigation SDK v3** for in-app, voice-guided turn-by-turn navigation on
**iOS and Android** — plus **Apple CarPlay**, **Android Auto**, and
**multi-waypoint** routing. Owned by Black Crown Mobility; built to replace the
abandoned third-party wrappers so the JS API, native UI, and version pins are
ours to move.

> Requires a custom dev/EAS build — the native view does **not** exist in Expo
> Go. When the module isn't compiled in, `isNavigationAvailable` is `false` and
> callers should fall back (e.g. an OS-maps hand-off).

## Install

```sh
npx expo install expo-mapbox-navigation
```

Add the config plugin (it wires the Mapbox SPM/Maven setup, tokens, permissions,
background modes, and — opt-in — CarPlay):

```js
// app.config.js
export default {
  plugins: [
    ["expo-mapbox-navigation", { /* carPlay: true  // see CarPlay below */ }]
  ]
};
```

Set the tokens before prebuild/EAS:

- `MAPBOX_DOWNLOAD_TOKEN` — secret token, scope `Downloads:Read` (fetches the SDKs).
- `EXPO_PUBLIC_MAPBOX_TOKEN` — public token (runtime).

## Usage

```tsx
import {
  MapboxNavigationView,
  isNavigationAvailable,
  isNavigationActive,
  stopNavigation
} from "expo-mapbox-navigation";

<MapboxNavigationView
  style={{ flex: 1 }}
  coordinates={[[lng, lat] /* origin */, ...stops, [lng, lat] /* destination */]}
  profile="driving-traffic"
  mute={false}
  theme={{ primary: "#D4AF37", night: true }}
  onRouteProgress={({ distanceRemaining, durationRemaining, fractionTraveled }) => {}}
  onWaypointArrival={({ index }) => {}}
  onArrival={() => {}}
  onCancel={() => {}}
  onReroute={() => {}}
  onError={({ message }) => {}}
/>;
```

### Props

| Prop | Type | Notes |
|---|---|---|
| `coordinates` | `[lng, lat][]` | Ordered `[origin, ...stops, destination]`. Routes through every stop in one session. |
| `profile` | `"driving-traffic" \| "driving"` | Defaults to `"driving-traffic"`. |
| `mute` | `boolean` | Mute voice guidance. |
| `theme` | `{ primary?: string; night?: boolean }` | Brand accent + day/night. |

### Events

| Event | Payload | Fires when |
|---|---|---|
| `onRouteProgress` | `{ distanceRemaining, durationRemaining, fractionTraveled }` | Continuously along the route. |
| `onWaypointArrival` | `{ index }` | An **intermediate** stop is reached (`index` into `coordinates`). |
| `onArrival` | — | The final destination is reached. |
| `onCancel` | — | The user exits navigation from the native UI. |
| `onReroute` | — | The SDK recalculates after going off-route. |
| `onError` | `{ message }` | Non-recoverable error (route fetch failed, etc.). |

### Module functions

- `isNavigationAvailable: boolean` — true when the native module is in the build.
- `isNavigationActive(): boolean` — true while a session is alive (incl. minimised).
- `stopNavigation(): void` — fully end the running session.

## Multi-waypoint

`coordinates` is the full chain `[origin, ...stops, destination]`; the SDK routes
through every intermediate stop in one session. Reaching a stop fires
`onWaypointArrival({ index })`; reaching the last fires `onArrival`. The session
is keyed on the **whole** chain, so editing a stop re-routes while an unchanged
chain resumes the live session with no recalculation.

## Persistent / resumable session

Navigation survives leaving the screen: the session lives in a process-wide
singleton (iOS `NavigationSession`; Android `NavSession` + `MapboxNavigationApp`)
independent of the React view. Detaching the view minimises (keeps the trip
session + voice running); re-mounting with the same destination resumes instantly
with no recalculation.

## CarPlay & Android Auto

The head unit **mirrors the phone's active navigation** — same destination, same
process-wide trip session, driven by the same Mapbox core (no second route fetch).

- **iOS — CarPlay**: Mapbox's `CarPlayManager` over the shared navigation
  provider, via a `CPTemplateApplicationSceneDelegate`. **Opt-in** — set
  `carPlay: true` on the config plugin (or `EXPO_PUBLIC_ENABLE_CARPLAY=1`). It's
  off by default because the `com.apple.developer.carplay-maps` entitlement needs
  Apple's grant + a matching provisioning profile, and adding it before the grant
  breaks code-signing.
- **Android — Android Auto**: built on `androidx.car.app` (Nav SDK v3 ships no
  Android Auto artifact) — a `CarAppService` rendering a `NavigationTemplate` over
  the Mapbox map drawn to the car surface via `MapSurface`, fed by the app-scoped
  `MapboxNavigation`.

Both need a real head unit / simulator (Xcode's CarPlay window; Android's Desktop
Head Unit) to validate.

## Version pins

The map (`@rnmapbox/maps`) and the Nav SDK both pull **MapboxMaps**; two copies
crash the map on mount, so everything must resolve **one** core.

| Piece | Pin |
|---|---|
| Mapbox Nav SDK | **3.20.1** |
| MapboxMaps core | **11.20.2** (shared with `@rnmapbox/maps`) |
| Expo / RN / React | **55 / 0.83.6 / 19.2.0** |

Nav minor tracks Maps minor since 3.16, so **Nav 3.20.1 ↔ Maps 11.20.2** is a
documented, stable pairing on both platforms. Move all of them together.

## License

MIT
