// Config plugin for `expo-mapbox-navigation` — the app-level native wiring that
// CNG/autolinking can't infer for the Mapbox Navigation SDK v3.
//
//  iOS:  attach the mapbox-navigation-ios SPM package (products
//        MapboxNavigationCore + MapboxNavigationUIKit) to OUR pod target
//        (`ExpoMapboxNavigation`), set its framework search paths, mark the
//        Mapbox targets BUILD_LIBRARY_FOR_DISTRIBUTION, and embed + sign the
//        binary xcframeworks (MapboxCommon/MapboxCoreMaps/MapboxNavigationNative)
//        + strip duplicate signatures (Xcode 17). This mirrors the proven wiring
//        that previously lived in the (now-removed) third-party plugin's Podfile
//        additions. The single-shared-MapboxMaps trick for @rnmapbox/maps stays
//        in the app's own `withRnmapboxMapsSpm.js` (unchanged).
//
//  Android: add the authenticated Mapbox Maven repo + credentials, the
//        MAPBOX_DOWNLOADS_TOKEN gradle property, a project-wide
//        resolutionStrategy.force pinning ONE MapboxMaps (matches rnmapbox), the
//        public token as the `mapbox_access_token` string resource, and the
//        nav/location/foreground-service permissions.
//
// Tokens: secret (DOWNLOADS:READ) from MAPBOX_DOWNLOAD_TOKEN (build-time); public
// from EXPO_PUBLIC_MAPBOX_TOKEN / MAPBOX_PUBLIC_TOKEN (runtime). Overridable via
// plugin props.

const {
  withDangerousMod,
  withInfoPlist,
  withProjectBuildGradle,
  withGradleProperties,
  withStringsXml,
  AndroidConfig
} = require("@expo/config-plugins");
const fs = require("fs");
const path = require("path");

const NAV_VERSION = "3.20.1";
const MAPS_VERSION = "11.20.2";
const SPM_URL = "https://github.com/mapbox/mapbox-navigation-ios.git";

// ---------------------------------------------------------------------------
// iOS — patch the Podfile post_install to attach the SPM package to our target.
// ---------------------------------------------------------------------------
const IOS_MARKER = "[withMapboxNavIos]";

