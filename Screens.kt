@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.streamtv.iptv

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.*
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.material3.Text as M3Text

enum class Section(val icon: String, val label: String) {
    SEARCH("🔍", "Search"), LIVE("📺", "Live TV"), MOVIES("🎬", "Movies"), SERIES("🍿", "Series"),
    MATCHES("⚽", "Matches Center"), RADIO("📻", "Radio"), SOURCES("➕", "Add source"), SETTINGS("⚙", "Settings")
}

private val SPORT_RE = Regex("(?i)sport|bein|ssc|match|football|soccer|ligue|liga|premier|champions|كأس|رياض|مباريات|كرة")
typealias OpenFn = (List<ChannelEntity>, Int, Long) -> Unit

@Composable
fun HomeScreen(vm: MainViewModel, onPlay: (PlayRequest) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val theme by vm.theme.collectAsStateWithLifecycle()
    var section by remember { mutableStateOf(Section.LIVE) }
    var episodes by remember { mutableStateOf<List<ChannelEntity>?>(null) }
    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

    val open: OpenFn = { list, idx, resume ->
        val target = list[idx]
        if (target.kind == "series") scope.launch {
            val e = vm.episodes(target)
            if (e.isEmpty()) toast("No episodes found") else episodes = e
        } else onPlay(PlayRequest(list, idx, resume))
    }
    val trailer: (ChannelEntity) -> Unit = { c ->
        scope.launch {
            val t = vm.trailer(c)
            if (t == null) toast("No trailer available")
            else {
                val u = if (t.startsWith("http")) t else "https://www.youtube.com/watch?v=$t"
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }
            }
        }
    }

    NavigationDrawer(drawerContent = {
        Column(Modifier.fillMaxHeight().background(Color(0x99000000)).padding(12.dp), verticalArrangement = Arrangement.Center) {
            Section.values().forEach { s ->
                NavigationDrawerItem(selected = section == s, onClick = { section = s }, leadingContent = { Text(s.icon) }) { Text(s.label) }
            }
        }
    }) {
        Box(Modifier.fillMaxSize().background(theme.bg).padding(start = 80.dp, top = 20.dp, end = 16.dp)) {
            when (section) {
                Section.SEARCH -> SearchScreen(vm, open)
                Section.LIVE -> MediaRows(vm, "live", false, null, "No channels yet. Open the menu and add a source.", open, trailer)
                Section.MOVIES -> MediaRows(vm, "movie", true, null, "No movies. Add an Xtream source that includes VOD.", open, trailer)
                Section.SERIES -> MediaRows(vm, "series", true, null, "No series. Add an Xtream source that includes series.", open, trailer)
                Section.MATCHES -> Column {
                    Text("Matches Center", style = MaterialTheme.typography.headlineSmall)
                    Text("Live channels from sports categories", color = Color.Gray, modifier = Modifier.padding(bottom = 8.dp))
                    MediaRows(vm, "live", false, SPORT_RE, "No sports categories found in your sources.", open, trailer)
                }
                Section.RADIO -> MediaRows(vm, "radio", false, null, "No radio stations found (categories named Radio are detected).", open, trailer)
                Section.SOURCES -> SourcesScreen(vm)
                Section.SETTINGS -> SettingsScreen(vm)
            }
        }
    }
    episodes?.let { eps ->
        Dialog(onDismissRequest = { episodes = null }) {
            Box(Modifier.width(520.dp).heightIn(max = 460.dp).background(Color(0xEE111111)).padding(16.dp)) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(eps) { i, e -> Button(onClick = { episodes = null; onPlay(PlayRequest(eps, i)) }, modifier = Modifier.fillMaxWidth()) { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
                }
            }
        }
    }
}

@Composable
fun MediaRows(vm: MainViewModel, kind: String, hero: Boolean, only: Regex?, empty: String, open: OpenFn, onTrailer: (ChannelEntity) -> Unit) {
    val groups by remember(kind) { vm.groups(kind) }.collectAsStateWithLifecycle(emptyList())
    val count by vm.itemCount.collectAsStateWithLifecycle()
    val hist by vm.history.collectAsStateWithLifecycle(emptyList())
    val favs by vm.favorites.collectAsStateWithLifecycle(emptyList())
    val heroItems by produceState(emptyList<ChannelEntity>(), kind, count, hero) { value = if (hero) vm.featured(kind) else emptyList() }
    val shown = if (only != null) groups.filter { only.containsMatchIn(it) } else groups
    if (shown.isEmpty()) { Box(Modifier.fillMaxSize(), Alignment.Center) { Text(empty) }; return }
    val h = hist.filter { it.kind == kind }
    val f = favs.filter { it.kind == kind }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
        if (heroItems.isNotEmpty()) item { Hero(heroItems, vm, { open(listOf(it), 0, 0) }, onTrailer) }
        if (h.isNotEmpty()) item {
            val list = h.map { it.toItem() }
            ItemRow("Continue Watching", list, { i -> open(list, i, h[i].position) }, vm)
        }
        if (f.isNotEmpty()) item { ItemRow("My List", f, { i -> open(f, i, 0) }, vm) }
        items(shown) { g ->
            val list by remember(g) { vm.channels(kind, g) }.collectAsStateWithLifecycle(emptyList())
            ItemRow(g, list, { i -> open(list, i, 0) }, vm)
        }
    }
}

