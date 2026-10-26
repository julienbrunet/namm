package com.namm.meteo.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.foundation.shape.RoundedCornerShape
import com.namm.meteo.ui.theme.DarkSlate
import com.namm.meteo.ui.theme.LightSlate
import com.namm.meteo.ui.theme.ThemeMode
import com.namm.meteo.ui.theme.ThemeStyle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.namm.meteo.data.local.Settings
import com.namm.meteo.data.local.WidgetBackground
import androidx.compose.foundation.layout.Box
import com.namm.meteo.data.location.LocationProvider
import com.namm.meteo.domain.Place
import com.namm.meteo.ui.components.RoundIconButton
import com.namm.meteo.ui.components.SectionLabel
import com.namm.meteo.ui.components.TileShape
import com.namm.meteo.ui.theme.Slate
import com.namm.meteo.ui.theme.screenBackground

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val updateStatus by viewModel.updateStatus.collectAsStateWithLifecycle()
    val aromeStatus by viewModel.aromeStatus.collectAsStateWithLifecycle()

    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result -> if (result.values.any { it }) viewModel.chooseCurrentLocation() }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.setRainAlerts(granted)
    }

    val hasLocationPermission = viewModel.hasLocationPermission()
    SettingsContent(
        settings = settings,
        search = search,
        versionName = viewModel.versionName,
        updateStatus = updateStatus,
        onBack = onBack,
        onQueryChange = viewModel::onQueryChange,
        onChoose = { viewModel.choose(it); onBack() },
        onChooseCurrentLocation = {
            if (hasLocationPermission) viewModel.chooseCurrentLocation()
            else locationPermission.launch(LocationProvider.PERMISSIONS)
            onBack()
        },
        onRemove = viewModel::remove,
        onRainAlerts = { enabled ->
            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.setRainAlerts(enabled)
            }
        },
        onAutoUpdate = viewModel::setAutoUpdate,
        onCheckUpdate = { viewModel.checkUpdate() },
        aromeStatus = aromeStatus,
        onSaveAromeKey = { viewModel.testAndSaveAromeKey(it) },
        onRemoveAromeKey = { viewModel.removeAromeKey() },
        onTheme = viewModel::setTheme,
        onStyle = viewModel::setStyle,
    )
}

