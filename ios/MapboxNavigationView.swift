import ExpoModulesCore
import MapboxNavigationCore
import MapboxNavigationUIKit
import MapboxDirections
import CoreLocation
import Combine
import UIKit

/// Full-screen, voice-guided turn-by-turn navigation backed by the Mapbox
/// Navigation SDK v3. Hosts the SDK's drop-in `NavigationViewController` as a
/// child view controller pinned to this Expo view's bounds.
///
/// The SDK reads its access token from the app's Info.plist `MBXAccessToken`
/// key (set in app.config.js), so we never set it from JS — that avoids the
/// New-Architecture race that crashes the map (see NAVIGATION.md).
public class MapboxNavigationView: ExpoView {
  // MARK: Events
  let onRouteProgress = EventDispatcher()
  let onArrival = EventDispatcher()
  let onCancel = EventDispatcher()
  let onReroute = EventDispatcher()
  let onError = EventDispatcher()

  // MARK: Props (set via the Module's Prop handlers)
  private var coordinates: [CLLocationCoordinate2D] = []
  private var profileIdentifier: ProfileIdentifier = .automobileAvoidingTraffic
  private var muted = false
  private var theme: NavigationThemeRecord?

  // MARK: Mapbox v3 stack
  private let mapboxNavigationProvider: MapboxNavigationProvider
  private var mapboxNavigation: MapboxNavigation { mapboxNavigationProvider.mapboxNavigation }
  private var navigationViewController: NavigationViewController?
  private var subscriptions = Set<AnyCancellable>()
  private var routeRequest: Task<Void, Never>?
  private var pendingRebuild = false
  private var didStart = false

  required init(appContext: AppContext? = nil) {
    // CoreConfig() defaults to the Info.plist MBXAccessToken credentials.
    mapboxNavigationProvider = MapboxNavigationProvider(coreConfig: CoreConfig())
    super.init(appContext: appContext)
    clipsToBounds = true
    backgroundColor = .black
  }

  deinit {
    routeRequest?.cancel()
    teardownNavigation()
  }

  // MARK: - Prop setters

  func setCoordinates(_ coords: [[Double]]) {
    // JS sends [lng, lat] pairs; CLLocationCoordinate2D is (lat, lng).
    coordinates = coords.compactMap { pair in
      guard pair.count == 2 else { return nil }
      return CLLocationCoordinate2D(latitude: pair[1], longitude: pair[0])
    }
    NSLog("[BCMNav] setCoordinates raw=\(coords.count) parsed=\(coordinates.count) first=\(String(describing: coordinates.first)) last=\(String(describing: coordinates.last))")
    scheduleRebuild()
  }

  func setProfile(_ value: String) {
    profileIdentifier = (value == "driving") ? .automobile : .automobileAvoidingTraffic
    scheduleRebuild()
  }

  func setMuted(_ value: Bool) {
    muted = value
    // The drop-in NavigationViewController exposes its own mute control to the
    // user; we mirror the JS-driven value onto the voice controller best-effort.
    mapboxNavigationProvider.routeVoiceController.speechSynthesizer.muted = value
  }

  func setTheme(_ value: NavigationThemeRecord?) {
    theme = value
    // Brand theming (custom Day/Night styles) is applied in Phase 3; storing it
    // now so the prop is wired end-to-end.
  }

  // MARK: - Route lifecycle

  /// Props arrive one-by-one in a render batch; coalesce them into a single
  /// rebuild on the next runloop tick so we request the route only once.
  private func scheduleRebuild() {
    guard !pendingRebuild else { return }
    pendingRebuild = true
    DispatchQueue.main.async { [weak self] in
      guard let self else { return }
      self.pendingRebuild = false
      self.rebuildNavigation()
    }
  }

