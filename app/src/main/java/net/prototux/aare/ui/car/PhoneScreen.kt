package net.prototux.aare.ui.car

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallMissed
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.prototux.aare.R
import net.prototux.aare.car.Calls
import net.prototux.aare.host.CarRuntime
import net.prototux.aare.media.Placeholder
import net.prototux.aare.ui.theme.LocalCarColors
import net.prototux.aare.ui.theme.LocalCarDimens

/** One number to call: a recent call or a contact. */
private class Callee(val name: String, val number: String, val detail: String, val type: Int = 0)

private enum class PhoneTab(val labelRes: Int, val icon: ImageVector) {
    FAVORITES(R.string.phone_tab_favorites, Icons.Rounded.Star),
    RECENTS(R.string.phone_tab_recents, Icons.Rounded.History),
    CONTACTS(R.string.phone_tab_contacts, Icons.Rounded.Person),
    KEYPAD(R.string.phone_tab_keypad, Icons.Rounded.Dialpad),
}

private val PERMISSIONS = listOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG,
    Manifest.permission.READ_CONTACTS, Manifest.permission.READ_PHONE_STATE)

/**
 * Calls from the car, like Android Auto's phone app: favourite contacts as big tiles, recent
 * calls, every contact by letter and a keypad (parked only), picked from a column of big buttons
 * on the side; a call in progress takes the whole screen (2026-11-11). Calls are placed by
 * Android's telephony, so their sound goes through the car's Bluetooth hands-free link as usual.
 */
