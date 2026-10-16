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
5. Si l'écran principal change, copier `android-app/app/screenshots/accueil.png` dans `site/` pour mettre à jour la page web.
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

## Conventions
- Code et commentaires en français pour les libellés UI ; noms de code en anglais.
- Couches : `ui` (Compose + ViewModel) → `domain` (modèles) → `data` (remote / repository). DI manuelle dans `MeteoApp`.
- Design **sobre « ardoise »** (voir `ui/theme/Theme.kt`, objet `Slate`) : fond `#353A45`, tuiles arrondies `#2F333D`, texte blanc/gris, grandes typos fines, pastilles discrètes. Pas de couleurs vives hormis le bleu pluie `#8FB3D9`. Le site reprend la même palette.
- Aucune dépendance publicitaire, analytics ou Google Play Services (compatibilité F-Droid).
- Tests : fixtures dans `app/src/test/resources/` (`forecast.json`, `geocoding.json` réelles ; `grid.json` au format multi-points) ; tout nouvel écran a un test Roborazzi (`ScreenshotTest`, application neutre `android.app.Application` pour éviter WorkManager).
- Accueil = position GPS par défaut ; lieux préenregistrés gérés dans Paramètres (`SettingsStore`).

## Release & signature
- `versionCode` = `BUILD_NUMBER` (numéro de run CI) ; `versionName` = `0.<1 + n/100>.<n % 100>` : le dernier chiffre reste < 100 (build 100 → 0.2.0). Source unique : `app/build.gradle.kts` (`./gradlew -q printVersionName`).
- L'app se met à jour seule depuis GitHub Releases (`UpdateManager`) : la release doit contenir un asset `.apk` et un tag `v<versionName>`. Les versions sont comparées numériquement (`AppVersion`), jamais en texte.
- Signature release via secrets GitHub `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`. Sans ces secrets, l'APK est signé avec la clé debug du runner (installable, mais les mises à jour nécessitent de désinstaller).
- Lien de téléchargement stable : `https://github.com/julienbrunet/namm/releases/latest/download/no-ad-meteo.apk`.
