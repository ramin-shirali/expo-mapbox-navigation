require 'json'

package = JSON.parse(File.read(File.join(__dir__, '..', 'package.json')))

Pod::Spec.new do |s|
  s.name           = 'ExpoMapboxNavigation'
  s.version        = package['version']
  s.summary        = package['description']
  s.description    = package['description']
  s.license        = { :type => 'MIT' }
  s.author         = 'Black Crown Mobility'
  s.homepage       = 'https://blackcrownmobility.com'
  s.platforms      = { :ios => '15.1' }
  s.swift_version  = '5.9'
  s.source         = { git: '' }
  s.static_framework = true

  s.dependency 'ExpoModulesCore'

  # The Mapbox Navigation SDK (MapboxNavigationCore / MapboxNavigationUIKit) is
  # NOT declared here as a CocoaPods dependency — it is pulled via Swift Package
  # Manager and attached to this pod target by the config plugin
  # (app.plugin.js → withMapboxNavIos). That keeps a SINGLE MapboxMaps core
  # shared with @rnmapbox/maps (see NAVIGATION.md). The plugin also wires the
  # FRAMEWORK_SEARCH_PATHS so `import MapboxNavigationUIKit` resolves.
  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
    'SWIFT_COMPILATION_MODE' => 'wholemodule',
    # Mapbox v11 / Nav v3 ship arm64-only simulator slices.
    'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'x86_64'
  }

  # The Mapbox Nav SDK binary xcframeworks are dynamic; a static pod can't link
  # them, so the APP target must. (The config plugin's Podfile hook embeds +
  # signs them.) Mirrors the proven third-party recipe.
  s.user_target_xcconfig = {
    'OTHER_LDFLAGS' => '$(inherited) -framework "MapboxNavigationNative" -framework "MapboxCommon" -framework "MapboxCoreMaps"'
  }

  s.source_files = "**/*.{h,m,mm,swift,hpp,cpp}"
end
