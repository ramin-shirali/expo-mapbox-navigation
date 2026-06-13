package expo.modules.mapboxnavigation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.mapbox.api.directions.v5.DirectionsCriteria
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.common.location.Location
import com.mapbox.geojson.Point
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.plugin.LocationPuck2D
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.navigation.base.extensions.applyDefaultNavigationOptions
import com.mapbox.navigation.base.extensions.applyLanguageAndVoiceUnitOptions
import com.mapbox.navigation.base.formatter.DistanceFormatterOptions
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.route.NavigationRouterCallback
import com.mapbox.navigation.base.route.RouterFailure
import com.mapbox.navigation.base.trip.model.RouteLegProgress
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.arrival.ArrivalObserver
import com.mapbox.navigation.core.directions.session.RoutesObserver
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import com.mapbox.navigation.core.lifecycle.MapboxNavigationObserver
import com.mapbox.navigation.core.trip.session.LocationMatcherResult
import com.mapbox.navigation.core.trip.session.LocationObserver
import com.mapbox.navigation.core.trip.session.OffRouteObserver
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
import com.mapbox.navigation.core.trip.session.VoiceInstructionsObserver
import com.mapbox.navigation.tripdata.maneuver.api.MapboxManeuverApi
import com.mapbox.navigation.tripdata.progress.api.MapboxTripProgressApi
import com.mapbox.navigation.tripdata.progress.model.DistanceRemainingFormatter
import com.mapbox.navigation.tripdata.progress.model.EstimatedTimeToArrivalFormatter
import com.mapbox.navigation.tripdata.progress.model.PercentDistanceTraveledFormatter
import com.mapbox.navigation.tripdata.progress.model.TimeRemainingFormatter
import com.mapbox.navigation.tripdata.progress.model.TripProgressUpdateFormatter
import com.mapbox.navigation.ui.components.maneuver.view.MapboxManeuverView
import com.mapbox.navigation.ui.components.tripprogress.view.MapboxTripProgressView
import com.mapbox.navigation.ui.maps.camera.NavigationCamera
import com.mapbox.navigation.ui.maps.camera.data.MapboxNavigationViewportDataSource
import com.mapbox.navigation.ui.maps.camera.lifecycle.NavigationBasicGesturesHandler
import com.mapbox.navigation.ui.maps.location.NavigationLocationProvider
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineApi
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineApiOptions
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineViewOptions
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineView
import com.mapbox.navigation.voice.api.MapboxSpeechApi
import com.mapbox.navigation.voice.api.MapboxVoiceInstructionsPlayer
import com.mapbox.navigation.voice.model.SpeechAnnouncement
import com.mapbox.navigation.voice.model.SpeechVolume
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.views.ExpoView
import java.util.Locale

/**
 * Full-screen, voice-guided turn-by-turn navigation backed by the Mapbox
 * Navigation SDK v3 (Android). v3 has NO drop-in UI on Android, so we assemble
 * the screen ourselves: a [MapView] with route line + nav camera + location
 * puck, a [MapboxManeuverView] banner, a [MapboxTripProgressView] footer, and
 * voice via [MapboxSpeechApi] + [MapboxVoiceInstructionsPlayer].
 *
 * The token is read by the Maps/Nav SDKs from the app's `MAPBOX_ACCESS_TOKEN`
 * resource / runtime config (wired by the config plugin) — not set from JS.
 *
 * NOTE: this targets the v3 (`com.mapbox.navigationcore`) API surface. A couple
 * of package paths / observer signatures may need minor adjustment against the
 * exact resolved 3.20.1 artifacts on the first Android build (see NAVIGATION.md).
 */
/**
 * Process-wide navigation state so the trip session survives the nav view being
 * detached (minimised). MapboxNavigationApp keeps the MapboxNavigation instance
 * alive at app scope; we just avoid stopping the trip session on detach, and
 * re-registering the observers on re-attach re-renders the ongoing route.
 */
object NavSession {
  var active = false
  var destinationKey: String? = null

  /** Explicitly end navigation (the trip screen's "Stop"). */
  fun stop() {
    MapboxNavigationApp.current()?.let { nav ->
      nav.setNavigationRoutes(emptyList())
      nav.stopTripSession()
    }
    active = false
    destinationKey = null
  }
}

