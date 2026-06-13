import ExpoModulesCore
import MapboxNavigationCore
import MapboxNavigationUIKit
import MapboxDirections
import CoreLocation
import Combine
import UIKit

/// Process-wide navigation session. Owns the Mapbox provider and the live
/// `NavigationViewController` so the trip session + voice guidance survive the
/// nav screen being popped (minimised). The view re-attaches the SAME controller
/// when the screen re-mounts → instant resume, no recalculation. Guidance keeps
/// running in the background (audio + location background modes).
/// Nonisolated mirror of "is navigation active", written on the main actor, so
/// the module's synchronous `isNavigationActive()` can read it without hopping
/// actors. (The Mapbox types below are @MainActor-isolated.)
enum NavSessionFlags {
  nonisolated(unsafe) static var active = false
}

@MainActor
final class NavigationSession {
  static let shared = NavigationSession()
  let provider: MapboxNavigationProvider
  /// The retained, still-running navigation UI. Detached from the view tree when
  /// minimised, re-embedded on resume. nil = no active navigation.
  var navigationViewController: NavigationViewController?
  /// Identifies the active route's destination so we know whether to resume the
  /// existing session or start a fresh one (e.g. pickup → drop-off).
  var destinationKey: String?

  /// Set by `CarPlayBridge` when a CarPlay head unit is connected; invoked
  /// whenever a fresh route starts so CarPlay can mirror the phone's route.
  /// Decoupled via a closure so this file never imports the CarPlay stack and
  /// the bridge/manager is only created once CarPlay actually connects.
  var onRouteStarted: (() -> Void)?

  private init() {
    provider = MapboxNavigationProvider(coreConfig: CoreConfig())
  }

  var mapboxNavigation: MapboxNavigation { provider.mapboxNavigation }
  var isActive: Bool { navigationViewController != nil }

  /// Fully end navigation (explicit "Stop"), tearing down the session.
  func stop() {
    if let vc = navigationViewController {
      vc.willMove(toParent: nil)
      vc.view.removeFromSuperview()
      vc.removeFromParent()
    }
    mapboxNavigation.tripSession().setToIdle()
    navigationViewController = nil
    destinationKey = nil
    NavSessionFlags.active = false
  }
}

/// Full-screen, voice-guided turn-by-turn navigation backed by the Mapbox
/// Navigation SDK v3. Hosts the shared `NavigationSession`'s drop-in
/// `NavigationViewController` as a child VC pinned to this Expo view's bounds.
///
/// The SDK reads its access token from the app's Info.plist `MBXAccessToken`
/// key (set by the config plugin), so we never set it from JS — that avoids the
/// New-Architecture race that crashes the map (see NAVIGATION.md).
public class MapboxNavigationView: ExpoView {
  // MARK: Events
  let onRouteProgress = EventDispatcher()
  let onWaypointArrival = EventDispatcher()
  let onArrival = EventDispatcher()
  let onCancel = EventDispatcher()
  let onReroute = EventDispatcher()
  let onError = EventDispatcher()

  // MARK: Props
  private var coordinates: [CLLocationCoordinate2D] = []
  private var profileIdentifier: ProfileIdentifier = .automobileAvoidingTraffic
  private var muted = false
  private var theme: NavigationThemeRecord?

  private var subscriptions = Set<AnyCancellable>()
  private var routeRequest: Task<Void, Never>?
  private var pendingAttach = false

  private var session: NavigationSession { NavigationSession.shared }
  private var mapboxNavigation: MapboxNavigation { session.mapboxNavigation }

  required init(appContext: AppContext? = nil) {
    super.init(appContext: appContext)
    clipsToBounds = true
    backgroundColor = .black
  }

  deinit {
    routeRequest?.cancel()
    // Detach happens in didMoveToWindow(nil) — deinit can't touch @MainActor state.
  }

  public override func didMoveToWindow() {
    super.didMoveToWindow()
    if window == nil { detachKeepingSession() }
  }

  // MARK: - Prop setters

  func setCoordinates(_ coords: [[Double]]) {
    // JS sends [lng, lat] pairs; CLLocationCoordinate2D is (lat, lng).
    coordinates = coords.compactMap { pair in
      guard pair.count == 2 else { return nil }
      return CLLocationCoordinate2D(latitude: pair[1], longitude: pair[0])
    }
    scheduleAttach()
  }

  func setProfile(_ value: String) {
    profileIdentifier = (value == "driving") ? .automobile : .automobileAvoidingTraffic
    scheduleAttach()
  }

  func setMuted(_ value: Bool) {
    muted = value
    session.provider.routeVoiceController.speechSynthesizer.muted = value
  }

  func setTheme(_ value: NavigationThemeRecord?) {
    theme = value // Brand theming is Phase 3; wired end-to-end.
  }

  // MARK: - Attach / resume / start

  private func key(for coords: [CLLocationCoordinate2D]) -> String {
    coords.map { "\($0.latitude),\($0.longitude)" }.joined(separator: ";")
  }

