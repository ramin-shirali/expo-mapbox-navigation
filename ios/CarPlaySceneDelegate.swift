import CarPlay
import MapboxNavigationCore
import MapboxNavigationUIKit

/// Bridges the CarPlay head unit to the SAME process-wide navigation session the
/// phone uses (`NavigationSession.shared`). Mapbox's `CarPlayManager` runs the
/// CarPlay map + templates over the shared `MapboxNavigationProvider`, so the
/// phone and the head unit observe one trip session and stay in sync.
///
/// This is a chauffeur app: the destination comes from the active ride, never
/// from CarPlay search — so we don't wire the geocoder/favorites/search stack.
/// CarPlay simply MIRRORS whatever the phone is navigating: when the head unit
/// connects mid-trip (or the phone starts a route while CarPlay is up) we show
/// the live route on the head unit; tapping "Go" begins synced guidance there.
///
/// Created lazily — the manager only exists once a CarPlay scene connects.
@MainActor
final class CarPlayBridge: NSObject {
  static let shared = CarPlayBridge()

  let carPlayManager: CarPlayManager
  private(set) var isConnected = false

  private override init() {
    carPlayManager = CarPlayManager(navigationProvider: NavigationSession.shared.provider)
    super.init()
    carPlayManager.delegate = self
    // When the phone starts a fresh route while the head unit is connected,
    // reflect it onto CarPlay too.
    NavigationSession.shared.onRouteStarted = { [weak self] in
      self?.mirrorActiveRoute()
    }
  }

  func sceneDidConnect(
    _ scene: CPTemplateApplicationScene,
    _ interfaceController: CPInterfaceController,
    _ window: CPWindow
  ) {
    isConnected = true
    carPlayManager.templateApplicationScene(scene, didConnect: interfaceController, to: window)
    // If a trip is already underway when the head unit comes up, show it.
    mirrorActiveRoute()
  }

  func sceneDidDisconnect(
    _ scene: CPTemplateApplicationScene,
    _ interfaceController: CPInterfaceController,
    _ window: CPWindow
  ) {
    isConnected = false
    carPlayManager.templateApplicationScene(scene, didDisconnect: interfaceController, from: window)
  }

  /// Reflect the phone's live route onto the head unit. No-op when CarPlay isn't
  /// connected or nothing is navigating. Tapping "Go" on the preview begins
  /// guidance on CarPlay, synced to the same trip session.
  func mirrorActiveRoute() {
    guard isConnected,
          let routes = NavigationSession.shared.mapboxNavigation.tripSession().currentNavigationRoutes
    else { return }
    Task { await carPlayManager.previewRoutes(for: routes) }
  }
}

// All `CarPlayManagerDelegate` methods have default implementations
// (`UnimplementedLogging`), so we override only what we need.
extension CarPlayBridge: CarPlayManagerDelegate {
  /// Ending navigation from the head unit ends the shared trip session so the
  /// phone stops guiding too.
  func carPlayManagerDidEndNavigation(_ carPlayManager: CarPlayManager, byCanceling canceled: Bool) {
    if canceled {
      NavigationSession.shared.stop()
    }
  }
}

/// The CarPlay scene entry point. iOS instantiates this by name from the
/// `UIApplicationSceneManifest` the config plugin writes into Info.plist (the
/// `EMNCarPlaySceneDelegate` Obj-C name below must match that manifest). The
/// app keeps using its legacy AppDelegate window for the phone UI; only the
/// CarPlay scene role is declared, so the two lifecycles coexist.
@objc(EMNCarPlaySceneDelegate)
@MainActor
public final class EMNCarPlaySceneDelegate: NSObject, CPTemplateApplicationSceneDelegate {
  public func templateApplicationScene(
    _ templateApplicationScene: CPTemplateApplicationScene,
    didConnect interfaceController: CPInterfaceController,
    to window: CPWindow
  ) {
    CarPlayBridge.shared.sceneDidConnect(templateApplicationScene, interfaceController, window)
  }

  public func templateApplicationScene(
    _ templateApplicationScene: CPTemplateApplicationScene,
    didDisconnect interfaceController: CPInterfaceController,
    from window: CPWindow
  ) {
    CarPlayBridge.shared.sceneDidDisconnect(templateApplicationScene, interfaceController, window)
  }
}
