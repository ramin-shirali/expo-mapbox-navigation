package expo.modules.mapboxnavigation

import android.content.Intent
import android.graphics.Rect
import android.text.SpannableString
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.CarAppService
import androidx.car.app.AppManager
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.Distance
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.mapbox.api.directions.v5.models.BannerInstructions
import com.mapbox.common.location.Location
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapInitOptions
import com.mapbox.maps.MapSurface
import com.mapbox.maps.Style
import com.mapbox.maps.plugin.LocationPuck2D
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.directions.session.RoutesObserver
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import com.mapbox.navigation.core.lifecycle.MapboxNavigationObserver
import com.mapbox.navigation.core.trip.session.LocationMatcherResult
import com.mapbox.navigation.core.trip.session.LocationObserver
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
import com.mapbox.navigation.ui.maps.location.NavigationLocationProvider
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineApi
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineView
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineApiOptions
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineViewOptions
import java.util.Calendar
import java.util.TimeZone

/**
 * Android Auto entry point. Mapbox Navigation SDK v3 dropped its dedicated
 * Android Auto module (the v2 `ui-androidauto`/MapboxCarApp), so this is built
 * directly on the Android for Cars App Library (`androidx.car.app`).
 *
 * Like CarPlay on iOS, the head unit MIRRORS the phone's active ride navigation:
 * it drives off the SAME process-wide `MapboxNavigation` (app-scoped via
 * `MapboxNavigationApp` + [NavSession]), renders the Mapbox map to the car
 * surface with [MapSurface], and shows turn-by-turn guidance in a
 * [NavigationTemplate]. The destination comes from the active ride, never from
 * car-side search.
 *
 * NOTE: like the rest of this module, the exact androidx.car.app + Mapbox
 * MapSurface surface/camera details validate on a real Android Auto head unit
 * (or the Desktop Head Unit). See NAVIGATION.md.
 */
class NavCarAppService : CarAppService() {
  override fun createHostValidator(): HostValidator =
    // Dev/testing convenience. For production, restrict to Google's signatures
    // via `R.array.hosts_allowlist_sample` per the car-app docs.
    HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

  override fun onCreateSession(): Session = NavCarSession()
}

/** One car session ↔ one connected head unit. Owns nav setup for its lifetime. */
class NavCarSession : Session() {
  override fun onCreateScreen(intent: Intent): Screen {
    // Ensure the app-scoped navigation exists even if the phone nav screen was
    // never opened this launch, then bind it to this session's lifecycle.
    if (!MapboxNavigationApp.isSetup()) {
      MapboxNavigationApp.setup(NavigationOptions.Builder(carContext).build())
    }
    MapboxNavigationApp.attach(this)
    return NavCarScreen(carContext)
  }
}

/**
 * The navigation screen: a [NavigationTemplate] with the live maneuver + travel
 * estimate, over a Mapbox map rendered to the car surface. Both are fed by the
 * shared [MapboxNavigation] route-progress/routes observers.
 */
class NavCarScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {
  private val navigationManager = carContext.getCarService(NavigationManager::class.java)

  // Latest guidance, rendered by onGetTemplate(); invalidate() refreshes it.
  private var routingInfo: RoutingInfo? = null
  private var travelEstimate: TravelEstimate? = null
  private var navigating = false

  private var carMap: CarMapRenderer? = null

  private val routeProgressObserver = RouteProgressObserver { progress ->
    updateGuidance(progress)
    carMap?.onRouteProgress(progress)
    invalidate()
  }

  private val routesObserver = RoutesObserver { result ->
    carMap?.onRoutes(result.navigationRoutes)
  }

  private val locationObserver = object : LocationObserver {
    override fun onNewRawLocation(rawLocation: Location) {}
    override fun onNewLocationMatcherResult(result: LocationMatcherResult) {
      carMap?.onLocation(result.enhancedLocation, result.keyPoints)
    }
  }

  private val navObserver = object : MapboxNavigationObserver {
    override fun onAttached(mapboxNavigation: MapboxNavigation) {
      mapboxNavigation.registerRouteProgressObserver(routeProgressObserver)
      mapboxNavigation.registerRoutesObserver(routesObserver)
      mapboxNavigation.registerLocationObserver(locationObserver)
    }

    override fun onDetached(mapboxNavigation: MapboxNavigation) {
      mapboxNavigation.unregisterRouteProgressObserver(routeProgressObserver)
      mapboxNavigation.unregisterRoutesObserver(routesObserver)
      mapboxNavigation.unregisterLocationObserver(locationObserver)
    }
  }