function iosPostInstallSnippet() {
  // NOTE: written into the Podfile as Ruby. `${...}` is escaped (\${) so this JS
  // template literal doesn't interpolate it — it must reach the shell verbatim.
  return `
    # ${IOS_MARKER} Attach mapbox-navigation-ios (SPM) to the ExpoMapboxNavigation
    # pod target and embed the Mapbox binary xcframeworks.
    mapbox_nav_version = '${NAV_VERSION}'
    installer.pods_project.targets.each do |target|
      if target.name.start_with?('Mapbox') || target.name == 'Turf'
        target.build_configurations.each do |cfg|
          cfg.build_settings['BUILD_LIBRARY_FOR_DISTRIBUTION'] = 'YES'
        end
      end
      if target.name == 'ExpoMapboxNavigation'
        target.build_configurations.each do |cfg|
          paths = ['$(inherited)', '$(BUILT_PRODUCTS_DIR)/PackageFrameworks', '$(BUILT_PRODUCTS_DIR)/..', '$(BUILT_PRODUCTS_DIR)/$(CONFIGURATION)$(EFFECTIVE_PLATFORM_NAME)']
          cfg.build_settings['FRAMEWORK_SEARCH_PATHS'] = paths
          cfg.build_settings['SWIFT_INCLUDE_PATHS'] = paths
        end
      end
    end
    emn_target = installer.pods_project.targets.find { |t| t.name == 'ExpoMapboxNavigation' }
    if emn_target
      project = installer.pods_project
      root = project.root_object
      pkg_ref = root.package_references&.find { |r| r.respond_to?(:repositoryURL) && r.repositoryURL == '${SPM_URL}' }
      unless pkg_ref
        pkg_ref = project.new(Xcodeproj::Project::Object::XCRemoteSwiftPackageReference)
        pkg_ref.repositoryURL = '${SPM_URL}'
        pkg_ref.requirement = { 'kind' => 'exactVersion', 'version' => mapbox_nav_version }
        root.package_references << pkg_ref
      end
      # Link ONLY the top-level product to the pod target; MapboxNavigationCore /
      # MapboxDirections / MapboxMaps resolve for compilation via the SPM
      # FRAMEWORK_SEARCH_PATHS above. Adding Core as a second product dep here
      # caused "Missing package product" — match the proven single-product recipe.
      unless emn_target.package_product_dependencies.any? { |d| d.product_name == 'MapboxNavigationUIKit' }
        dep = project.new(Xcodeproj::Project::Object::XCSwiftPackageProductDependency)
        dep.package = pkg_ref
        dep.product_name = 'MapboxNavigationUIKit'
        emn_target.package_product_dependencies << dep
        if emn_target.frameworks_build_phases
          bf = project.new(Xcodeproj::Project::Object::PBXBuildFile)
          bf.product_ref = dep
          emn_target.frameworks_build_phases.files << bf
        end
      end
    end
    user_proj = installer.aggregate_targets.first&.user_project
    if user_proj
      app_target = user_proj.targets.first
      mapbox_fws = %w[MapboxCommon MapboxCoreMaps MapboxNavigationNative]
      embed_name = 'EmbedMapboxFrameworks'
      unless app_target.shell_script_build_phases.any? { |p| p.name == embed_name }
        phase = app_target.new_shell_script_build_phase(embed_name)
        phase.shell_script = "FRAMEWORKS_DIR=\\"\${BUILT_PRODUCTS_DIR}/\${FRAMEWORKS_FOLDER_PATH}\\"\\nmkdir -p \\"\${FRAMEWORKS_DIR}\\"\\nfor fw in #{mapbox_fws.join(' ')}; do\\n  SRC=\\"\${BUILT_PRODUCTS_DIR}/\${fw}.framework\\"\\n  if [ -d \\"\${SRC}\\" ]; then\\n    cp -R \\"\${SRC}\\" \\"\${FRAMEWORKS_DIR}/\\"\\n    codesign --force --sign \\"\${EXPANDED_CODE_SIGN_IDENTITY}\\" \\"\${FRAMEWORKS_DIR}/\${fw}.framework\\"\\n  fi\\ndone\\n"
      end
      strip_name = 'StripXCFrameworkSignatures'
      unless app_target.shell_script_build_phases.any? { |p| p.name == strip_name }
        phase = app_target.new_shell_script_build_phase(strip_name)
        phase.shell_script = "DERIVED_ROOT=\\"\${BUILD_DIR%/Build/*}\\"\\nfind \\"\${DERIVED_ROOT}\\" -name \\"*.xcframework-*.signature\\" -delete 2>/dev/null\\nexit 0\\n"
      end
      user_proj.save
    end
`;
}

function withMapboxNavIos(config) {
  return withDangerousMod(config, [
    "ios",
    (cfg) => {
      const podfile = path.join(cfg.modRequest.platformProjectRoot, "Podfile");
      let src = fs.readFileSync(podfile, "utf8");
      if (src.includes(IOS_MARKER)) return cfg;
      const snippet = iosPostInstallSnippet();
      // Insert at the END of the post_install block (right after
      // react_native_post_install), matching the proven recipe. Inserting at the
      // START (before react_native_post_install / the rnmapbox hook) left the SPM
      // package reference unresolved → "Missing package product".
      const endAnchor = /(ccache_enabled\?\(podfile_properties\),\n\s*\))\s*\n(  end\nend)/;
      if (endAnchor.test(src)) {
        src = src.replace(endAnchor, (_m, p1, p2) => `${p1}\n${snippet}\n${p2}`);
      } else {
        // Fallback: right after the post_install opener.
        src = src.replace(/post_install do \|installer\|/, (l) => `${l}\n${snippet}`);
      }
      fs.writeFileSync(podfile, src);
      return cfg;
    }
  ]);
}

function withPublicTokenInfoPlist(config, publicToken) {
  if (!publicToken) return config;
  return withInfoPlist(config, (cfg) => {
    if (!cfg.modResults.MBXAccessToken) cfg.modResults.MBXAccessToken = publicToken;
    return cfg;
  });
}

