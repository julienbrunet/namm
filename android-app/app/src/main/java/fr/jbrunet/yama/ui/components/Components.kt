package fr.jbrunet.yama.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Dehaze
import androidx.compose.material.icons.outlined.FilterDrama
import androidx.compose.material.icons.outlined.Thunderstorm
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import fr.jbrunet.yama.domain.WeatherCondition
import fr.jbrunet.yama.ui.theme.Slate

val TileShape = RoundedCornerShape(18.dp)

@Composable
fun Pill(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Slate.Secondary,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Slate.TileHighlight)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Slate.Muted,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier.padding(bottom = 10.dp, start = 2.dp),
    )
}

/** Discreet round button, like the player controls of the mock-up. */
@Composable
fun RoundIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(Slate.Tile)) {
        IconButton(onClick = onClick) {
            Icon(icon, description, tint = Slate.Primary, modifier = Modifier.size(20.dp))
        }
    }
}

fun WeatherCondition.icon(): ImageVector = when (this) {
    WeatherCondition.CLEAR -> Icons.Outlined.WbSunny
    WeatherCondition.PARTLY_CLOUDY -> Icons.Outlined.FilterDrama
    WeatherCondition.CLOUDY -> Icons.Outlined.Cloud
    WeatherCondition.FOG -> Icons.Outlined.Dehaze
    WeatherCondition.DRIZZLE -> WeatherIcons.Drizzle
    WeatherCondition.RAIN -> WeatherIcons.Rain
    WeatherCondition.SNOW -> Icons.Outlined.AcUnit
    WeatherCondition.STORM -> Icons.Outlined.Thunderstorm
}
