# Architecture technique — No Ad Mobile Météo

## Principes
- **Autonome** : aucun backend propre, l'app appelle directement des API gratuites sans clé.
- **Zéro pub, zéro tracker** : pas de Firebase/Google Analytics, pas de SDK tiers propriétaires.
- **Hors-ligne d'abord** : la dernière prévision est mise en cache localement.

## Sources de données recommandées

| Besoin | Service | Pourquoi |
|---|---|---|
| Prévisions (actuel, horaire, 7-16 j) | **[Open-Meteo](https://open-meteo.com)** `/v1/forecast` | Gratuit, sans clé, open-source, agrège les meilleurs modèles nationaux |
| Précipitations très précises (France) | Open-Meteo `models=meteofrance_arome_france_hd` | AROME HD 1,5 km, pas de **15 min** (`minutely_15`) sur ~48 h |
| Fallback Europe / monde | `icon_d2` (DWD, 2 km), `best_match` | Couverture hors France |
| Recherche de ville | Open-Meteo Geocoding `/v1/search` | Gratuit, sans clé |
| Radar de pluie observé (passé récent) | **[RainViewer](https://www.rainviewer.com/api.html)** tuiles | Tuiles radar mondiales gratuites |
| Fond de carte | **osmdroid** + tuiles **OpenStreetMap** standard, assombries par filtre de couleur | Léger (pur Java), sans clé ; respecter la politique d'usage OSM (User-Agent = nom du paquet) |
| Mises à jour de l'app | API **GitHub Releases** | Détection d'une nouvelle version, téléchargement de l'APK |
| Prévision immédiate fine (option) | **Météo-France AROME-PI** (WCS `public-api.meteofrance.fr/public/aromepi/1.0/wcs/MF-NWP-HIGHRES-AROMEPI-001-FRANCE-WCS`) | Maille 0,01°, 15 min, +6 h ; clé gratuite saisie dans Paramètres (en-tête `apikey`, ou jeton `Bearer`) ; GeoTIFF décodé sur le téléphone (`GeoTiff`) |
| Alertes officielles (option) | Météo-France vigilance via [portail API](https://portail-api.meteofrance.fr) | Gratuit avec compte (clé) |

> Limites : Open-Meteo est gratuit pour un usage non commercial (< 10 000 appels/jour par IP — chaque téléphone a son IP, donc pas de souci). Si l'app devient commerciale, prendre l'abonnement API ou auto-héberger Open-Meteo (open-source).

## Carte de prévision de précipitations
Aucune API gratuite ne fournit des **tuiles de prévision** de pluie prêtes à l'emploi. Approche implémentée (`RainMapRepository`) :
1. **Passé (≈ −2 h → maintenant)** : tuiles radar RainViewer (`/256/{z}/{x}/{y}/2/1_1.png`), **zoom 7 maximum** (au-delà RainViewer renvoie « zoom not supported ») : `RadarOverlay` les télécharge au zoom ≤ 7 et les étire au zoom de la carte.
2. **Futur (maintenant → +24 h, pas de 15 min — la plus fine dispo gratuitement)** : grille 12 × 16 points couvrant **la zone visible** de la carte (+10 %) ;
   rechargée (après 400 ms d'inactivité) seulement si la vue sort de la grille ou si la maille devient 2× trop grossière/fine (`RainMapRepository.needsNewGrid`). Chaque point compte comme un appel dans le quota Open-Meteo (10 000/jour) : ~190 par rechargement ; les 10 dernières zones sont gardées 15 min en mémoire (`cachedFor`) pour ne pas re-télécharger une zone déjà vue,
   **un seul appel** Open-Meteo multi-coordonnées avec `minutely_15=precipitation` (AROME HD en France via `best_match`),
   interpolée (bilinéaire, ×10) puis colorée par paliers d'intensité (contours nets) par `ForecastGridOverlay` ; la limite de la zone est tracée en pointillés.
3. Curseur temporel + lecture animée ; échelle de couleurs commune radar/prévision (palette RainViewer « Universal Blue » : bleus → jaune → rouge, `RainLevel`) avec légende.
4. Rafraîchissement : automatique si les données ont > 10 min (vérifié chaque minute tant que l'écran est visible) + pastille « Mis à jour à HH:mm » cliquable. Accueil : tirer vers le bas pour actualiser.
5. Évolution possible : convertir les GRIB2 AROME de [meteo.data.gouv.fr](https://meteo.data.gouv.fr) en tuiles pour une carte nationale.

### AROME-PI (si une clé est saisie)
- `AromePiSource` : GetCapabilities (mémorisé 5 min) → choix automatique de la couche de précipitation (taux > cumul 15 min > cumul depuis le run) et du dernier run ;
  puis un GetCoverage GeoTIFF par pas de 15 min (4 en parallèle) sur l'emprise de la grille, réduit à ≤ 160 px.
- Utilisé seulement en France et si la vue fait ≤ 4° de haut ; au-delà de +6 h, ou en cas d'échec, la prévision Open-Meteo prend le relais.
- Rendu commun (`renderForecast`) : interpolation à noyau gaussien → taches rondes, contours nets (plus de losanges).

## Stack Android
- **Kotlin**, **Jetpack Compose** + Material 3, minSdk 26
- **MVVM + couches** : `ui` → `domain` → `data`, navigation par état simple (`MainActivity`)
- Réseau : **Retrofit + OkHttp + kotlinx.serialization** (`Network`)
- Cache hors-ligne : dernière prévision en JSON (`ForecastCache`) ; préférences et lieux enregistrés : **DataStore**
- Arrière-plan : **WorkManager** (`RefreshWorker`, 30 min) : cache, widget, alerte « pluie dans l'heure », vérification de mise à jour
- Widget : **Jetpack Glance** (`WeatherWidget`)
- Localisation : `LocationManager` natif (fused/GPS/réseau), sans Google Play Services → compatible F-Droid
- Carte : **osmdroid**
- Mise à jour : `UpdateManager` (GitHub Releases → DownloadManager → installateur Android via FileProvider)

```
com.namm.meteo
├── data
│   ├── remote        # Open-Meteo (prévision, grille, géocodage), RainViewer, GitHub
│   ├── local         # SettingsStore (DataStore), ForecastCache
│   ├── location      # LocationProvider
│   ├── update        # UpdateManager
│   └── repository    # Forecast, Place, RainMap
├── domain            # modèles + règles (AppVersion, minutesUntilRain, WeatherCondition)
├── work              # RefreshWorker, Notifications
├── widget            # WeatherWidget (Glance)
└── ui                # forecast, map, settings, components, theme
```

## Versions
`versionCode` = numéro de run CI ; `versionName` = `0.<1 + n/100>.<n % 100>` : le dernier chiffre reste < 100
(build 99 → 0.1.99, build 100 → 0.2.0). Les versions sont comparées numériquement (`AppVersion`).

## Feuille de route
1. ✅ Squelette + écran prévisions, design ardoise, CI + release APK, site Pages
2. ✅ Position GPS par défaut + lieux enregistrés (recherche de ville)
3. ✅ Cache hors-ligne
4. ✅ Carte radar RainViewer
5. ✅ Carte de prévision précipitations (grille 15 min)
6. ✅ Widget Glance + notification « pluie dans l'heure »
7. ✅ Mise à jour automatique depuis GitHub Releases
8. À venir : publication F-Droid, alertes vigilance Météo-France, thème clair
