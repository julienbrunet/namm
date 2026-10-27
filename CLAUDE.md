# CLAUDE.md — YAMA (Yet Another Meteo App)

Android app for local weather, **ad-free and tracker-free**, built on free APIs.
All conversation with the user happens **in French**. The app and its documentation are in English
(French translation in `values-fr`); this file stays in English like the rest of the docs.

## Repository layout
- `android-app/` — Android app (Kotlin, Jetpack Compose, Retrofit + kotlinx.serialization). Architecture: `android-app/ARCHITECTURE.md` (weather sources, roadmap).
- `site/` — static presentation website (plain HTML/CSS, no dependency), deployed on GitHub Pages.
- `.github/workflows/android.yml` — CI: tests, screenshots, release APK, GitHub Release.
- `.github/workflows/pages.yml` — deploys `site/` to GitHub Pages.
- `.github/workflows/security.yml` — free analyses built into GitHub (Security tab): CodeQL (Kotlin compiled in release + workflows), Gradle dependency graph (`releaseRuntimeClasspath`, feeds Dependabot alerts; generated read-only, uploaded by a separate job), dependency review on PRs. Badges at the top of `README.md`.

## Working method (follow in every conversation)
1. Develop on the working branch the session provides (otherwise a `claude/<topic>` branch).
2. **Build and test locally in the sandbox** before any push:
   ```bash
   cd android-app
   ./gradlew testDebugUnitTest recordRoborazziDebug assembleRelease
   ```
3. **Look at the screenshots** generated in `android-app/app/screenshots/` (English) and `android-app/app/screenshots/fr/` (French) (Read on the PNGs) to check the visual rendering.
4. When everything is green: commit, push the branch, then **merge into `main`** (fast-forward or merge commit) and push `main`.
   The push to `main` triggers the CI that publishes the APK as release `v<versionName>` (see "Release & signing").
   Then check the CI run (`mcp__github__actions_*` tools) and, if it doesn't start, trigger it via `workflow_dispatch`.