@Composable
fun Hero(items: List<ChannelEntity>, vm: MainViewModel, onWatch: (ChannelEntity) -> Unit, onTrailer: (ChannelEntity) -> Unit) {
    var i by remember { mutableIntStateOf(0) }
    val bg = MaterialTheme.colorScheme.background
    LaunchedEffect(items) { while (items.size > 1) { delay(8000); i = (i + 1) % items.size } }
    val cur = items.getOrNull(i % items.size) ?: return
    Box(Modifier.fillMaxWidth().height(250.dp).clip(RoundedCornerShape(12.dp))) {
        AsyncImage(model = cur.logo.ifBlank { null }, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(bg, bg.copy(alpha = 0.85f), Color.Transparent))))
        Column(Modifier.padding(24.dp).width(520.dp).align(Alignment.CenterStart), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(cur.name, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (cur.rating.isNotBlank() && cur.rating != "0") Text("★ ${cur.rating}", color = Color(0xFFF5C518))
                Text(cur.groupTitle, color = Color.LightGray)
            }
            if (cur.plot.isNotBlank()) Text(cur.plot.take(160), maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { onWatch(cur) }) { Text("Watch Now") }
                Button(onClick = { onTrailer(cur) }) { Text("Trailer") }
                Button(onClick = { vm.toggleFav(cur) }) { Text("My List") }
            }
        }
    }
}

@Composable
fun ItemRow(title: String?, items: List<ChannelEntity>, onOpen: (Int) -> Unit, vm: MainViewModel) {
    Column {
        if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(end = 48.dp, top = 8.dp, bottom = 8.dp)) {
            itemsIndexed(items) { i, c -> MediaCard(c, { onOpen(i) }, { vm.toggleFav(c) }) }
        }
    }
}

@Composable
fun MediaCard(c: ChannelEntity, onClick: () -> Unit, onFav: () -> Unit) {
    val ctx = LocalContext.current
    val poster = c.kind == "movie" || c.kind == "series"
    Card(
        onClick = onClick,
        onLongClick = { onFav(); Toast.makeText(ctx, "My List updated", Toast.LENGTH_SHORT).show() },
        modifier = Modifier.width(if (poster) 140.dp else 180.dp),
        scale = CardDefaults.scale(focusedScale = 1.10f),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = RoundedCornerShape(8.dp)))
    ) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AsyncImage(model = c.logo.ifBlank { null }, contentDescription = null, contentScale = if (poster) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier.height(if (poster) 190.dp else 80.dp).fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun Field(value: String, onChange: (String) -> Unit, label: String, onFocus: () -> Unit = {}, secret: Boolean = false) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { M3Text(label) }, singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = if (secret) KeyboardOptions(keyboardType = KeyboardType.NumberPassword) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() })
}

@Composable
fun SearchScreen(vm: MainViewModel, open: OpenFn) {
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ChannelEntity>>(emptyList()) }
    LaunchedEffect(Unit) { RemoteBus.text.collect { q += it } }
    LaunchedEffect(q) { if (q.length >= 2) { delay(300); results = vm.search(q) } else results = emptyList() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(520.dp)) { Field(q, { q = it }, "Search channels, movies, series (phone keyboard works too)") }
        LazyColumn(contentPadding = PaddingValues(bottom = 48.dp)) {
            itemsIndexed(results.chunked(6)) { r, chunk -> ItemRow(null, chunk, { i -> open(results, r * 6 + i, 0) }, vm) }
        }
    }
}

