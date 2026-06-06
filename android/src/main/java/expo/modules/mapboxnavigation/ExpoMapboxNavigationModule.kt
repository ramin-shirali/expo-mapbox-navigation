package expo.modules.mapboxnavigation

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

/**
 * Expo module definition: registers the single `ExpoMapboxNavigation` view and
 * its props/events. All real work lives in [MapboxNavigationView].
 */
class ExpoMapboxNavigationModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("ExpoMapboxNavigation")

    // True when a navigation session is alive (possibly minimised).
    Function("isNavigationActive") { NavSession.active }

    // Explicitly end navigation (the trip screen's "Stop" action).
    Function("stopNavigation") { NavSession.stop() }

    View(MapboxNavigationView::class) {
      Events("onRouteProgress", "onArrival", "onCancel", "onReroute", "onError")

      Prop("coordinates") { view: MapboxNavigationView, coords: List<List<Double>> ->
        view.setCoordinates(coords)
      }
      Prop("profile") { view: MapboxNavigationView, profile: String? ->
        view.setProfile(profile ?: "driving-traffic")
      }
      Prop("mute") { view: MapboxNavigationView, mute: Boolean? ->
        view.setMuted(mute ?: false)
      }
      Prop("theme") { view: MapboxNavigationView, theme: Map<String, Any?>? ->
        view.setTheme(theme)
      }
    }
  }
}