  init {
    lifecycle.addObserver(object : DefaultLifecycleObserver {
      override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this@NavCarScreen)
        MapboxNavigationApp.registerObserver(navObserver)
        // Declare to the host that this app is actively guiding, so the head unit
        // suppresses its own clutter and routes the "stop" affordance to us.
        if (NavSession.active) startNavigationSession()
      }

      override fun onDestroy(owner: LifecycleOwner) {
        MapboxNavigationApp.unregisterObserver(navObserver)
        if (navigating) navigationManager.navigationEnded()
        carMap?.destroy()
        carMap = null
      }
    })
  }

  private fun startNavigationSession() {
    if (navigating) return
    navigationManager.setNavigationManagerCallback(object : NavigationManagerCallback {
      override fun onStopNavigation() {
        // The head unit asked us to stop (e.g. user tapped end, or another nav
        // app took over). Tear down the shared session.
        NavSession.stop()
        navigating = false
        routingInfo = null
        invalidate()
      }

      override fun onAutoDriveEnabled() {
        CarToast.makeText(carContext, "Auto-drive enabled", CarToast.LENGTH_SHORT).show()
      }
    })
    navigationManager.navigationStarted()
    navigating = true
  }

  // MARK: - Template

  override fun onGetTemplate(): Template {
    val builder = NavigationTemplate.Builder()
      .setBackgroundColor(CarColor.PRIMARY)
      .setActionStrip(
        ActionStrip.Builder()
          .addAction(
            Action.Builder()
              .setTitle("Stop")
              .setOnClickListener {
                NavSession.stop()
                navigationManager.navigationEnded()
                navigating = false
                routingInfo = null
                invalidate()
              }
              .build()
          )
          .build()
      )

    val info = routingInfo
    if (info != null) {
      builder.setNavigationInfo(info)
      travelEstimate?.let { builder.setDestinationTravelEstimate(it) }
    } else {
      // No active route → a simple "waiting" message strip.
      builder.setNavigationInfo(
        RoutingInfo.Builder().setLoading(true).build()
      )
    }
    return builder.build()
  }

  /** Map a Mapbox [RouteProgress] onto the car template's guidance + ETA. */
  private fun updateGuidance(progress: RouteProgress) {
    if (!navigating && NavSession.active) startNavigationSession()

    val banner: BannerInstructions? = progress.bannerInstructions
    val cue = banner?.primary()?.text().orEmpty()
    val maneuver = Maneuver.Builder(bannerToManeuverType(banner)).build()

    val stepDistanceMeters =
      progress.currentLegProgress?.currentStepProgress?.distanceRemaining?.toDouble() ?: 0.0
    val step = Step.Builder()
      .setManeuver(maneuver)
      .setCue(SpannableString(cue.ifEmpty { "Continue" }))
      .build()

    routingInfo = RoutingInfo.Builder()
      .setCurrentStep(step, Distance.create(stepDistanceMeters, Distance.UNIT_METERS))
      .build()

    // Destination ETA + remaining distance.
    val arrival = Calendar.getInstance().apply {
      add(Calendar.SECOND, progress.durationRemaining.toInt())
    }
    travelEstimate = TravelEstimate.Builder(
      Distance.create(progress.distanceRemaining.toDouble(), Distance.UNIT_METERS),
      androidx.car.app.model.DateTimeWithZone.create(arrival.timeInMillis, TimeZone.getDefault())
    )
      .setRemainingTimeSeconds(progress.durationRemaining.toLong().coerceAtLeast(0))
      .build()
  }

  // MARK: - SurfaceCallback (the Mapbox map on the car display)

  override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
    val surface = surfaceContainer.surface ?: return
    carMap = CarMapRenderer(carContext).also {
      it.onSurfaceAvailable(surface, surfaceContainer.width, surfaceContainer.height)
    }
  }

  override fun onVisibleAreaChanged(visibleArea: Rect) {
    carMap?.onVisibleAreaChanged(visibleArea)
  }

  override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
    carMap?.destroy()
    carMap = null
  }
}

/**
 * Renders the Mapbox map onto the Android Auto surface via [MapSurface]
 * (Maps v11's raw-Surface renderer), drawing the live route line and following
 * the puck. Kept separate so the surface/camera plumbing — the part most likely
 * to need on-device tuning — is isolated from the template logic.
 */
private class CarMapRenderer(private val carContext: CarContext) {
  private var mapSurface: MapSurface? = null
  private val navigationLocationProvider = NavigationLocationProvider()
  private var visibleArea: Rect? = null

  private val routeLineApi = MapboxRouteLineApi(MapboxRouteLineApiOptions.Builder().build())
  private val routeLineView =
    MapboxRouteLineView(MapboxRouteLineViewOptions.Builder(carContext).build())

