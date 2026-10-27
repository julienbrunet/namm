# CLAUDE.md — No Ad Mobile Météo (NAMM)

Application Android de météo locale **sans publicité ni pistage**, basée sur des API gratuites.
Toute la conversation avec l'utilisateur se fait **en français**.

## Organisation du dépôt
- `android-app/` — app Android (Kotlin, Jetpack Compose, Retrofit + kotlinx.serialization). Architecture : `android-app/ARCHITECTURE.md` (sources météo, feuille de route).
- `site/` — page web statique de présentation (HTML/CSS pur, sans dépendance), déployée sur GitHub Pages.
- `.github/workflows/android.yml` — CI : tests, captures d'écran, APK release, GitHub Release.
- `.github/workflows/pages.yml` — déploiement de `site/` sur GitHub Pages.

## Méthode de travail (à suivre à chaque conversation)
1. Développer sur la branche de travail fournie par la session (sinon une branche `claude/<sujet>`).
2. **Compiler et tester en local dans la sandbox** avant tout push :
   ```bash
   cd android-app
   ./gradlew testDebugUnitTest recordRoborazziDebug assembleRelease
   ```
3. **Regarder les captures** générées dans `android-app/app/screenshots/` (Read sur les PNG) pour vérifier le rendu visuel.
4. Quand tout est vert : commit, push de la branche, puis **merge sur `main`** (fast-forward ou merge commit) et push de `main`.
   Le push sur `main` déclenche la CI qui publie l'APK en release `v<versionName>` (voir « Release & signature »).
   Vérifier ensuite le run CI (outils `mcp__github__actions_*`) et, s'il ne démarre pas, le lancer via `workflow_dispatch`.
