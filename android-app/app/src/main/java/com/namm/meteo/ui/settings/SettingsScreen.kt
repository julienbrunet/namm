package com.namm.meteo.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Search
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
import com.namm.meteo.data.location.LocationProvider
import com.namm.meteo.domain.Place
import com.namm.meteo.ui.components.RoundIconButton
import com.namm.meteo.ui.components.SectionLabel
import com.namm.meteo.ui.components.TileShape
import com.namm.meteo.ui.theme.Slate

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val updateStatus by viewModel.updateStatus.collectAsStateWithLifecycle()

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
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Slate.Background)
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
            "Aucune publicité, aucun pistage. Données Open-Meteo et RainViewer, fond de carte © contributeurs OpenStreetMap.",
            color = Slate.Muted,
            style = MaterialTheme.typography.bodyMedium,
        )
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