  fun onSurfaceAvailable(surface: android.view.Surface, width: Int, height: Int) {
    val ms = MapSurface(carContext, surface, MapInitOptions(carContext))
    ms.surfaceCreated()
    ms.surfaceChanged(width, height)
    ms.onStart()
    ms.mapboxMap.loadStyle(Style.DARK) {
      // Drive the puck from the navigation's enhanced location (fed by onLocation)
      // rather than the raw device provider, so it matches the guidance.
      ms.location.apply {
        setLocationProvider(navigationLocationProvider)
        locationPuck = LocationPuck2D()
        enabled = true
        pulsingEnabled = false
      }
    }
    mapSurface = ms
  }

  fun onVisibleAreaChanged(visibleArea: Rect) {
    // Keep the puck out from under the head unit's chrome by padding the camera
    // to the host-reported safe area.
    this.visibleArea = visibleArea
  }

  /** Move the puck to the latest enhanced fix and follow it (a NavigationCamera
   * needs a MapView, so on a raw MapSurface we follow manually). */
  fun onLocation(enhanced: Location, keyPoints: List<Location>) {
    val ms = mapSurface ?: return
    navigationLocationProvider.changePosition(enhanced, keyPoints)
    val area = visibleArea
    val padding = if (area != null) {
      EdgeInsets(area.top.toDouble(), area.left.toDouble(), area.bottom.toDouble(), area.right.toDouble())
    } else {
      EdgeInsets(0.0, 0.0, 0.0, 0.0)
    }
    ms.mapboxMap.setCamera(
      CameraOptions.Builder()
        .center(Point.fromLngLat(enhanced.longitude, enhanced.latitude))
        .zoom(16.0)
        .pitch(45.0)
        .bearing(enhanced.bearing ?: 0.0)
        .padding(padding)
        .build()
    )
  }

  fun onRoutes(routes: List<com.mapbox.navigation.base.route.NavigationRoute>) {
    val ms = mapSurface ?: return
    if (routes.isEmpty()) {
      ms.mapboxMap.style?.let { style -> routeLineApi.clearRouteLine { routeLineView.renderClearRouteLineValue(style, it) } }
      return
    }
    routeLineApi.setNavigationRoutes(routes) { value ->
      ms.mapboxMap.style?.let { style -> routeLineView.renderRouteDrawData(style, value) }
    }
  }

  fun onRouteProgress(progress: RouteProgress) {
    val ms = mapSurface ?: return
    routeLineApi.updateWithRouteProgress(progress) { value ->
      ms.mapboxMap.style?.let { style -> routeLineView.renderRouteLineUpdate(style, value) }
    }
  }

  fun destroy() {
    mapSurface?.let {
      it.onStop()
      it.surfaceDestroyed()
      it.onDestroy()
    }
    mapSurface = null
  }
}

/** Map a Mapbox banner instruction to an Android Auto [Maneuver] type. */
private fun bannerToManeuverType(banner: BannerInstructions?): Int {
  val primary = banner?.primary() ?: return Maneuver.TYPE_STRAIGHT
  val type = primary.type().orEmpty()
  val modifier = primary.modifier().orEmpty()
  return when (type) {
    "depart" -> Maneuver.TYPE_DEPART
    "arrive" -> when (modifier) {
      "left" -> Maneuver.TYPE_DESTINATION_LEFT
      "right" -> Maneuver.TYPE_DESTINATION_RIGHT
      else -> Maneuver.TYPE_DESTINATION
    }
    // Roundabout maneuver types REQUIRE a roundabout exit number or build()
    // throws; we don't reliably have it, so fall back to straight (the cue text
    // still reads "Enter the roundabout…").
    "roundabout", "rotary" -> Maneuver.TYPE_STRAIGHT
    "merge" -> Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED
    "fork" -> if (modifier.contains("left")) Maneuver.TYPE_FORK_LEFT else Maneuver.TYPE_FORK_RIGHT
    "off ramp" -> if (modifier.contains("left")) Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT else Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
    "turn", "continue", "end of road", "new name" -> when (modifier) {
      "left" -> Maneuver.TYPE_TURN_NORMAL_LEFT
      "slight left" -> Maneuver.TYPE_TURN_SLIGHT_LEFT
      "sharp left" -> Maneuver.TYPE_TURN_SHARP_LEFT
      "right" -> Maneuver.TYPE_TURN_NORMAL_RIGHT
      "slight right" -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
      "sharp right" -> Maneuver.TYPE_TURN_SHARP_RIGHT
      "uturn" -> Maneuver.TYPE_U_TURN_LEFT
      else -> Maneuver.TYPE_STRAIGHT
    }
    else -> Maneuver.TYPE_STRAIGHT
  }
}