class MapboxNavigationView(context: Context, appContext: AppContext) :
  ExpoView(context, appContext) {

  // MARK: Events
  private val onRouteProgress by EventDispatcher()
  private val onWaypointArrival by EventDispatcher()
  private val onArrival by EventDispatcher()
  private val onCancel by EventDispatcher()
  private val onReroute by EventDispatcher()
  private val onError by EventDispatcher()

  // MARK: Props
  private var coordinates: List<Point> = emptyList()
  private var profile: String = "driving-traffic"
  private var muted: Boolean = false
  private var theme: Map<String, Any?>? = null

  // MARK: Views
  // ExpoView is a LinearLayout that, by default, does NOT re-layout native
  // children when they call requestLayout() — a MapView then renders at 0x0
  // (black screen). Opt into Android layout, and host everything in a FrameLayout
  // so the maneuver banner / trip-progress footer overlay the full-screen map.
  override val shouldUseAndroidLayout = true
  private val root = FrameLayout(context)
  private val mapView = MapView(context)
  private val maneuverView = MapboxManeuverView(context)
  private val tripProgressView = MapboxTripProgressView(context)

  // MARK: Mapbox stack
  private var mapboxNavigation: MapboxNavigation? = null
  private var styleReady = false
  private var sessionStarted = false

  private val navigationLocationProvider = NavigationLocationProvider()
  private lateinit var viewportDataSource: MapboxNavigationViewportDataSource
  private lateinit var navigationCamera: NavigationCamera

  private val routeLineApi = MapboxRouteLineApi(MapboxRouteLineApiOptions.Builder().build())
  private val routeLineView =
    MapboxRouteLineView(MapboxRouteLineViewOptions.Builder(context).build())

  private val maneuverApi by lazy {
    MapboxManeuverApi(
      com.mapbox.navigation.core.formatter.MapboxDistanceFormatter(
        DistanceFormatterOptions.Builder(context).build()
      )
    )
  }
  private val tripProgressApi by lazy {
    MapboxTripProgressApi(
      TripProgressUpdateFormatter.Builder(context)
        .distanceRemainingFormatter(DistanceRemainingFormatter(DistanceFormatterOptions.Builder(context).build()))
        .timeRemainingFormatter(TimeRemainingFormatter(context))
        .percentRouteTraveledFormatter(PercentDistanceTraveledFormatter())
        .estimatedTimeToArrivalFormatter(EstimatedTimeToArrivalFormatter(context))
        .build()
    )
  }

  private val speechApi by lazy { MapboxSpeechApi(context, Locale.getDefault().language) }
  private val voicePlayer by lazy {
    MapboxVoiceInstructionsPlayer(context, Locale.getDefault().language)
  }

  // MARK: Observers

  private val locationObserver = object : LocationObserver {
    override fun onNewRawLocation(rawLocation: Location) {}
    override fun onNewLocationMatcherResult(result: LocationMatcherResult) {
      val enhanced = result.enhancedLocation
      navigationLocationProvider.changePosition(enhanced, result.keyPoints)
      viewportDataSource.onLocationChanged(enhanced)
      viewportDataSource.evaluate()
    }
  }

  private val routesObserver = RoutesObserver { result ->
    if (result.navigationRoutes.isNotEmpty()) {
      routeLineApi.setNavigationRoutes(result.navigationRoutes) { value ->
        mapView.mapboxMap.style?.let { routeLineView.renderRouteDrawData(it, value) }
      }
      viewportDataSource.onRouteChanged(result.navigationRoutes.first())
      viewportDataSource.evaluate()
      navigationCamera.requestNavigationCameraToFollowing()
    } else {
      mapView.mapboxMap.style?.let { routeLineApi.clearRouteLine { v -> routeLineView.renderClearRouteLineValue(it, v) } }
    }
  }

  private val routeProgressObserver = RouteProgressObserver { routeProgress ->
    viewportDataSource.onRouteProgressChanged(routeProgress)
    viewportDataSource.evaluate()

    maneuverView.renderManeuvers(maneuverApi.getManeuvers(routeProgress))
    tripProgressView.render(tripProgressApi.getTripProgress(routeProgress))

    onRouteProgress(
      mapOf(
        "distanceRemaining" to routeProgress.distanceRemaining.toDouble(),
        "durationRemaining" to routeProgress.durationRemaining,
        "fractionTraveled" to routeProgress.fractionTraveled.toDouble()
      )
    )
  }

  private val arrivalObserver = object : ArrivalObserver {
    override fun onWaypointArrival(routeProgress: RouteProgress) {
      // The leg that just ended; the reached waypoint is its end, i.e. index
      // legIndex + 1 in the coordinates we passed (origin is 0). Guard against
      // the final destination (handled by onFinalDestinationArrival).
      val legIndex = routeProgress.currentLegProgress?.legIndex ?: return
      val waypointIndex = legIndex + 1
      if (waypointIndex in 1 until (coordinates.size - 1)) {
        // Qualify the call: inside this ArrivalObserver, the bare name
        // `onWaypointArrival` resolves to the overridden observer method (which
        // takes a RouteProgress), not the outer view's EventDispatcher.
        this@MapboxNavigationView.onWaypointArrival(mapOf("index" to waypointIndex))
      }
    }
    override fun onNextRouteLegStart(routeLegProgress: RouteLegProgress) {}
    override fun onFinalDestinationArrival(routeProgress: RouteProgress) {
      onArrival(mapOf())
    }
  }

  private val offRouteObserver = OffRouteObserver { offRoute ->
    if (offRoute) onReroute(mapOf())
  }

  private val voiceObserver = VoiceInstructionsObserver { voiceInstructions ->
    speechApi.generate(voiceInstructions) { expected ->
      expected.fold(
        { error -> voicePlayer.play(error.fallback) { speechApi.clean(it) } },
        { value -> voicePlayer.play(value.announcement) { announcement: SpeechAnnouncement -> speechApi.clean(announcement) } }
      )
    }
  }

  private val navigationObserver = object : MapboxNavigationObserver {
    override fun onAttached(mapboxNavigation: MapboxNavigation) {
      this@MapboxNavigationView.mapboxNavigation = mapboxNavigation
      mapboxNavigation.registerRoutesObserver(routesObserver)
      mapboxNavigation.registerRouteProgressObserver(routeProgressObserver)
      mapboxNavigation.registerArrivalObserver(arrivalObserver)
      mapboxNavigation.registerOffRouteObserver(offRouteObserver)
      mapboxNavigation.registerLocationObserver(locationObserver)
      mapboxNavigation.registerVoiceInstructionsObserver(voiceObserver)
      startNavigationIfReady()
    }

    override fun onDetached(mapboxNavigation: MapboxNavigation) {
      mapboxNavigation.unregisterRoutesObserver(routesObserver)
      mapboxNavigation.unregisterRouteProgressObserver(routeProgressObserver)
      mapboxNavigation.unregisterArrivalObserver(arrivalObserver)
      mapboxNavigation.unregisterOffRouteObserver(offRouteObserver)
      mapboxNavigation.unregisterLocationObserver(locationObserver)
      mapboxNavigation.unregisterVoiceInstructionsObserver(voiceObserver)
      this@MapboxNavigationView.mapboxNavigation = null
    }
  }

  init {
    addView(
      root,
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    )
    // Full-screen map.
    root.addView(
      mapView,
      FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT
      )
    )
    // Maneuver banner pinned to the top.
    root.addView(
      maneuverView,
      FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.WRAP_CONTENT
      ).apply {
        gravity = Gravity.TOP
        val m = dp(8)
        setMargins(m, m, m, 0)
      }
    )
    // Trip-progress footer pinned to the bottom.
    root.addView(
      tripProgressView,
      FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.WRAP_CONTENT
      ).apply {
        gravity = Gravity.BOTTOM
      }
    )

    setupMap()
  }

  private fun setupMap() {
    mapView.mapboxMap.loadStyle(Style.DARK) {
      styleReady = true
      mapView.location.apply {
        setLocationProvider(navigationLocationProvider)
        locationPuck = LocationPuck2D()
        enabled = true
      }
      startNavigationIfReady()
    }

    viewportDataSource = MapboxNavigationViewportDataSource(mapView.mapboxMap)
    navigationCamera = NavigationCamera(mapView.mapboxMap, mapView.camera, viewportDataSource)
    mapView.camera.addCameraAnimationsLifecycleListener(
      NavigationBasicGesturesHandler(navigationCamera)
    )
  }

  // MARK: Prop setters

  fun setCoordinates(coords: List<List<Double>>) {
    coordinates = coords.mapNotNull { pair ->
      if (pair.size == 2) Point.fromLngLat(pair[0], pair[1]) else null
    }
    requestRouteIfReady()
  }

  fun setProfile(value: String) {
    profile = value
    requestRouteIfReady()
  }

  fun setMuted(value: Boolean) {
    muted = value
    voicePlayer.volume(SpeechVolume(if (value) 0f else 1f))
  }

  fun setTheme(value: Map<String, Any?>?) {
    theme = value
    // Brand theming (route-line colors / maneuver styles) is Phase 3; stored
    // now so the prop is wired end-to-end.
  }

  // MARK: Lifecycle / orchestration

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    if (!hasLocationPermission()) {
      onError(mapOf("message" to "location_permission_denied"))
      return
    }
    if (!MapboxNavigationApp.isSetup()) {
      MapboxNavigationApp.setup(NavigationOptions.Builder(context).build())
    }
    (appContext.currentActivity as? LifecycleOwner)?.let { MapboxNavigationApp.attach(it) }
    MapboxNavigationApp.registerObserver(navigationObserver)
  }

  override fun onDetachedFromWindow() {
    // Minimise: detach observers + this view's voice player, but DO NOT stop the
    // trip session — it keeps running so navigation resumes instantly. Use
    // NavSession.stop() (trip screen "Stop") to actually end it.
    MapboxNavigationApp.unregisterObserver(navigationObserver)
    speechApi.cancel()
    voicePlayer.shutdown()
    super.onDetachedFromWindow()
  }

  // Key the session on the FULL waypoint chain (not just the final destination)
  // so editing an intermediate stop re-routes, while an unchanged chain resumes
  // the live session. Mirrors the iOS `key(for:)`.
  private fun thisDestinationKey(): String? =
    if (coordinates.isEmpty()) null
    else coordinates.joinToString(";") { "${it.latitude()},${it.longitude()}" }

  private fun startNavigationIfReady() {
    val nav = mapboxNavigation ?: return
    if (!styleReady) return
    setMuted(muted)
    // Resume: same destination already navigating → don't restart; the freshly
    // re-registered RoutesObserver/RouteProgressObserver re-render the live route.
    if (NavSession.active && NavSession.destinationKey == thisDestinationKey()) {
      sessionStarted = true
      return
    }
    if (!sessionStarted) {
      nav.startTripSession()
      sessionStarted = true
    }
    requestRouteIfReady()
  }

  private fun requestRouteIfReady() {
    val nav = mapboxNavigation ?: return
    if (!sessionStarted || coordinates.size < 2) return

    val routeOptions = RouteOptions.builder()
      .applyDefaultNavigationOptions()
      .applyLanguageAndVoiceUnitOptions(context)
      .coordinatesList(coordinates)
      .profile(
        if (profile == "driving") DirectionsCriteria.PROFILE_DRIVING
        else DirectionsCriteria.PROFILE_DRIVING_TRAFFIC
      )
      .build()

    nav.requestRoutes(
      routeOptions,
      object : NavigationRouterCallback {
        override fun onRoutesReady(routes: List<NavigationRoute>, routerOrigin: String) {
          nav.setNavigationRoutes(routes)
          NavSession.active = true
          NavSession.destinationKey = thisDestinationKey()
        }

        override fun onFailure(reasons: List<RouterFailure>, routeOptions: RouteOptions) {
          onError(mapOf("message" to "route_calculation_failed: ${reasons.firstOrNull()?.message ?: "unknown"}"))
        }

        override fun onCanceled(routeOptions: RouteOptions, routerOrigin: String) {}
      }
    )
  }

  private fun hasLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED

  private fun dp(value: Int): Int =
    TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()
}