@Composable
fun SourcesScreen(vm: MainViewModel) {
    val status by vm.status.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    var mode by remember { mutableIntStateOf(0) } // 0 M3U, 1 Xtream, 2 Stalker
    var focused by remember { mutableIntStateOf(0) }
    val v = remember { mutableStateListOf("", "", "", "", "") } // 0 name, 1 url, 2 user/mac, 3 pass, 4 epg
    LaunchedEffect(Unit) { RemoteBus.text.collect { v[focused] = v[focused] + it } }
    val idxs = when (mode) { 0 -> listOf(0, 1, 4); 1 -> listOf(0, 1, 2, 3); else -> listOf(0, 1, 2) }
    fun label(i: Int) = when (i) {
        0 -> "Name"
        1 -> listOf("Playlist URL (.m3u / .m3u8)", "Server (http://host:port)", "Portal URL (http://host/c/)")[mode]
        2 -> if (mode == 1) "Username" else "MAC address (00:1A:79:xx:xx:xx)"
        3 -> "Password"
        else -> "EPG XMLTV URL (optional)"
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(560.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
        item { Text("Add a source", style = MaterialTheme.typography.headlineSmall) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("M3U", "Xtream Codes", "Stalker / MAG").forEachIndexed { i, n -> Button(onClick = { mode = i; focused = 0 }) { Text(if (mode == i) "● $n" else n) } }
            }
        }
        idxs.forEach { i -> item(key = "f$i-$mode") { Field(v[i], { v[i] = it }, label(i), { focused = i }, secret = false) } }
        item {
            Button(enabled = !busy, onClick = {
                when (mode) { 0 -> vm.addM3u(v[0], v[1], v[4]); 1 -> vm.addXtream(v[0], v[1], v[2], v[3]); else -> vm.addStalker(v[0], v[1], v[2]) }
            }) { Text(if (busy) "Importing..." else "Import") }
        }
        item { Text(status) }
        item { Text("Your sources", style = MaterialTheme.typography.titleMedium) }
        items(playlists) { p ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${p.name} (${p.type})", modifier = Modifier.width(300.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Button(onClick = { vm.removePlaylist(p) }) { Text("Delete") }
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val profile by vm.profile.collectAsStateWithLifecycle()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
        item {
            Text("Theme", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppTheme.values().forEach { t -> Button(onClick = { vm.setTheme(t) }) { Text(t.label) } }
            }
        }
        item {
            Text("Profile: ${profile?.name ?: ""}${if (profile?.isKids == true) " (Kids)" else ""}")
            Spacer(Modifier.height(8.dp))
            Button(onClick = { vm.select(null) }) { Text("Switch profile") }
        }
        item {
            Text("Phone remote", style = MaterialTheme.typography.headlineSmall)
            val url = RemoteInfo.url
            if (url.isBlank()) Text("Remote server unavailable (port in use or no network).")
            else {
                Text("Scan with a phone on the same Wi-Fi: D-pad, keyboard and stream URL cast.", color = Color.LightGray)
                Spacer(Modifier.height(8.dp))
                Image(bitmap = remember(url) { qr(url, 320) }, contentDescription = "QR", modifier = Modifier.size(220.dp).background(Color.White).padding(8.dp))
                Text(url, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun ProfileScreen(vm: MainViewModel) {
    val theme by vm.theme.collectAsStateWithLifecycle()
    val ps by vm.profiles.collectAsStateWithLifecycle()
    var pinFor by remember { mutableStateOf<ProfileEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(theme.bg), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Who's watching?", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            ps.forEach { p ->
                Card(onClick = { if (p.pin.isBlank()) vm.select(p) else pinFor = p }, onLongClick = { vm.deleteProfile(p) },
                    scale = CardDefaults.scale(focusedScale = 1.12f),
                    border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White)))) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(100.dp).background(theme.primary, RoundedCornerShape(12.dp)), Alignment.Center) { Text(p.name.take(1).uppercase(), fontSize = 40.sp, color = Color.White) }
                        Spacer(Modifier.height(8.dp))
                        Text(p.name + if (p.isKids) " (Kids)" else "")
                        if (p.pin.isNotBlank()) Text("🔒", fontSize = 12.sp)
                    }
                }
            }
            Card(onClick = { adding = true }, scale = CardDefaults.scale(focusedScale = 1.12f)) {
                Box(Modifier.size(132.dp, 160.dp), Alignment.Center) { Text("+  Add", fontSize = 22.sp) }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Long-press OK on a profile to delete it", color = Color.Gray, fontSize = 12.sp)
    }
    pinFor?.let { p ->
        var pin by remember { mutableStateOf("") }
        var wrong by remember { mutableStateOf(false) }
        Dialog(onDismissRequest = { pinFor = null }) {
            Column(Modifier.background(Color(0xEE111111)).padding(24.dp).width(320.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("PIN for ${p.name}")
                Field(pin, { pin = it.filter(Char::isDigit).take(8); wrong = false }, "PIN", secret = true)
                if (wrong) Text("Wrong PIN", color = Color(0xFFFF6666))
                Button(onClick = { if (pin == p.pin) { pinFor = null; vm.select(p) } else wrong = true }) { Text("OK") }
            }
        }
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        var pin by remember { mutableStateOf("") }
        var kids by remember { mutableStateOf(false) }
        Dialog(onDismissRequest = { adding = false }) {
            Column(Modifier.background(Color(0xEE111111)).padding(24.dp).width(360.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("New profile")
                Field(name, { name = it }, "Name")
                Field(pin, { pin = it.filter(Char::isDigit).take(8) }, "PIN (optional)", secret = true)
                Button(onClick = { kids = !kids }) { Text(if (kids) "Kids profile: ON (adult categories hidden)" else "Kids profile: OFF") }
                Button(onClick = { vm.addProfile(name, pin, kids); adding = false }) { Text("Create") }
            }
        }
    }
}
