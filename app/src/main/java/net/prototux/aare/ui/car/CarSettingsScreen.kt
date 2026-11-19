package net.prototux.aare.ui.car

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.prototux.aare.R
import net.prototux.aare.Settings
import net.prototux.aare.media.Pill
import net.prototux.aare.ui.theme.LocalCarDimens

/**
 * The car's own settings app: what drivers change from the car, like Android Auto's. The rest
 * stays in AARE on the phone.
 */
@Composable
internal fun CarSettingsScreen(settings: Settings, modifier: Modifier) {
    var theme by remember { mutableStateOf(settings.carTheme) }
    var rail by remember { mutableStateOf(settings.railPosition) }
    var themeId by remember { mutableStateOf(settings.carThemeId) }
    var layoutId by remember { mutableStateOf(settings.carLayoutId) }
    var accent by remember { mutableStateOf(settings.carAccent) }
    var gtsItalic by remember { mutableStateOf(settings.gtsItalic) }
    var motion by remember { mutableStateOf(settings.carAnimations) }
    var corner by remember { mutableStateOf(settings.cornerCard) }
    var cardScale by remember { mutableStateOf(settings.cardScale) }
    var banners by remember { mutableStateOf(settings.messageBanners) }
    var aloud by remember { mutableStateOf(settings.readMessagesAloud) }
    var audio by remember { mutableStateOf(settings.carAudio) }
    var darkBar by remember { mutableStateOf(settings.darkBar) }
    var cards by remember { mutableStateOf(DashCard.parse(settings.dashboardCards)) }
    var passenger by remember { mutableStateOf(settings.ignoreDriving) }
    var uiScale by remember { mutableStateOf(settings.uiScalePercent) }
    LazyColumn(
        modifier.background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item { Text(stringResource(R.string.setup_app_entry_settings), style = MaterialTheme.typography.headlineSmall) }
        item {
            Choice(stringResource(R.string.setup_choice_colors), listOf(Settings.THEME_AUTO to stringResource(R.string.setup_choice_colors_auto), Settings.THEME_DARK to stringResource(R.string.setup_choice_dark), Settings.THEME_LIGHT to stringResource(R.string.setup_choice_light)), theme) {
                theme = it; settings.carTheme = it
            }
        }
        item {
            Choice(stringResource(R.string.setup_choice_bar), listOf(Settings.RAIL_AUTO to stringResource(R.string.setup_choice_bar_auto), Settings.RAIL_LEFT to stringResource(R.string.setup_choice_left), Settings.RAIL_RIGHT to stringResource(R.string.setup_choice_right), Settings.RAIL_BOTTOM to stringResource(R.string.setup_choice_bottom)), rail) {
                rail = it; settings.railPosition = it
            }
        }
        item {
            StyleChoices(
                themeId, layoutId, accent,
                choice = { title, options, selected, onSelect -> Choice(title, options, selected, onSelect) },
                onChange = { t, l -> themeId = t; layoutId = l; settings.carThemeId = t; settings.carLayoutId = l },
                onAccent = { accent = it; settings.carAccent = it },
            )
        }
        if (themeId == net.prototux.aare.ui.theme.GTS_THEME_ID) item {
            Choice(stringResource(R.string.setup_choice_gts_font), gtsFontChoices(), if (gtsItalic) "italic" else "upright") {
                gtsItalic = it == "italic"; settings.gtsItalic = gtsItalic
            }
        }
        item {
            Choice(stringResource(R.string.setup_choice_animations), animationChoices(), motion) {
                motion = it; settings.carAnimations = it
            }
        }
        item {
            Choice(stringResource(R.string.setup_choice_card_scale), cardScaleChoices(), cardScale) {
                cardScale = it; settings.cardScale = it
            }
        }
        item {
            Choice(stringResource(R.string.setup_choice_corner_card), cornerCardChoices(), corner) {
                corner = it; settings.cornerCard = it
            }
        }
        item {
            Choice(stringResource(R.string.setup_choice_ui_size), Settings.UI_SCALES.map { "$it" to "$it%" }, "$uiScale") {
                uiScale = it.toInt(); settings.uiScalePercent = uiScale
            }
        }
        item { SwitchRow(stringResource(R.string.setup_switch_dark_bar), darkBar) { darkBar = it; settings.darkBar = it } }
        item {
            DashboardCardsEditor(cards, LocalCarDimens.current.touchTarget) { cards = it; settings.dashboardCards = it.map(DashCard::id) }
        }
        item { SwitchRow(stringResource(R.string.setup_switch_passenger), passenger) { passenger = it; settings.ignoreDriving = it } }
        item { SwitchRow(stringResource(R.string.setup_switch_message_notifications), banners) { banners = it; settings.messageBanners = it } }
        item { SwitchRow(stringResource(R.string.msg_toggle_read_aloud_title), aloud) { aloud = it; settings.readMessagesAloud = it } }
        item { SwitchRow(stringResource(R.string.setup_switch_audio_next_connection), audio) { audio = it; settings.carAudio = it } }
    }
}

@Composable
private fun Choice(title: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(8.dp))
        Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            options.forEach { (value, label) -> Pill(label, selected = value == selected, onClick = { onSelect(value) }) }
        }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = LocalCarDimens.current.touchTarget).clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** The map and cards splits, for the phone's and the car's settings. */
@Composable
internal fun cardScaleChoices(): List<Pair<String, String>> = listOf(
    Settings.CARDS_MAP to stringResource(R.string.setup_cards_map),
    Settings.CARDS_BALANCED to stringResource(R.string.setup_cards_balanced),
    Settings.CARDS_BIG to stringResource(R.string.setup_cards_big),
)

/** The cards the corner over the player can show, for the phone's and the car's settings. */
@Composable
internal fun cornerCardChoices(): List<Pair<String, String>> = listOf(
    Settings.CORNER_CAR to stringResource(R.string.setup_corner_car),
    Settings.CORNER_CLOCK to stringResource(R.string.setup_corner_clock),
    Settings.CORNER_TURN to stringResource(R.string.setup_corner_turn),
    Settings.CORNER_TRIP to stringResource(R.string.setup_corner_trip),
    Settings.CORNER_SHORTCUTS to stringResource(R.string.setup_corner_shortcuts),
)

/** The animation levels, for the phone's and the car's settings. */
@Composable
internal fun animationChoices(): List<Pair<String, String>> = listOf(
    Settings.MOTION_FULL to stringResource(R.string.setup_animations_full),
    Settings.MOTION_REDUCED to stringResource(R.string.setup_animations_reduced),
    Settings.MOTION_OFF to stringResource(R.string.setup_animations_off),
)
