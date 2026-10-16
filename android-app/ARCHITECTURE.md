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
| Fond de carte | **MapLibre Native** + **[OpenFreeMap](https://openfreemap.org)** | Open-source, sans clé, sans quota |
| Alertes officielles (option) | Météo-France vigilance via [portail API](https://portail-api.meteofrance.fr) | Gratuit avec compte (clé) |

> Limites : Open-Meteo est gratuit pour un usage non commercial (< 10 000 appels/jour par IP — chaque téléphone a son IP, donc pas de souci). Si l'app devient commerciale, prendre l'abonnement API ou auto-héberger Open-Meteo (open-source).

## Carte de prévision de précipitations
Aucune API gratuite ne fournit des **tuiles de prévision** de pluie prêtes à l'emploi. Approche retenue :
1. **Passé (−2 h → maintenant)** : tuiles radar RainViewer superposées sur MapLibre.
2. **Futur (maintenant → +48 h)** : on génère nous-mêmes une grille autour de l'utilisateur
   - grille ex. 15×15 points (≈ 3 km de pas, ~45 km de côté) ;
   - **un seul appel** Open-Meteo multi-coordonnées (`latitude=a,b,c…&longitude=…`) avec `minutely_15=precipitation` et le modèle AROME HD ;
   - rendu en couche *heatmap*/raster colorée dans MapLibre, avec un curseur temporel (pas de 15 min) et animation.
3. Évolution possible : petit service optionnel qui convertit les GRIB2 AROME de [data.gouv.fr / Météo-France](https://meteo.data.gouv.fr) en tuiles PNG pour une carte nationale fine.

## Stack Android
- **Kotlin**, **Jetpack Compose** + Material 3, minSdk 26
- **MVVM + Clean Architecture légère** : `ui` → `domain` → `data`
- Réseau : **Retrofit + OkHttp + kotlinx.serialization**
- Cache : **Room** (prévisions) + **DataStore** (préférences)
- Arrière-plan : **WorkManager** (rafraîchissement, notifications pluie imminente)
- Widget : **Jetpack Glance**
- Localisation : `LocationManager` natif (évite Google Play Services → compatible F-Droid)
- Carte : **MapLibre Android SDK**
- DI : manuelle au départ (`AppContainer`), Hilt si l'app grossit

```
com.namm.meteo
├── data
│   ├── remote        # API Open-Meteo, RainViewer (DTO)
│   ├── local         # Room, DataStore (à venir)
│   └── repository    # implémentations, mapping DTO -> domaine
├── domain            # modèles métier
└── ui                # écrans Compose + ViewModels
    ├── forecast      # écran principal
    ├── map           # carte précipitations (à venir)
    └── theme
```

## Feuille de route
1. ✅ Squelette + écran prévisions Open-Meteo (design ardoise, tests Roborazzi, CI + release APK, site Pages)
2. Localisation GPS + recherche de ville
3. Cache Room + mode hors-ligne
4. Carte radar RainViewer (MapLibre)
5. Carte de prévision précipitations (grille AROME 15 min)
6. Widget Glance + notification « pluie dans 15 min »
7. Publication F-Droid / Play Store