5. Si un écran présenté change, régénérer ses images JPEG dans `site/` (captures `nature_*`, `carte`, `accueil*`, `widget_journee`, `widget_12h` réduites avec PIL, qualité 86) ; le site (https://julienbrunet.github.io/namm/) reprend le style du thème Nature.
6. **Terminer en montrant les captures d'écran** à l'utilisateur (app + page web si modifiée).
7. Mettre à jour la feuille de route de `ARCHITECTURE.md` et ce fichier si la méthode évolue.

## Mise en place de la sandbox (une fois par session)
Pas de KVM → pas d'émulateur : l'UI est vérifiée par **Robolectric + Roborazzi** (rendu Compose sur JVM).
```bash
# SDK Android
mkdir -p ~/android-sdk/cmdline-tools && cd ~/android-sdk/cmdline-tools
curl -sSLo t.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q t.zip && mv cmdline-tools latest && rm t.zip
export ANDROID_HOME=~/android-sdk
yes | latest/bin/sdkmanager --licenses >/dev/null
latest/bin/sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
echo "sdk.dir=$HOME/android-sdk" > /home/user/namm/android-app/local.properties

# Maven Central renvoie souvent 429 depuis la sandbox : utiliser le miroir Google
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
- Open-Meteo répond parfois 429 depuis la sandbox (IP partagée, quota journalier) : réessayer plus tard ou travailler avec les fixtures.
- Captures de la page web : Playwright (`npm i playwright` dans le scratchpad, lancer Chromium avec `--no-sandbox`).

## Dépendances (sécurité de la chaîne d'approvisionnement)
- Gradle vérifie chaque artefact téléchargé (`android-app/gradle/verification-metadata.xml`) et la distribution Gradle (`distributionSha256Sum`).
- Après tout ajout ou changement de version (dont les PR Dependabot), régénérer les checksums **avec un cache Gradle vierge**
  (sinon des métadonnées déjà en cache, comme des BOM, sont omises et la CI échoue), puis relire le diff :
  ```bash
  cd android-app
  export GRADLE_USER_HOME=$(mktemp -d) && mkdir -p $GRADLE_USER_HOME/init.d && cp ~/.gradle/init.d/mirror.gradle $GRADLE_USER_HOME/init.d/
  ./gradlew --write-verification-metadata sha256 printVersionName testDebugUnitTest recordRoborazziDebug assembleRelease help
  unset GRADLE_USER_HOME
  ```
- Mise à jour du wrapper : `./gradlew wrapper --gradle-version X --gradle-distribution-sha256-sum <sha publié sur services.gradle.org>`.
- Actions GitHub figées par SHA de commit (commentaire `# vX.Y.Z`) ; le job `release` est le seul à pouvoir écrire et ne reçoit pas les secrets de signature.
- Préférer des versions publiées depuis au moins deux semaines (Dependabot : `cooldown` 14 jours).

## Conventions
- Code et commentaires en français pour les libellés UI ; noms de code en anglais.
- Couches : `ui` (Compose + ViewModel) → `domain` (modèles) → `data` (remote / repository). DI manuelle dans `MeteoApp`.
- Design **sobre « ardoise »** (voir `ui/theme/Theme.kt`) : thèmes `DarkSlate` (fond `#353A45`, tuiles `#2F333D`, bleu pluie `#8FB3D9`) et `LightSlate` (fond `#E9EBEF`, tuiles `#F8F9FA`, bleu `#3F7CC4`), choix Système/Sombre/Clair dans Paramètres. Toujours passer par `Slate.X` (couleurs du thème courant, lisibles seulement dans un composable : les capturer avant un `Canvas`). Grandes typos fines, pastilles discrètes. Thèmes colorés en plus d'Ardoise (`ThemeStyle`, réglage « Apparence ») : `SkySlate` (Ciel, dégradé bleu, tuiles en verre dépoli), `PastelSlate` (fond crème, carte héros dégradée, tuiles teintées), `AuroraSlate` (indigo avec halos corail/violet, accents cyan) ; `NatureSlate` (Nature : accueil `NatureForecastContent` — paysage zen en bandeau d’en-tête (texte à gauche, montagnes et reflet visibles à droite, fondu vers le fond de page), puis les tuiles des autres thèmes + « Lever et coucher du soleil » ; paysage dessiné en code, `NatureScene` — image calculée pixel par pixel en basse définition puis agrandie avec lissage (`SceneRenderer` : ciel selon `sunPosition` aube/jour/crépuscule/nuit, nuages en bruit fractal vus en perspective, deux chaînes de montagnes dans la brume, lac qui reflète, voiles de pluie, brouillard ; étoiles, pluie fine et neige dessinées par-dessus) ; rendu réaliste et sobre, pas de formes vectorielles « dessin animé » ; aucune image embarquée ; captures `nature_*`) ; le mode Système/Sombre/Clair ne concerne qu'Ardoise. Fond d'écran via `Modifier.screenBackground()` (jamais `background(Slate.Background)`), dégradés/teintes via `Slate.HeroBrush`, `TileTints`, `CurveColors`, `ButtonBrush`. Les widgets gardent les palettes Ardoise (`ThemeStyle.widgetDark`). Les captures Roborazzi couvrent tous les thèmes. Le site reprend le style du thème Nature (ciel de crépuscule, montagnes et lac en SVG, tuiles en verre fumé).
- Aucune dépendance publicitaire, analytics ou Google Play Services (compatibilité F-Droid).
- Réseau : toujours `Network.client` / `Network.uncachedClient` (jamais `OkHttpClient()`), User-Agent `Network.userAgent`.
- Tests : fixtures dans `app/src/test/resources/` (`forecast.json`, `geocoding.json` réelles — bloc `hourly` de `forecast.json` complété ; `grid.json` au format multi-points) ; tout nouvel écran a un test Roborazzi (`ScreenshotTest`, application neutre `android.app.Application` pour éviter WorkManager).
- Accueil = position GPS par défaut ; lieux préenregistrés gérés dans Paramètres (`SettingsStore`). La carte utilise le même lieu que l'accueil.
- Carte : fond OSM filtré (`FadedMapFilter` gris estompé en thème clair, `SlateMapFilter` en sombre, carte et widget) ; Open-Meteo par défaut ; AROME-PI (Météo-France) si une clé est enregistrée dans Paramètres. Aucune clé dans le code ni le dépôt. Nuages : satellite Meteosat IR 10,8 µm (WMS EUMETSAT gratuit, `SatelliteSource`, voile dont l’opacité suit la clarté IR) sous le radar et dans le widget ; couverture nuageuse Open-Meteo (`cloud_cover` horaire de la grille, `CloudLayer`) sous la prévision. Vent : couche à part (bouton en haut à droite, masquée par défaut), chargée à la demande (`windGrid`, grille grossière, minutely_15), flèches colorées `WindLevel` ; quand elle est affichée, pluie et nuages sont masqués. Animation fluide : 6 images par pas de 15 min (`MapViewModel.FRAME_MS`/`STEPS`, `Blend`) ; prévision → images intermédiaires avec déplacement global estimé (`FrameInterpolation`), calculées à la volée hors du thread principal ; radar → fondu enchaîné (`RadarOverlay.next`/`mix`).
- Widgets (Glance, `widget/`) : Mini 2×1, Pluie 4×1, Journée 4×2, 12 h 4×2 (12 colonnes = 2 demi-lignes de 6), Semaine 4×3, Carte de pluie 4×3 (image ≈ 50 km au format réel du widget, dessinée par `WidgetMapRenderer` dans `RefreshWorker` toutes les 15 min). Rendus testés dans `WidgetScreenshotTest` (GlanceRemoteViews → PNG). Réglages propres à chaque widget (heures affichées 6/12 pour Journée et 12 h, couleur du fond Auto/Clair/Sombre, opacité Opaque/70 %/40 %/Transparent, texte Auto/Blanc/Foncé) via `WidgetConfigActivity` (pose du widget ou appui long → Paramètres), stockés par appWidgetId dans `WidgetConfigStore`. Design commun : en-tête météo actuelle + date/heure (TextClock via `AndroidRemoteViews`, dans une boîte de largeur fixe), textes de prévision en gras, pourcentages dans la couleur du texte (pas en bleu), mise en page compacte collée en haut (TopStart, sauf Mini/Pluie centrés), prévisions horaires en gras 14–17 sp. Une ligne Glance accepte au plus 10 enfants. En test, la vue doit être attachée à une activité pour que les TextClock s'affichent. Les classes de widgets sont protégées de la fusion R8 (`proguard-rules.pro`, vérifié en CI) : les tests tournent en debug, non minifié, et ne voient pas ce genre de bug. Fraîcheur : worker 15 min + `onUpdate` système (30 min) + ouverture de l’appli → `Widgets.refreshIfStale` ; carte « Actualisation en arrière-plan » (exemption batterie) dans Paramètres. Carte de pluie : décodage et rendu des images hors du thread principal (`Dispatchers.Default`, `ForecastBitmaps`) ; radar affiché d’abord puis prévision, avancement (`LoadProgress`) sur l’écran d’attente et la pastille.
- Navigation : barre flottante en bas de l'accueil (carte de pluie, paramètres) ; actualisation de l'accueil en tirant vers le bas.

## Release & signature
- `versionCode` = `BUILD_NUMBER` (numéro de run CI) ; `versionName` = `0.<1 + n/100>.<n % 100>` : le dernier chiffre reste < 100 (build 100 → 0.2.0). Source unique : `app/build.gradle.kts` (`./gradlew -q printVersionName`).
- L'app se met à jour seule depuis GitHub Releases (`UpdateManager`) : la release doit contenir un asset `.apk` et un tag `v<versionName>`. Les versions sont comparées numériquement (`AppVersion`), jamais en texte.
- Signature release via secrets GitHub `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`. Sans ces secrets, l'APK est signé avec la clé debug du runner (installable, mais les mises à jour nécessitent de désinstaller).
- Lien de téléchargement stable : `https://github.com/julienbrunet/namm/releases/latest/download/no-ad-meteo.apk`.