  private func rebuildNavigation() {
    NSLog("[BCMNav] rebuildNavigation coords=\(coordinates.count) hasVC=\(navigationViewController != nil)")
    guard coordinates.count >= 2 else { NSLog("[BCMNav] skip: <2 coords"); return }
    // Only build once; a live reroute is handled by the SDK, not by remounting.
    guard navigationViewController == nil else { NSLog("[BCMNav] skip: VC exists"); return }

    routeRequest?.cancel()
    let waypointCoordinates = coordinates
    let profile = profileIdentifier

    routeRequest = Task { [weak self] in
      guard let self else { return }
      do {
        NSLog("[BCMNav] requesting route…")
        let options = NavigationRouteOptions(
          coordinates: waypointCoordinates,
          profileIdentifier: profile
        )
        let navigationRoutes = try await self.mapboxNavigation
          .routingProvider()
          .calculateRoutes(options: options)
          .value
        NSLog("[BCMNav] route OK")
        if Task.isCancelled { NSLog("[BCMNav] route task cancelled"); return }
        await MainActor.run { self.presentNavigation(with: navigationRoutes) }
      } catch {
        NSLog("[BCMNav] route FAILED: \(error)")
        if Task.isCancelled { return }
        await MainActor.run {
          self.onError(["message": "route_calculation_failed: \(error.localizedDescription)"])
        }
      }
    }
  }

  @MainActor
  private func presentNavigation(with navigationRoutes: NavigationRoutes) {
    NSLog("[BCMNav] presentNavigation bounds=\(bounds) hostVC=\(findViewController() != nil)")
    guard let parent = findViewController() else {
      NSLog("[BCMNav] no host VC")
      onError(["message": "no_host_view_controller"])
      return
    }

    let navigationOptions = NavigationOptions(
      mapboxNavigation: mapboxNavigation,
      voiceController: mapboxNavigationProvider.routeVoiceController,
      eventsManager: mapboxNavigationProvider.eventsManager()
    )

    let vc = NavigationViewController(
      navigationRoutes: navigationRoutes,
      navigationOptions: navigationOptions
    )
    vc.delegate = self
    vc.modalPresentationStyle = .fullScreen

    parent.addChild(vc)
    vc.view.frame = bounds
    vc.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
    addSubview(vc.view)
    vc.didMove(toParent: parent)

    navigationViewController = vc
    didStart = true
    setMuted(muted)
    observeProgress()
    NSLog("[BCMNav] presented; subview frame=\(vc.view.frame)")
  }

  private func observeProgress() {
    // Continuous progress lives on the navigation controller's publisher in v3
    // (delegate carries UI-level callbacks only).
    mapboxNavigation.navigation().routeProgress
      .receive(on: DispatchQueue.main)
      .sink { [weak self] state in
        guard let self, let progress = state?.routeProgress else { return }
        self.onRouteProgress([
          "distanceRemaining": progress.distanceRemaining,
          "durationRemaining": progress.durationRemaining,
          "fractionTraveled": progress.fractionTraveled
        ])
      }
      .store(in: &subscriptions)
  }

  private func teardownNavigation() {
    subscriptions.removeAll()
    if didStart {
      mapboxNavigation.tripSession().setToIdle()
      didStart = false
    }
    if let vc = navigationViewController {
      vc.willMove(toParent: nil)
      vc.view.removeFromSuperview()
      vc.removeFromParent()
      navigationViewController = nil
    }
  }

  // MARK: - Layout

  public override func layoutSubviews() {
    super.layoutSubviews()
    navigationViewController?.view.frame = bounds
  }

  /// Walk the responder chain to find the owning UIViewController (the host for
  /// our child NavigationViewController) — no dependency on RN's UIView category.
  private func findViewController() -> UIViewController? {
    var responder: UIResponder? = self
    while let current = responder {
      if let vc = current as? UIViewController { return vc }
      responder = current.next
    }
    return nil
  }
}

// MARK: - NavigationViewControllerDelegate

extension MapboxNavigationView: NavigationViewControllerDelegate {
  public func navigationViewControllerDidDismiss(
    _ navigationViewController: NavigationViewController,
    byCanceling canceled: Bool
  ) {
    teardownNavigation()
    onCancel()
  }

  public func navigationViewController(
    _ navigationViewController: NavigationViewController,
    didArriveAt waypoint: Waypoint
  ) -> Bool {
    // Only emit arrival for the final destination (last waypoint).
    if waypoint.coordinate == coordinates.last {
      onArrival()
    }
    return true
  }

  public func navigationViewController(
    _ navigationViewController: NavigationViewController,
    willRerouteFrom location: CLLocation
  ) {
    onReroute()
  }
}