@Composable
fun SettingsContent(
    settings: Settings,
    search: SearchState,
    versionName: String,
    updateStatus: String?,
    onBack: () -> Unit = {},
    onQueryChange: (String) -> Unit = {},
    onChoose: (Place) -> Unit = {},
    onChooseCurrentLocation: () -> Unit = {},
    onRemove: (Place) -> Unit = {},
    onRainAlerts: (Boolean) -> Unit = {},
    onAutoUpdate: (Boolean) -> Unit = {},
    onCheckUpdate: () -> Unit = {},
    aromeStatus: String? = null,
    onSaveAromeKey: (String) -> Unit = {},
    onRemoveAromeKey: () -> Unit = {},
    onTheme: (ThemeMode) -> Unit = {},
    onStyle: (ThemeStyle) -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxSize()
            .screenBackground()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Retour", onBack)
            Spacer(Modifier.width(16.dp))
            Text("Paramètres", style = MaterialTheme.typography.headlineLarge, color = Slate.Primary)
        }

        Spacer(Modifier.height(28.dp))
        SectionLabel("LIEU AFFICHÉ SUR L'ACCUEIL")
        Card {
            PlaceRow(
                icon = Icons.Outlined.MyLocation,
                title = "Ma position",
                subtitle = "GPS du téléphone, jamais partagé",
                selected = settings.useLocation,
                onClick = onChooseCurrentLocation,
            )
            settings.savedPlaces.forEach { place ->
                HorizontalDivider(color = Slate.TileHighlight)
                PlaceRow(
                    icon = Icons.Outlined.Place,
                    title = place.name,
                    subtitle = place.detail,
                    selected = !settings.useLocation && settings.place.sameAs(place),
                    onClick = { onChoose(place) },
                    onRemove = { onRemove(place) },
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel("AJOUTER UN LIEU")
        OutlinedTextField(
            value = search.query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Rechercher une ville…", color = Slate.Muted) },
            leadingIcon = { Icon(Icons.Outlined.Search, null, tint = Slate.Secondary) },
            trailingIcon = {
                if (search.loading) CircularProgressIndicator(Modifier.size(18.dp), color = Slate.Secondary, strokeWidth = 2.dp)
            },
            singleLine = true,
            shape = TileShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Slate.Tile,
                unfocusedContainerColor = Slate.Tile,
                focusedBorderColor = Slate.TileHighlight,
                unfocusedBorderColor = Slate.Tile,
                focusedTextColor = Slate.Primary,
                unfocusedTextColor = Slate.Primary,
                cursorColor = Slate.Rain,
            ),
        )
        search.error?.let {
            Text(it, color = Slate.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp))
        }
        if (search.results.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Card {
                search.results.forEachIndexed { i, place ->
                    if (i > 0) HorizontalDivider(color = Slate.TileHighlight)
                    Column(Modifier.fillMaxWidth().clickable { onChoose(place) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(place.name, color = Slate.Primary, style = MaterialTheme.typography.titleMedium)
                        place.detail?.let { Text(it, color = Slate.Secondary, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel("NOTIFICATIONS")
        Card {
            SwitchRow(null, "Alerte pluie imminente", "Prévient quand la pluie arrive dans l'heure", settings.rainAlerts, onRainAlerts)
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel("APPARENCE")
        StyleSelector(settings.style, onStyle)
        if (settings.style == ThemeStyle.ARDOISE) {
            Spacer(Modifier.height(10.dp))
            ThemeSelector(settings.theme, onTheme)
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel("WIDGETS")
        Text(
            "Fond et couleur du texte se règlent pour chaque widget : appui long sur le widget → Paramètres.",
            color = Slate.Secondary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        BackgroundRefreshCard()

        Spacer(Modifier.height(24.dp))
        SectionLabel("CARTE DE PLUIE · MÉTÉO-FRANCE")
        AromeKeyCard(settings.aromeApiKey, aromeStatus, onSaveAromeKey, onRemoveAromeKey)

        Spacer(Modifier.height(24.dp))
        SectionLabel("MISES À JOUR")
        Card {
            SwitchRow(null, "Mise à jour automatique", "Télécharge les nouvelles versions publiées sur GitHub", settings.autoUpdate, onAutoUpdate)
            HorizontalDivider(color = Slate.TileHighlight)
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Version $versionName", color = Slate.Primary, style = MaterialTheme.typography.titleMedium)
                    updateStatus?.let { Text(it, color = Slate.Secondary, style = MaterialTheme.typography.bodyMedium) }
                }
                TextButton(onClick = onCheckUpdate) { Text("Vérifier", color = Slate.Rain) }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "Aucune publicité, aucun pistage. Données Open-Meteo, Météo-France (AROME-PI) et RainViewer, fond de carte © contributeurs OpenStreetMap.",
            color = Slate.Muted,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Sélecteur segmenté générique (texte seul). */
@Composable
private fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(TileShape).background(Slate.Tile).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isSelected) Slate.TileHighlight else Slate.Tile)
                    .clickable { onSelect(option) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    color = if (isSelected) Slate.Primary else Slate.Secondary,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Choix du thème : une pastille d'aperçu (fond + tuile) au-dessus du nom. */
@Composable
private fun StyleSelector(current: ThemeStyle, onSelect: (ThemeStyle) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(TileShape).background(Slate.Tile).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ThemeStyle.entries.forEach { style ->
            val selected = style == current
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (selected) Slate.TileHighlight else Slate.Tile)
                    .clickable { onSelect(style) }
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                StyleSwatch(style)
                Spacer(Modifier.height(8.dp))
                Text(
                    style.label,
                    color = if (selected) Slate.Primary else Slate.Secondary,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Aperçu rond d'un thème : son fond, avec une tuile et une touche de couleur. */
@Composable
private fun StyleSwatch(style: ThemeStyle) {
    // Ardoise : moitié sombre, moitié claire (le mode se choisit en dessous).
    val palette = style.palette(ThemeMode.DARK, systemDark = true)
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(
                when {
                    style == ThemeStyle.ARDOISE ->
                        Brush.linearGradient(0.5f to DarkSlate.background, 0.5f to LightSlate.background)
                    palette.heroBrush != null -> palette.heroBrush
                    // Halos du fond (Aurore) autour de la couleur de fond.
                    palette.glows.size >= 2 -> Brush.linearGradient(
                        listOf(palette.glows[1].color.copy(alpha = 1f), palette.background, palette.glows[0].color.copy(alpha = 1f)),
                    )
                    else -> palette.backgroundBrush
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(if (style == ThemeStyle.PASTEL) palette.background else palette.rain),
        )
    }
}

/** Sélecteur segmenté Système / Sombre / Clair. */
@Composable
private fun ThemeSelector(current: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(TileShape).background(Slate.Tile).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ThemeMode.entries.forEach { mode ->
            val selected = mode == current
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (selected) Slate.TileHighlight else Slate.Tile)
                    .clickable { onSelect(mode) }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    when (mode) {
                        ThemeMode.SYSTEM -> Icons.Outlined.Contrast
                        ThemeMode.DARK -> Icons.Outlined.DarkMode
                        ThemeMode.LIGHT -> Icons.Outlined.LightMode
                    },
                    null,
                    tint = if (selected) Slate.Primary else Slate.Secondary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    mode.label,
                    color = if (selected) Slate.Primary else Slate.Secondary,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

/** Clé d'API AROME-PI : sans clé, la carte utilise Open-Meteo. */
@Composable
private fun AromeKeyCard(savedKey: String, status: String?, onSave: (String) -> Unit, onRemove: () -> Unit) {
    var key by rememberSaveable(savedKey) { mutableStateOf(savedKey) }
    var visible by rememberSaveable { mutableStateOf(false) }
    Card {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (savedKey.isBlank()) "Prévision Open-Meteo (par défaut)" else "Prévision AROME-PI activée",
                color = Slate.Primary,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Avec une clé gratuite du portail portail-api.meteofrance.fr (abonnement « AROME-PI »), " +
                    "la carte affiche la prévision immédiate de Météo-France : maille 1,3 km, 15 min, jusqu'à +6 h, en France.",
                color = Slate.Secondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Clé d'API (API Key)", color = Slate.Muted) },
                singleLine = true,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            if (visible) "Masquer" else "Afficher",
                            tint = Slate.Secondary,
                        )
                    }
                },
                shape = TileShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Slate.Background,
                    unfocusedContainerColor = Slate.Background,
                    focusedBorderColor = Slate.TileHighlight,
                    unfocusedBorderColor = Slate.Background,
                    focusedTextColor = Slate.Primary,
                    unfocusedTextColor = Slate.Primary,
                    cursorColor = Slate.Rain,
                ),
            )
            status?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Slate.Secondary, style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (savedKey.isNotBlank()) TextButton(onClick = onRemove) { Text("Retirer", color = Slate.Muted) }
                TextButton(onClick = { onSave(key) }, enabled = key.isNotBlank()) {
                    Text("Tester et enregistrer", color = Slate.Rain)
                }
            }
        }
    }
}

@Composable
private fun PlaceRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) Slate.Rain else Slate.Secondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Slate.Primary, style = MaterialTheme.typography.titleMedium)
            subtitle?.let { Text(it, color = Slate.Secondary, style = MaterialTheme.typography.bodyMedium) }
        }
        if (selected) Icon(Icons.Outlined.Check, "Affiché", tint = Slate.Rain, modifier = Modifier.padding(horizontal = 8.dp).size(20.dp))
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.DeleteOutline, "Supprimer", tint = Slate.Muted, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(TileShape).background(Slate.Tile)) { content() }
}

@Composable
private fun SwitchRow(icon: ImageVector?, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        icon?.let { Icon(it, null, tint = Slate.Secondary, modifier = Modifier.size(20.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, color = Slate.Primary, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = Slate.Secondary, style = MaterialTheme.typography.bodyMedium)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Slate.Background,
                checkedTrackColor = Slate.Rain,
                uncheckedThumbColor = Slate.Secondary,
                uncheckedTrackColor = Slate.TileHighlight,
                uncheckedBorderColor = Slate.TileHighlight,
            ),
        )
    }
}


/**
 * Android (surtout Samsung, Xiaomi…) retarde fortement les tâches de fond des applis « optimisées » :
 * les widgets restent alors figés. Propose de lever l'optimisation de batterie pour l'appli.
 */
@Composable
private fun BackgroundRefreshCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val power = context.getSystemService(android.os.PowerManager::class.java)
    var unrestricted by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName))
    }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        unrestricted = power.isIgnoringBatteryOptimizations(context.packageName)
        onPauseOrDispose { }
    }
    Spacer(Modifier.height(10.dp))
    Card {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = !unrestricted) {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            android.net.Uri.parse("package:${context.packageName}"),
                        ),
                    )
                }
            }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Actualisation en arrière-plan", color = Slate.Primary, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (unrestricted) "Autorisée : les widgets se mettent à jour toutes les 15 min"
                    else "Limitée par l'économie de batterie : les widgets peuvent rester figés plusieurs heures. Appuyer pour autoriser.",
                    color = if (unrestricted) Slate.Secondary else Slate.Rain,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