// The Nav SDK's RouteVoiceController.verifyBackgroundAudio() HARD-ASSERTS (crash,
// EXC_BREAKPOINT) unless the app declares the `audio` background mode. `location`
// is needed for background guidance. Ensure both, idempotently.
function withNavBackgroundModes(config) {
  return withInfoPlist(config, (cfg) => {
    const modes = new Set(cfg.modResults.UIBackgroundModes || []);
    modes.add("audio");
    modes.add("location");
    cfg.modResults.UIBackgroundModes = Array.from(modes);
    return cfg;
  });
}

// ---------------------------------------------------------------------------
// Android — Maven repo + credentials, version pin, token resource, permissions.
// ---------------------------------------------------------------------------
const ANDROID_MARKER = "// [expo-mapbox-navigation]";

function withMapboxNavAndroidRepo(config) {
  return withProjectBuildGradle(config, (cfg) => {
    if (cfg.modResults.contents.includes(ANDROID_MARKER)) return cfg;
    cfg.modResults.contents += `
${ANDROID_MARKER}
allprojects {
  repositories {
    maven {
      url 'https://api.mapbox.com/downloads/v2/releases/maven'
      authentication { create('basic', BasicAuthentication) }
      credentials {
        username = 'mapbox'
        password = project.findProperty('MAPBOX_DOWNLOADS_TOKEN') ?: System.getenv('MAPBOX_DOWNLOADS_TOKEN') ?: ''
      }
    }
  }
  // No resolutionStrategy needed: the Nav module declares the -ndk27 nav
  // artifacts (see its build.gradle), so it and @rnmapbox/maps both resolve the
  // same -ndk27 Maps/common core (${MAPS_VERSION}) — no duplicate classes. Do NOT
  // blanket-redirect com.mapbox.* to -ndk27: shared sub-modules like
  // com.mapbox.common:logger / :loader have no -ndk27 variant and would 404.
}
`;
    return cfg;
  });
}

function withDownloadTokenGradleProperty(config, downloadToken) {
  if (!downloadToken) return config;
  return withGradleProperties(config, (cfg) => {
    const existing = cfg.modResults.find(
      (i) => i.type === "property" && i.key === "MAPBOX_DOWNLOADS_TOKEN"
    );
    if (existing) {
      existing.value = downloadToken;
    } else {
      cfg.modResults.push({ type: "property", key: "MAPBOX_DOWNLOADS_TOKEN", value: downloadToken });
    }
    return cfg;
  });
}

function withPublicTokenStringResource(config, publicToken) {
  if (!publicToken) return config;
  return withStringsXml(config, (cfg) => {
    cfg.modResults = AndroidConfig.Strings.setStringItem(
      [{ _: publicToken, $: { name: "mapbox_access_token", translatable: "false" } }],
      cfg.modResults
    );
    return cfg;
  });
}

const withMapboxNavPermissions = (config) =>
  AndroidConfig.Permissions.withPermissions(config, [
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_LOCATION",
    "android.permission.POST_NOTIFICATIONS"
  ]);

// ---------------------------------------------------------------------------
module.exports = function withMapboxNavigation(config, props = {}) {
  const publicToken =
    props.publicToken ||
    process.env.EXPO_PUBLIC_MAPBOX_TOKEN ||
    process.env.MAPBOX_PUBLIC_TOKEN ||
    "";
  const downloadToken =
    props.downloadToken ||
    process.env.MAPBOX_DOWNLOAD_TOKEN ||
    process.env.RNMAPBOX_MAPS_DOWNLOAD_TOKEN ||
    "";

  // iOS
  config = withMapboxNavIos(config);
  config = withPublicTokenInfoPlist(config, publicToken);
  config = withNavBackgroundModes(config);
  // Android
  config = withMapboxNavAndroidRepo(config);
  config = withDownloadTokenGradleProperty(config, downloadToken);
  config = withPublicTokenStringResource(config, publicToken);
  config = withMapboxNavPermissions(config);

  return config;
};
