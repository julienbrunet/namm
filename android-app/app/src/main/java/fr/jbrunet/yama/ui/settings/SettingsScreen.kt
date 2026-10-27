package fr.jbrunet.yama.ui.settings

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
import androidx.compose.ui.graphics.Color
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
import fr.jbrunet.yama.ui.theme.DarkSlate
import fr.jbrunet.yama.ui.theme.LightSlate
import fr.jbrunet.yama.ui.theme.ThemeMode
import fr.jbrunet.yama.ui.theme.ThemeStyle
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.jbrunet.yama.R
import fr.jbrunet.yama.data.local.Settings
import androidx.compose.foundation.layout.Box
import fr.jbrunet.yama.data.location.LocationProvider
import fr.jbrunet.yama.domain.Place
import fr.jbrunet.yama.ui.components.RoundIconButton
import fr.jbrunet.yama.ui.components.SectionLabel
import fr.jbrunet.yama.ui.components.TileShape
import fr.jbrunet.yama.ui.UiText
import fr.jbrunet.yama.ui.labelRes
import fr.jbrunet.yama.ui.resolve
import fr.jbrunet.yama.ui.theme.Slate
import fr.jbrunet.yama.ui.theme.screenBackground

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
    updateStatus: UiText?,
    onBack: () -> Unit = {},
    onQueryChange: (String) -> Unit = {},
    onChoose: (Place) -> Unit = {},
    onChooseCurrentLocation: () -> Unit = {},
    onRemove: (Place) -> Unit = {},
    onRainAlerts: (Boolean) -> Unit = {},
    onAutoUpdate: (Boolean) -> Unit = {},
    onCheckUpdate: () -> Unit = {},
    aromeStatus: UiText? = null,
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
            RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back), onBack)
            Spacer(Modifier.width(16.dp))
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineLarge, color = Slate.Primary)
        }

        Spacer(Modifier.height(28.dp))
        SectionLabel(stringResource(R.string.section_home_place))
        Card {
            PlaceRow(
                icon = Icons.Outlined.MyLocation,
                title = stringResource(R.string.my_location),
                subtitle = stringResource(R.string.my_location_detail),
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
        SectionLabel(stringResource(R.string.section_add_place))
        OutlinedTextField(
            value = search.query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.search_city), color = Slate.Muted) },
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
            Text(it.resolve(), color = Slate.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp))
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
        SectionLabel(stringResource(R.string.section_notifications))
        Card {
            SwitchRow(
                null,
                stringResource(R.string.rain_alert_title),
                stringResource(R.string.rain_alert_subtitle),
                settings.rainAlerts,
                onRainAlerts,
            )
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel(stringResource(R.string.section_appearance))
        StyleSelector(settings.style, onStyle)
        if (settings.style == ThemeStyle.SLATE) {
            Spacer(Modifier.height(10.dp))
            ThemeSelector(settings.theme, onTheme)
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel(stringResource(R.string.section_widgets))
        Text(
            stringResource(R.string.widgets_hint),
            color = Slate.Secondary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        BackgroundRefreshCard()

        Spacer(Modifier.height(24.dp))
        SectionLabel(stringResource(R.string.section_rain_map_meteo_france))
        AromeKeyCard(settings.aromeApiKey, aromeStatus, onSaveAromeKey, onRemoveAromeKey)

        Spacer(Modifier.height(24.dp))
        SectionLabel(stringResource(R.string.section_updates))
        Card {
            SwitchRow(
                null,
                stringResource(R.string.auto_update_title),
                stringResource(R.string.auto_update_subtitle),
                settings.autoUpdate,
                onAutoUpdate,
            )
            HorizontalDivider(color = Slate.TileHighlight)
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.version_name, versionName), color = Slate.Primary, style = MaterialTheme.typography.titleMedium)
                    updateStatus?.let { Text(it.resolve(), color = Slate.Secondary, style = MaterialTheme.typography.bodyMedium) }
                }
                TextButton(onClick = onCheckUpdate) { Text(stringResource(R.string.action_check), color = Slate.Rain) }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.settings_footer),
            color = Slate.Muted,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Theme picker: a preview swatch (background + tile) above the name. */
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
                    stringResource(style.labelRes),
                    color = if (selected) Slate.Primary else Slate.Secondary,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Round preview of a theme: its background, with a tile and a touch of colour. */
@Composable
private fun StyleSwatch(style: ThemeStyle) {
    // Slate: half dark, half light (the mode is chosen below).
    val palette = style.palette(ThemeMode.DARK, systemDark = true)
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(
                when {
                    style == ThemeStyle.SLATE ->
                        Brush.linearGradient(0.5f to DarkSlate.background, 0.5f to LightSlate.background)
                    palette.heroBrush != null -> palette.heroBrush
                    // Nature: dawn sky above the ridges.
                    palette.scenic -> Brush.verticalGradient(listOf(Color(0xFF2E3D6B), Color(0xFFA9839F), Color(0xFFF3C6A0)))
                    // Background glows (Aurora) around the background colour.
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

/** System / Dark / Light segmented selector. */
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
                    stringResource(mode.labelRes),
                    color = if (selected) Slate.Primary else Slate.Secondary,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

/** AROME-PI API key: without a key, the map uses Open-Meteo. */
@Composable
private fun AromeKeyCard(savedKey: String, status: UiText?, onSave: (String) -> Unit, onRemove: () -> Unit) {
    var key by rememberSaveable(savedKey) { mutableStateOf(savedKey) }
    var visible by rememberSaveable { mutableStateOf(false) }
    Card {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(if (savedKey.isBlank()) R.string.arome_default else R.string.arome_enabled),
                color = Slate.Primary,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.arome_explanation),
                color = Slate.Secondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.arome_key_hint), color = Slate.Muted) },
                singleLine = true,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            stringResource(if (visible) R.string.action_hide else R.string.action_show),
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
                Text(it.resolve(), color = Slate.Secondary, style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (savedKey.isNotBlank()) TextButton(onClick = onRemove) { Text(stringResource(R.string.action_remove), color = Slate.Muted) }
                TextButton(onClick = { onSave(key) }, enabled = key.isNotBlank()) {
                    Text(stringResource(R.string.action_test_and_save), color = Slate.Rain)
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
        if (selected) Icon(Icons.Outlined.Check, stringResource(R.string.place_shown), tint = Slate.Rain, modifier = Modifier.padding(horizontal = 8.dp).size(20.dp))
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.action_delete), tint = Slate.Muted, modifier = Modifier.size(20.dp))
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
 * Android (especially Samsung, Xiaomi…) heavily delays background work of “optimised” apps:
 * widgets then stay frozen. Offers to lift battery optimisation for the app.
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
                Text(stringResource(R.string.background_refresh_title), color = Slate.Primary, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(if (unrestricted) R.string.background_refresh_allowed else R.string.background_refresh_limited),
                    color = if (unrestricted) Slate.Secondary else Slate.Rain,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
