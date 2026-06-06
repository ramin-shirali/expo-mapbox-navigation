import ExpoModulesCore

/// Brand/appearance hooks passed from JS (`theme` prop).
struct NavigationThemeRecord: Record {
  @Field var primary: String?
  @Field var night: Bool?
}

/// Expo module definition: registers the single `ExpoMapboxNavigation` view and
/// its props/events. All real work lives in `MapboxNavigationView`.
public class ExpoMapboxNavigationModule: Module {
  public func definition() -> ModuleDefinition {
    Name("ExpoMapboxNavigation")

    // True when a navigation session is alive (possibly minimised). Reads the
    // nonisolated flag so it stays synchronous.
    Function("isNavigationActive") { () -> Bool in
      NavSessionFlags.active
    }

    // Explicitly end navigation (the trip screen's "Stop" action).
    Function("stopNavigation") {
      Task { @MainActor in NavigationSession.shared.stop() }
    }

    View(MapboxNavigationView.self) {
      Events("onRouteProgress", "onArrival", "onCancel", "onReroute", "onError")

      Prop("coordinates") { (view: MapboxNavigationView, coords: [[Double]]) in
        view.setCoordinates(coords)
      }
      Prop("profile") { (view: MapboxNavigationView, profile: String?) in
        view.setProfile(profile ?? "driving-traffic")
      }
      Prop("mute") { (view: MapboxNavigationView, mute: Bool?) in
        view.setMuted(mute ?? false)
      }
      Prop("theme") { (view: MapboxNavigationView, theme: NavigationThemeRecord?) in
        view.setTheme(theme)
      }
    }
  }
}