  /// Props arrive one-by-one; coalesce into a single attach on the next tick.
  private func scheduleAttach() {
    guard !pendingAttach else { return }
    pendingAttach = true
    DispatchQueue.main.async { [weak self] in
      guard let self else { return }
      self.pendingAttach = false
      self.attach()
    }
  }

  private func attach() {
    guard coordinates.count >= 2, let host = findViewController() else { return }
    let wantKey = key(for: coordinates)

    // Resume: the same destination is already navigating → re-embed the live VC.
    if let vc = session.navigationViewController, session.destinationKey == wantKey {
      embed(vc, in: host)
      return
    }

    // A different destination is active (e.g. pickup → drop-off): end it first.
    if session.navigationViewController != nil {
      session.stop()
    }

    // Start a fresh session.
    routeRequest?.cancel()
    let waypoints = coordinates
    let profile = profileIdentifier
    routeRequest = Task { [weak self] in
      guard let self else { return }
      do {
        let options = NavigationRouteOptions(coordinates: waypoints, profileIdentifier: profile)
        let routes = try await self.mapboxNavigation.routingProvider().calculateRoutes(options: options).value
        if Task.isCancelled { return }
        await MainActor.run {
          guard let host = self.findViewController() else {
            self.onError(["message": "no_host_view_controller"])
            return
          }
          let navigationOptions = NavigationOptions(
            mapboxNavigation: self.mapboxNavigation,
            voiceController: self.session.provider.routeVoiceController,
            eventsManager: self.session.provider.eventsManager()
          )
          let vc = NavigationViewController(navigationRoutes: routes, navigationOptions: navigationOptions)
          self.session.navigationViewController = vc
          self.session.destinationKey = wantKey
          NavSessionFlags.active = true
          self.embed(vc, in: host)
          // Mirror the just-started route onto CarPlay if a head unit is up.
          self.session.onRouteStarted?()
        }
      } catch {
        if Task.isCancelled { return }
        await MainActor.run { self.onError(["message": "route_calculation_failed: \(error.localizedDescription)"]) }
      }
    }
  }

  private func embed(_ vc: NavigationViewController, in host: UIViewController) {
    // Move the (possibly previously-attached) VC under this view's host.
    if vc.parent !== host {
      if vc.parent != nil {
        vc.willMove(toParent: nil)
        vc.view.removeFromSuperview()
        vc.removeFromParent()
      }
      host.addChild(vc)
      vc.view.frame = bounds
      vc.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
      addSubview(vc.view)
      vc.didMove(toParent: host)
    } else if vc.view.superview !== self {
      vc.view.frame = bounds
      vc.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
      addSubview(vc.view)
    }
    vc.delegate = self
    setMuted(muted)
    observeProgress()
  }

  /// Detach the live VC from this view WITHOUT ending the session (minimise).
  private func detachKeepingSession() {
    subscriptions.removeAll()
    guard let vc = session.navigationViewController, vc.view.superview === self else { return }
    vc.willMove(toParent: nil)
    vc.view.removeFromSuperview()
    vc.removeFromParent()
    // The VC (and its trip session + voice) stays retained by NavigationSession.
  }

  private func observeProgress() {
    subscriptions.removeAll()
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

  public override func layoutSubviews() {
    super.layoutSubviews()
    if let vc = session.navigationViewController, vc.view.superview === self {
      vc.view.frame = bounds
    }
  }

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
    // The SDK's own end-navigation control: fully stop + notify JS to pop.
    NavigationSession.shared.stop()
    onCancel()
  }

  public func navigationViewController(
    _ navigationViewController: NavigationViewController,
    didArriveAt waypoint: Waypoint
  ) -> Bool {
    // Map the reached waypoint back to its index in the coordinates we passed.
    // The last index is the final destination (→ onArrival); any earlier index
    // is an intermediate stop (→ onWaypointArrival). The SDK may snap the
    // waypoint to the road, so match by nearest coordinate rather than equality.
    let index = nearestCoordinateIndex(to: waypoint.coordinate)
    if index == coordinates.count - 1 {
      onArrival()
    } else if index >= 0 {
      onWaypointArrival(["index": index])
    }
    return true
  }

  /// Index of the passed-in coordinate closest to `target` (−1 if none).
  private func nearestCoordinateIndex(to target: CLLocationCoordinate2D) -> Int {
    var best = -1
    var bestDist = Double.greatestFiniteMagnitude
    for (i, c) in coordinates.enumerated() {
      let dLat = c.latitude - target.latitude
      let dLng = c.longitude - target.longitude
      let dist = dLat * dLat + dLng * dLng
      if dist < bestDist {
        bestDist = dist
        best = i
      }
    }
    return best
  }

  public func navigationViewController(
    _ navigationViewController: NavigationViewController,
    willRerouteFrom location: CLLocation
  ) {
    onReroute()
  }
}