5. If a shown screen changes, regenerate its JPEG images in `site/` (`nature_*`, `map`, `home*`, `widget_day`, `widget_12h` downscaled with PIL, quality 86) for both languages (`site/*.jpg` English, `site/fr/*.jpg` French); the site (https://julienbrunet.github.io/yama/) follows the Nature theme's style.
6. **Finish by showing the screenshots** to the user (app + web page if changed).
7. Update the roadmap in `ARCHITECTURE.md` and this file if the method evolves.

## Setting up the sandbox (once per session)
No KVM → no emulator: the UI is checked with **Robolectric + Roborazzi** (Compose rendering on the JVM).
```bash
# Android SDK
mkdir -p ~/android-sdk/cmdline-tools && cd ~/android-sdk/cmdline-tools
curl -sSLo t.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q t.zip && mv cmdline-tools latest && rm t.zip
export ANDROID_HOME=~/android-sdk
yes | latest/bin/sdkmanager --licenses >/dev/null
latest/bin/sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
echo "sdk.dir=$HOME/android-sdk" > /home/user/namm/android-app/local.properties

# Maven Central often returns 429 from the sandbox: use the Google mirror
mkdir -p ~/.gradle/init.d
cat > ~/.gradle/init.d/mirror.gradle <<'G'
def mirror = { repos -> repos.maven { url "https://maven-central.storage-download.googleapis.com/maven2/" } }
beforeSettings { s ->
  s.pluginManagement.repositories { mirror(delegate) }
  s.dependencyResolutionManagement.repositories { mirror(delegate) }
}
G
echo "robolectricRepo=https://maven-central.storage-download.googleapis.com/maven2" >> ~/.gradle/gradle.properties
```
- Open-Meteo sometimes answers 429 from the sandbox (shared IP, daily quota): retry later or work with the fixtures.
- Website screenshots: Playwright (`npm i playwright` in the scratchpad, launch Chromium with `--no-sandbox`).

## Dependencies (supply-chain security)
- Gradle verifies every downloaded artifact (`android-app/gradle/verification-metadata.xml`) and the Gradle distribution (`distributionSha256Sum`).
- After any addition or version change (including Dependabot PRs), regenerate the checksums **with a clean Gradle cache**
  (otherwise already-cached metadata, such as BOMs, is omitted and CI fails), then review the diff:
  ```bash
  cd android-app
  export GRADLE_USER_HOME=$(mktemp -d) && mkdir -p $GRADLE_USER_HOME/init.d && cp ~/.gradle/init.d/mirror.gradle $GRADLE_USER_HOME/init.d/
  ./gradlew --write-verification-metadata sha256 printVersionName testDebugUnitTest recordRoborazziDebug assembleRelease help
  unset GRADLE_USER_HOME
  ```
- The `org.gradle:github-dependency-graph-gradle-plugin` plugin (the "Dependency graph" job in `security.yml`) has its checksums in this file;
  its version is pinned by `DEPENDENCY_GRAPH_PLUGIN_VERSION`. Don't delete the file before regenerating (the entry would be lost), and
  add new checksums if that version changes (init script from `gradle/actions`, task `:ForceDependencyResolutionPlugin_resolveAllDependencies`).
- Wrapper update: `./gradlew wrapper --gradle-version X --gradle-distribution-sha256-sum <sha published on services.gradle.org>`.
- GitHub Actions pinned by commit SHA (comment `# vX.Y.Z`); the `release` job is the only one allowed to write and does not receive the signing secrets.
- Prefer versions published at least two weeks ago (Dependabot: 14-day `cooldown`).

## Conventions
- Code and comments in English; UI text in string resources (`values/strings.xml` English default, `values-fr/strings.xml` French), accessed through `stringResource`/`getString` in the UI, or through `UiText` (`ui/Text.kt`) when built outside a composable (view models, workers); enum labels through the `labelRes` extensions in `ui/Text.kt`. Dates/times through `java.time` formatted with a pattern string resource (`date_long_pattern`, `clock_date_pattern`) and the current `Locale`; see `currentLocale()`/`formatDate()` in `ui/Text.kt`. Per-app language declared in `res/xml/locales_config.xml` (Android 13+).
- Layers: `ui` (Compose + ViewModel) → `domain` (models) → `data` (remote / repository). Manual DI in `MeteoApp`.
- **Sober "slate" design** (see `ui/theme/Theme.kt`): `DarkSlate` (background `#353A45`, tiles `#2F333D`, rain blue `#8FB3D9`) and `LightSlate` (background `#E9EBEF`, tiles `#F8F9FA`, blue `#3F7CC4`) themes, System/Dark/Light choice in Settings. Always go through `Slate.X` (colours of the current theme, readable only inside a composable: capture them before a `Canvas`). Large thin type, discreet pills. Colourful themes besides Slate (`ThemeStyle`, "Appearance" setting): `Sky` (blue gradient, frosted-glass tiles), `Pastel` (cream background, gradient hero card, tinted tiles), `Aurora` (indigo with coral/purple glows, cyan accents); `Nature` (home screen `NatureForecastContent` — zen landscape as a header band (text on the left, mountains and their reflection visible on the right, fading into the page background), then the other themes' tiles + "Sunrise and sunset"; landscape drawn in code, `NatureScene` — image computed pixel by pixel at low resolution then upscaled with smoothing (`SceneRenderer`: sky following `sunPosition` dawn/day/dusk/night, fractal-noise clouds seen in perspective, two mountain ranges in the mist, reflecting lake, rain veils, fog; stars, drizzle and snow drawn on top); realistic, sober rendering, no "cartoon" vector shapes; no embedded image; `nature_*` screenshots); Nature is the default theme (`ThemeStyle.DEFAULT`); the System/Dark/Light mode only applies to Slate. Screen background through `Modifier.screenBackground()` (never `background(Slate.Background)`), gradients/tints through `Slate.HeroBrush`, `TileTints`, `CurveColors`, `ButtonBrush`. Widgets keep the Slate palettes (`ThemeStyle.widgetDark`). Roborazzi screenshots cover every theme, in both languages. The website follows the Nature theme's style (dusk sky, SVG mountains and lake, smoked-glass tiles).
- No advertising, analytics or Google Play Services dependency (F-Droid compatibility).
- Network: always `Network.client` / `Network.uncachedClient` (never `OkHttpClient()`), User-Agent `Network.userAgent`.
- Tests: fixtures in `app/src/test/resources/` (`forecast.json`, `geocoding.json` are real responses — the `hourly` block of `forecast.json` was completed; `grid.json` in the multi-point format); every new screen has a Roborazzi test (`ScreenshotTest`/`FrenchScreenshotTest`, neutral `android.app.Application` to avoid WorkManager).
- Home screen = GPS position by default; saved places managed in Settings (`SettingsStore`). The map uses the same place as the home screen.
- Map: filtered OSM background (`FadedMapFilter` dimmed grey in the light theme, `SlateMapFilter` in dark, map and widget); Open-Meteo by default; AROME-PI (Météo-France) if a key is saved in Settings. No key in the code or the repository. Clouds: Meteosat IR 10.8 µm satellite (free EUMETSAT WMS, `SatelliteSource`, veil whose opacity follows the IR brightness) under the radar and in the widget; Open-Meteo cloud cover (hourly `cloud_cover` from the grid, `CloudLayer`) under the forecast. Wind: separate layer (button top right, hidden by default), loaded on demand (`windGrid`, coarse grid, minutely_15), arrows coloured by `WindLevel`; when shown, rain and clouds are hidden. Smooth animation: 6 frames per 15 min step (`MapViewModel.FRAME_MS`/`STEPS`, `Blend`); forecast → intermediate frames with an estimated overall motion (`FrameInterpolation`), computed on the fly off the main thread; radar → cross-fade (`RadarOverlay.next`/`mix`).
- Widgets (Glance, `widget/`): Mini 2×1, Rain 4×1, Day 4×2, 12 h 4×2 (12 columns = 2 half-rows of 6), Week 4×3, Rain map 4×3 (image ≈ 50 km in the widget's actual format, drawn by `WidgetMapRenderer` in `RefreshWorker` every 15 min). Rendering tested in `WidgetScreenshotTest`/`FrenchWidgetScreenshotTest` (GlanceRemoteViews → PNG). Per-widget settings (hours shown 6/12 for Day and 12 h, background colour Auto/Light/Dark, opacity Opaque/70 %/40 %/Transparent, text Auto/White/Dark) through `WidgetConfigActivity` (placing the widget or long-press → Settings), stored per appWidgetId in `WidgetConfigStore`. Shared design: current-weather header + date/time (TextClock through `AndroidRemoteViews`, in a fixed-width box), bold forecast text, percentages in the text colour (not blue), compact layout pinned to the top (TopStart, except Mini/Rain centred), hourly forecasts in bold 14–17 sp. A Glance row accepts at most 10 children. In tests, the view must be attached to an activity for the TextClocks to render. Widget classes are protected from R8 merging (`proguard-rules.pro`, checked in CI): tests run in debug, unminified, and don't see this kind of bug. Freshness: 15 min worker + system `onUpdate` (30 min) + opening the app → `Widgets.refreshIfStale`; "Background refresh" (battery exemption) card in Settings. Rain map: image decoding and rendering off the main thread (`Dispatchers.Default`, `ForecastBitmaps`); radar shown first, then the forecast, progress (`LoadProgress`) on the waiting screen and the pill.
- Navigation: floating bar at the bottom of the home screen (rain map, settings); pull down to refresh the home screen.

## Release & signing
- `versionCode` = `BUILD_NUMBER` (CI run number), always increasing: Android's internal install order, never shown to the user. `versionName` is classic semver (`major.minor.patch`), a plain constant in `app/build.gradle.kts` (`./gradlew -q printVersionName`) bumped **by hand** for each meaningful release — bump it whenever user-visible behaviour changes enough to warrant a new release.
- The app updates itself from GitHub Releases (`UpdateManager`): the release must contain a `.apk` asset and a `v<versionName>` tag, and `versionName` must be bumped before merging to `main` (`main`'s push publishes that exact tag) so the next CI run doesn't republish the same version. Versions are compared numerically (`AppVersion`), never as text.
- Release signing through GitHub secrets `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`. Without these secrets, the APK is signed with the runner's debug key (installable, but updates then require uninstalling first).
- Stable download link: `https://github.com/julienbrunet/yama/releases/latest/download/yama.apk`.
