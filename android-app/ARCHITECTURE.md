# YAMA — technical architecture

## Principles
- **Self-contained**: no backend of its own, the app calls free, key-less APIs directly.
- **Zero ads, zero trackers**: no Firebase / Google Analytics, no proprietary third-party SDK.
- **Offline first**: the last forecast is cached locally.
- **Bilingual**: English by default, French translation (`res/values-fr`); dates and numbers follow the user's language.

## Data sources

| Need | Service | Why |
|---|---|---|
| Forecasts (current, hourly, 7 days) | **[Open-Meteo](https://open-meteo.com)** `/v1/forecast` | Free, no key, open source, blends the best national models |
| Very precise precipitation (France) | Open-Meteo `best_match` (AROME HD 1.5 km) | **15 min** steps (`minutely_15`) |
| Fallback Europe / world | `icon_d2` (DWD, 2 km), `best_match` | Coverage outside France |
| City search | Open-Meteo Geocoding `/v1/search` | Free, no key, results in the user's language |
| Observed rain radar (recent past) | **[RainViewer](https://www.rainviewer.com/api.html)** tiles | Free worldwide radar tiles |
| Observed clouds | **EUMETSAT** WMS, Meteosat IR 10.8 µm (`SatelliteSource`) | Free, no key, one image every 15 min |
| Map background | **osmdroid** + standard **OpenStreetMap** tiles, filtered to match the theme | Light (pure Java), no key; follows the OSM usage policy (User-Agent = app name) |
| App updates | **GitHub Releases** API | New version detection, APK download |
| Fine nowcast (optional) | **Météo-France AROME-PI** (WCS `public-api.meteofrance.fr/public/aromepi/1.0/wcs/MF-NWP-HIGHRES-AROMEPI-001-FRANCE-WCS`) | 0.01° grid, 15 min, +6 h; free credential entered in Settings: API Key (JWT, `apikey` header), token (`Bearer`) or Application ID (OAuth client_credentials token obtained and renewed by the app through `portail-api.meteofrance.fr/token`); GeoTIFF decoded on the phone (`GeoTiff`) |

> Limits: Open-Meteo is free for non-commercial use (< 10,000 calls/day per IP — each phone has its own IP). If the app ever became commercial, take the API subscription or self-host Open-Meteo (open source).

## Rain forecast map
No free API provides ready-made rain **forecast tiles**. Implemented approach (`RainMapRepository`):
1. **Past (≈ −2 h → now)**: RainViewer radar tiles (`/256/{z}/{x}/{y}/2/1_1.png`), **zoom 7 at most** (beyond that RainViewer answers “zoom not supported”): `RadarOverlay` downloads them at zoom ≤ 7 and stretches them to the map zoom. Observed Meteosat clouds are drawn underneath.
2. **Future (now → +24 h, 15 min steps — the finest available for free)**: a 12 × 16 point grid covering **the visible area** of the map (+10 %);
   reloaded (after 400 ms of inactivity) only when the view leaves the grid or the spacing becomes 2× too coarse / fine (`RainMapRepository.needsNewGrid`). Each point counts as one call in the Open-Meteo quota (~190 per reload); the last 10 areas are kept 15 min in memory (`cachedFor`),
   **a single** multi-coordinate Open-Meteo call with `minutely_15=precipitation` and hourly `cloud_cover`,
   interpolated with a gaussian kernel then coloured by intensity level (sharp, round edges) by `ForecastGridOverlay`; the area boundary is dotted.
3. Time slider + animated playback: 6 frames per 15 min step (`MapViewModel.FRAME_MS` / `STEPS`, `Blend`); forecast → intermediate frames with an estimated overall motion (`FrameInterpolation`), computed off the main thread; radar → cross-fade (`RadarOverlay.next` / `mix`). Colour scale shared by radar and forecast (RainViewer “Universal Blue” palette, `RainLevel`) with a legend.
4. Wind layer (button at the top right, hidden by default): loaded on demand on a coarse grid (`windGrid`, `minutely_15`), arrows coloured by `WindLevel`; rain and clouds are hidden while it is shown.
5. Refresh: automatic when the data is > 10 min old (checked every minute while the screen is visible) + a tappable “Updated at HH:mm” pill. Home screen: pull down to refresh.
6. Possible evolution: convert AROME GRIB2 files from [meteo.data.gouv.fr](https://meteo.data.gouv.fr) into tiles for a nationwide map.

### Open-Meteo quota
- `OpenMeteoQuota`: a 429 from Open-Meteo blocks the map for ~60 s (Retry-After) to let the home screen through (`OpenMeteoBusyException`).
- Home screen failure: “data from HH:mm · reason” pill (no network / Open-Meteo overloaded / service unavailable, `StaleReason`) and automatic retry.
- Map with AROME-PI: Open-Meteo grid reduced to 8 × 10 points (beyond +6 h only); an incomplete forecast is not cached and is reloaded after 1 min.

### AROME-PI (when a key is entered)
- `AromePiSource`: GetCapabilities (remembered 5 min) → automatic choice of the precipitation layer (rate > 15 min accumulation > accumulation since the run) and of the latest run;
  then one GeoTIFF GetCoverage per 15 min step (8 in parallel) over the grid extent, downscaled to ≤ 160 px.
- Used only in France and when the view is ≤ 4° tall; beyond +6 h, or on failure, the Open-Meteo forecast takes over.
- Shared rendering (`renderForecast`): gaussian-kernel interpolation → round blobs, sharp edges.

## Android stack
- **Kotlin 2.4**, **Jetpack Compose** + Material 3, minSdk 26, compileSdk 37, targetSdk 35; AGP 9 (built-in Kotlin), Gradle 9
- **MVVM + layers**: `ui` (Compose + ViewModel) → `domain` (models) → `data` (remote / repository); manual DI in `MeteoApp`; navigation through a simple state (`MainActivity`)
- Network: **Retrofit 3 + OkHttp 5 + kotlinx.serialization**; a single client `Network.client` (shared pool, 20 MB HTTP disk cache, radar tiles kept 2 h), `Network.uncachedClient` for AROME-PI and the widget's OSM tiles
- Texts: string resources only (`values/strings.xml` in English, `values-fr/strings.xml`); text built in view models or workers goes through `UiText`; enum labels through `labelRes` (`ui/Text.kt`); per-app language declared in `res/xml/locales_config.xml`
- Rain map: grids as `FloatArray`, separable gaussian rendering (`forecastPixels`, no `exp()` per pixel), images cached by identity (`ForecastBitmaps`)
- Security: dependencies verified by checksum (`gradle/verification-metadata.xml`), Gradle wrapper verified (`distributionSha256Sum`), CI actions pinned by SHA, publishing in a separate job without secrets, update APK verified (package + signature) before installation, Météo-France key excluded from Android backups, position rounded to 0.01° for Open-Meteo
- Offline cache: last forecast as JSON (`ForecastCache`); preferences and saved places: **DataStore**
- Background: **WorkManager** (`RefreshWorker`, 15 min): cache, widgets, “rain within the hour” alert, update check
- Widgets: **Jetpack Glance** (`widget/`) — 6 variants with per-widget settings (`WidgetConfigActivity`, `WidgetConfigStore`); the map widget is an image (cached OSM background + clouds + rain, `WidgetMapRenderer`) recomputed by the worker
- Location: native `LocationManager` (fused / GPS / network), no Google Play Services → F-Droid compatible
- Map: **osmdroid**
- Updates: `UpdateManager` (GitHub Releases → DownloadManager → Android installer through FileProvider)

```
fr.jbrunet.yama
├── data
│   ├── arome         # AROME-PI (WCS, GeoTIFF)
│   ├── local         # SettingsStore (DataStore), ForecastCache
│   ├── location      # LocationProvider
│   ├── remote        # Open-Meteo (forecast, grid, geocoding), RainViewer, GitHub, Network
│   ├── repository    # Forecast, Place, RainMap
│   ├── satellite     # SatelliteSource (Meteosat)
│   └── update        # UpdateManager, UpdateController
├── domain            # models + rules (AppVersion, minutesUntilRain, WeatherCondition, SunPosition)
├── work              # RefreshWorker, Notifications
├── widget            # Glance widgets, settings, map renderer
└── ui                # forecast, map, settings, components, theme, Text (UiText, labels)
```

## Versions
`versionCode` = CI run number; `versionName` = `0.<1 + n/100>.<n % 100>`: the last digit stays < 100
(build 99 → 0.1.99, build 100 → 0.2.0). Versions are compared numerically (`AppVersion`).

## Roadmap
1. ✅ Skeleton + forecast screen, slate design, CI + APK release, Pages website
2. ✅ GPS position by default + saved places (city search)
3. ✅ Offline cache
4. ✅ RainViewer radar map
5. ✅ Precipitation forecast map (15 min grid)
6. ✅ Glance widget + “rain within the hour” notification
7. ✅ Automatic updates from GitHub Releases
8. ✅ Light theme (System / Dark / Light)
9. ✅ Widgets: mini, rain, day, 12 h, week, rain map, with per-widget settings
10. ✅ Colour themes: Slate (System / Dark / Light), Sky, Pastel, Aurora
11. ✅ Performance / security review: separable rain rendering, single HTTP client, dependency verification, hardened CI
12. ✅ Nature theme: home screen redesigned over a zen landscape following the weather and the time of day
13. ✅ Renamed to YAMA (`fr.jbrunet.yama`), English by default with a French translation (app and website)
14. Next: open-source licence, F-Droid release, Météo-France weather warnings, optional gentle landscape animation