@Composable
internal fun PhoneScreen(runtime: CarRuntime, modifier: Modifier) {
    val context = LocalContext.current
    val driving = runtime.ui.drivingRestricted
    if (PERMISSIONS.any { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
        Placeholder(stringResource(R.string.phone_permissions_placeholder), loading = false,
            icon = Icons.Rounded.Call, modifier = modifier.background(MaterialTheme.colorScheme.background))
        return
    }
    // Opens on the favourites when there are some, else on the recent calls.
    val favorites by produceState<List<Callee>?>(null) { value = withContext(Dispatchers.IO) { contacts(context, starred = true) } }
    var picked by remember { mutableStateOf<PhoneTab?>(null) }
    val tab = picked ?: if (favorites?.isEmpty() == true) PhoneTab.RECENTS else PhoneTab.FAVORITES
    var reload by remember { mutableIntStateOf(0) }
    // While a call is on, show it with a hang-up button; poll: no listener survives the screen anyway.
    val inCall by produceState(false) {
        while (true) { value = Calls.state(context) != TelephonyManager.CALL_STATE_IDLE; delay(1_500) }
    }
    LaunchedEffect(inCall) { if (!inCall) reload++ }
    val callingTitle = context.getString(R.string.phone_notice_calling_title)
    val call: (Callee) -> Unit = { c ->
        // An empty tel: places no call; it would only show a "Calling" banner with no name.
        if (c.number.isNotBlank()) {
            Calls.place(context, c.number)
            runtime.notice(callingTitle, c.name.ifBlank { c.number })
        }
    }

    if (inCall) {
        FullCall(runtime.ui.call, modifier.background(MaterialTheme.colorScheme.background)) { Calls.end(context) }
        return
    }
    Row(modifier.background(MaterialTheme.colorScheme.background).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        // The tabs as a column of big buttons: easier to hit from the driver's seat than a row of pills.
        Column(Modifier.width(132.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PhoneTab.entries.forEach { t ->
                if (t != PhoneTab.KEYPAD || !driving) TabButton(t, tab == t, Modifier.weight(1f).fillMaxWidth()) { picked = t }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (if (driving && tab == PhoneTab.KEYPAD) PhoneTab.RECENTS else tab) {
                PhoneTab.FAVORITES -> FavoriteGrid(favorites, call)
                PhoneTab.RECENTS -> {
                    val recents by produceState<List<Callee>?>(null, reload) { value = withContext(Dispatchers.IO) { recents(context) } }
                    CalleeList(recents, stringResource(R.string.phone_no_recent_calls), call)
                }
                PhoneTab.CONTACTS -> {
                    val contacts by produceState<List<Callee>?>(null, reload) { value = withContext(Dispatchers.IO) { contacts(context, starred = false) } }
                    ContactList(contacts, call)
                }
                PhoneTab.KEYPAD -> Keypad { number -> call(Callee("", number, "")) }
            }
        }
    }
}

@Composable
private fun TabButton(tab: PhoneTab, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Column(modifier.clip(RoundedCornerShape(22.dp)).background(bg).clickable(onClick = onClick).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(tab.icon, null, tint = fg, modifier = Modifier.size(32.dp))
        Text(stringResource(tab.labelRes), style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A round badge with the name's first letter, coloured by the name (the same person, the same colour). */
@Composable
private fun Avatar(name: String, size: androidx.compose.ui.unit.Dp) {
    val color = AVATAR_COLORS[name.hashCode().mod(AVATAR_COLORS.size)]
    Box(Modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        val letter = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase()
        if (letter != null) Text(letter, color = Color.White, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Bold)
        else Icon(Icons.Rounded.Person, null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
    }
}

private val AVATAR_COLORS = listOf(Color(0xFF5C6BC0), Color(0xFF00897B), Color(0xFFD81B60), Color(0xFFEF6C00), Color(0xFF6D4C41), Color(0xFF3949AB), Color(0xFF7CB342), Color(0xFF8E24AA))

/** The call in progress, the whole screen: who, for how long, and one big button to hang up. */
@Composable
private fun FullCall(call: CarCall?, modifier: Modifier, onHangUp: () -> Unit) {
    val name = call?.name ?: stringResource(R.string.phone_call_in_progress)
    val since = call?.sinceMs
    val elapsed by produceState(0L, since) {
        while (since != null) { value = (System.currentTimeMillis() - since) / 1000; delay(1_000) }
    }
    Row(modifier.fillMaxSize().padding(32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Avatar(name, 150.dp)
        Spacer(Modifier.width(36.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(stringResource(if (call?.ringing == true) R.string.phone_incoming_call else R.string.phone_call_in_progress),
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(name, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (since != null && call.ringing.not()) AccentText("%d:%02d".format(elapsed / 60, elapsed % 60),
                MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.headlineMedium)
        }
        Spacer(Modifier.width(36.dp))
        if (Calls.canEnd) Box(Modifier.size(120.dp).clip(CircleShape).background(LocalCarColors.current.decline).clickable(onClick = onHangUp), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.CallEnd, stringResource(R.string.phone_action_hang_up), tint = LocalCarColors.current.onCall, modifier = Modifier.size(56.dp))
        }
    }
}

/** The starred contacts as big tiles: one touch calls. */
@Composable
private fun FavoriteGrid(list: List<Callee>?, onCall: (Callee) -> Unit) {
    when {
        list == null -> Placeholder(stringResource(R.string.common_loading), loading = true)
        list.isEmpty() -> Placeholder(stringResource(R.string.phone_no_favorites), loading = false, icon = Icons.Rounded.Star)
        else -> LazyVerticalGrid(GridCells.Adaptive(190.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(list) { c ->
                Column(Modifier.clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onCall(c) }.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(c.name.ifBlank { c.number }, 76.dp)
                    Spacer(Modifier.height(8.dp))
                    Text(c.name.ifBlank { c.number }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(c.detail.substringBefore(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

/** Every contact, by letter, with the letters down the side to jump to one. */
@Composable
private fun ContactList(list: List<Callee>?, onCall: (Callee) -> Unit) {
    when {
        list == null -> Placeholder(stringResource(R.string.common_loading), loading = true)
        list.isEmpty() -> Placeholder(stringResource(R.string.phone_no_contacts), loading = false, icon = Icons.Rounded.Person)
        else -> {
            fun letter(c: Callee) = c.name.trim().firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() } ?: '#'
            // Where each letter's first contact is in the list.
            val starts = remember(list) { LinkedHashMap<Char, Int>().apply { list.forEachIndexed { i, c -> putIfAbsent(letter(c), i) } } }
            val listState = rememberLazyListState()
            val scope = rememberCoroutineScope()
            Row(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.weight(1f).fillMaxHeight(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(list) { c -> CalleeRow(c, avatar = true, onCall = onCall) }
                }
                // Up to about a dozen letters fit as car-sized targets: with more, every second one shows.
                BoxWithConstraints(Modifier.width(64.dp).fillMaxHeight().padding(start = 8.dp)) {
                    val fit = (maxHeight.value / 44f).toInt().coerceAtLeast(1)
                    val step = (starts.size + fit - 1) / fit
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                        starts.entries.filterIndexed { i, _ -> i % step.coerceAtLeast(1) == 0 }.forEach { (ch, index) ->
                            Box(Modifier.fillMaxWidth().weight(1f).padding(vertical = 2.dp).clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable { scope.launch { listState.scrollToItem(index) } }, contentAlignment = Alignment.Center) {
                                Text(ch.toString(), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalleeList(list: List<Callee>?, empty: String, onCall: (Callee) -> Unit) {
    when {
        list == null -> Placeholder(stringResource(R.string.common_loading), loading = true)
        list.isEmpty() -> Placeholder(empty, loading = false, icon = Icons.Rounded.Person)
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(list) { c -> CalleeRow(c, avatar = false, onCall = onCall) }
        }
    }
}

/** One row to call: a contact with its [avatar], or a recent call with its direction. */
@Composable
private fun CalleeRow(c: Callee, avatar: Boolean, onCall: (Callee) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = LocalCarDimens.current.touchTarget).clip(RoundedCornerShape(20.dp))
            .clickable { onCall(c) }.padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val missed = c.type == CallLog.Calls.MISSED_TYPE
        if (avatar) Avatar(c.name.ifBlank { c.number }, 52.dp)
        else {
            val icon = when (c.type) {
                CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> Icons.AutoMirrored.Rounded.CallMissed
                CallLog.Calls.OUTGOING_TYPE -> Icons.AutoMirrored.Rounded.CallMade
                CallLog.Calls.INCOMING_TYPE -> Icons.AutoMirrored.Rounded.CallReceived
                else -> Icons.Rounded.Person
            }
            // Read out by screen readers: the arrow alone says nothing to them.
            val kind = when (c.type) {
                CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> stringResource(R.string.phone_call_missed)
                CallLog.Calls.OUTGOING_TYPE -> stringResource(R.string.phone_call_outgoing)
                CallLog.Calls.INCOMING_TYPE -> stringResource(R.string.phone_call_incoming)
                else -> null
            }
            Box(Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(icon, kind, tint = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name.ifBlank { c.number }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            if (c.detail.isNotBlank()) Text(c.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Icon(Icons.Rounded.Call, stringResource(R.string.common_call), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 12.dp))
    }
}

@Composable
private fun Keypad(onCall: (String) -> Unit) {
    var number by remember { mutableStateOf("") }
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(number.ifEmpty { " " }, style = MaterialTheme.typography.displaySmall, maxLines = 1)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(LocalCarDimens.current.touchTarget).clip(CircleShape).clickable { number = number.dropLast(1) }, contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Rounded.Backspace, stringResource(R.string.phone_keypad_delete))
                }
                Box(Modifier.size(LocalCarDimens.current.touchTarget + 8.dp).clip(CircleShape).background(LocalCarColors.current.accept)
                    .clickable(enabled = number.isNotEmpty()) { onCall(number) }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Call, stringResource(R.string.common_call), tint = LocalCarColors.current.onCall)
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            keys.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { k ->
                        Box(Modifier.weight(1f).height(64.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { if (number.length < 20) number += k }, contentAlignment = Alignment.Center) {
                            Text(k, style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- call history and contacts

private fun recents(context: Context): List<Callee> = runCatching {
    val out = ArrayList<Callee>()
    val seen = HashSet<String>()
    context.contentResolver.query(CallLog.Calls.CONTENT_URI,
        arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE),
        null, null, "${CallLog.Calls.DATE} DESC")?.use { c ->
        while (c.moveToNext() && out.size < 30) {
            // Withheld and unknown callers have an empty number, not null: nothing to call back.
            val number = c.getString(0)?.takeIf { it.isNotBlank() } ?: continue
            if (!seen.add(number)) continue
            val ago = DateUtils.getRelativeTimeSpanString(c.getLong(3), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
            out += Callee(c.getString(1).orEmpty(), number, ago, c.getInt(2))
        }
    }
    out
}.getOrDefault(emptyList())

/** The contacts with a phone number, by name: the favourite ([starred]) ones, or all of them. */
private fun contacts(context: Context, starred: Boolean): List<Callee> = runCatching {
    fun query(starred: Boolean): List<Callee> {
        val out = ArrayList<Callee>()
        val seen = HashSet<String>()
        context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.LABEL),
            if (starred) "${ContactsContract.CommonDataKinds.Phone.STARRED} = 1" else null, null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC")?.use { c ->
            while (c.moveToNext() && out.size < 2000) {
                val name = c.getString(0).orEmpty()
                val number = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                if (!seen.add(name + number.filter(Char::isDigit))) continue
                val type = ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, c.getInt(2), c.getString(3)).toString()
                out += Callee(name, number, "$type · $number")
            }
        }
        return out
    }
    query(starred)
}.getOrDefault(emptyList())
