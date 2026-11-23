package se.ulf.tv

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ObsidianApp()
            }
        }
    }
}

@Composable
fun ObsidianApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ulf_tv_v2", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val localIp = remember { getLocalIpAddress() }

    var mode by remember { mutableStateOf(prefs.getString("mode", "M3U") ?: "M3U") }
    var streamFormat by remember { mutableStateOf(prefs.getString("stream_format", "AUTO") ?: "AUTO") }
    var m3uUrl by remember { mutableStateOf(prefs.getString("m3u_url", "") ?: "") }
    var xtServer by remember { mutableStateOf(prefs.getString("xt_server", "") ?: "") }
    var xtUser by remember { mutableStateOf(prefs.getString("xt_user", "") ?: "") }
    var xtPass by remember { mutableStateOf(prefs.getString("xt_pass", "") ?: "") }

    var adultLockEnabled by remember { mutableStateOf(prefs.getBoolean("adult_lock_enabled", false)) }
    var adultPin by remember { mutableStateOf(prefs.getString("adult_pin", "") ?: "") }
    var adultUnlocked by remember { mutableStateOf(false) }
    var showUnlockDialog by remember { mutableStateOf(false) }

    var catalog by remember { mutableStateOf(demoCatalog()) }
    var catalogVersion by remember { mutableStateOf(1) }
    var currentSection by remember { mutableStateOf(NavSection.HOME) }
    var playingItem by remember { mutableStateOf<MediaEntry?>(null) }
    var statusText by remember { mutableStateOf("http://$localIp:8099/add") }
    var syncJob by remember { mutableStateOf<Job?>(null) }

    val isAdultRestricted = adultLockEnabled && adultPin.isNotBlank() && !adultUnlocked
    val visibleCatalog = remember(catalog, isAdultRestricted) {
        if (isAdultRestricted) catalog.filterNot { it.isAdult } else catalog
    }

    fun syncCatalog(useMode: String, useFormat: String, url: String, server: String, user: String, pass: String) {
        syncJob?.cancel()
        syncJob = scope.launch {
            statusText = "Synkar ($useFormat)..."
            var errorReason: String? = null
            val loaded = withContext(Dispatchers.IO) {
                try {
                    if (useMode == "XTREAM" && server.isNotBlank() && user.isNotBlank()) {
                        fetchXtreamCatalog(server, user, pass, useFormat)
                    } else if (url.isNotBlank()) {
                        parseM3uFromUrl(applyPlaylistFormat(url.trim(), useFormat), useFormat)
                    } else {
                        demoCatalog()
                    }
                } catch (t: Throwable) {
                    errorReason = "${t.javaClass.simpleName}: ${t.message ?: "Okänt fel"}"
                    emptyList()
                }
            }
            CoverImageCache.clearAndBump()
            catalogVersion += 1
            if (loaded.isNotEmpty()) {
                catalog = loaded
                statusText = "${loaded.count { it.type == MediaType.LIVE }} Live • ${loaded.count { it.type == MediaType.MOVIE }} Filmer • ${loaded.count { it.type == MediaType.SERIES }} Serier"
            } else {
                catalog = demoCatalog()
                statusText = if (errorReason != null) "Fel: $errorReason" else "Tom lista – visar demo"
            }
        }
    }

    fun saveAll(
        newMode: String, newFormat: String, newM3u: String,
        newServer: String, newUser: String, newPass: String,
        newAdultLock: Boolean, newAdultPin: String
    ) {
        val turningOnLock = newAdultLock && !adultLockEnabled
        mode = newMode
        streamFormat = newFormat
        m3uUrl = newM3u
        xtServer = newServer
        xtUser = newUser
        xtPass = newPass
        adultLockEnabled = newAdultLock
        adultPin = newAdultPin
        if (turningOnLock) adultUnlocked = false
        prefs.edit()
            .putString("mode", newMode)
            .putString("stream_format", newFormat)
            .putString("m3u_url", newM3u)
            .putString("xt_server", newServer)
            .putString("xt_user", newUser)
            .putString("xt_pass", newPass)
            .putBoolean("adult_lock_enabled", newAdultLock)
            .putString("adult_pin", newAdultPin)
            .apply()
        currentSection = NavSection.HOME
        syncCatalog(newMode, newFormat, newM3u, newServer, newUser, newPass)
    }

    DisposableEffect(Unit) {
        var serverSocket: ServerSocket? = null
        val job = scope.launch(Dispatchers.IO) {
            try {
                val socket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(8099))
                }
                serverSocket = socket
                while (true) {
                    val client = socket.accept()
                    launch(Dispatchers.IO) {
                        handleWebClient(client, mode, streamFormat, m3uUrl, xtServer, xtUser, xtPass, adultLockEnabled, adultPin) { nm, nf, nm3u, ns, nu, np, nal, nap ->
                            withContext(Dispatchers.Main) { saveAll(nm, nf, nm3u, ns, nu, np, nal, nap) }
                        }
                    }
                }
            } catch (_: Exception) { }
        }
        onDispose {
            try { serverSocket?.close() } catch (_: Exception) { }
            job.cancel()
        }
    }

    LaunchedEffect(Unit) {
        if ((mode == "M3U" && m3uUrl.isNotBlank()) || (mode == "XTREAM" && xtServer.isNotBlank())) {
            syncCatalog(mode, streamFormat, m3uUrl, xtServer, xtUser, xtPass)
        }
    }

    if (playingItem != null) {
        BackHandler { playingItem = null }
        PlayerScreen(item = playingItem!!, streamFormatMode = streamFormat, onBack = { playingItem = null })
        return
    }

    BackHandler(enabled = currentSection != NavSection.HOME && !showUnlockDialog) {
        currentSection = NavSection.HOME
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF05070C), Color(0xFF0B0F1A), Color(0xFF030407))))
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(Color(0xFF38BDF8)))
                    Text("OBSIDIAN", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
                }

                Surface(
                    color = Color(0xD9121622),
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, Color(0x2EFFFFFF))
                ) {
                    Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NavSection.entries.forEach { s -> NavPill(s.label, currentSection == s) { currentSection = s } }
                    }
                }

                Surface(color = Color(0x99121622), shape = RoundedCornerShape(50), border = BorderStroke(1.dp, Color(0x1FFFFFFF))) {
                    Text(
                        statusText,
                        color = Color(0xFF93C5FD),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            key(catalogVersion) {
                when (currentSection) {
                    NavSection.HOME -> AppleTvHomeView(visibleCatalog) { playingItem = it }
                    NavSection.LIVE -> LiveTvSplitView(visibleCatalog.filter { it.type == MediaType.LIVE }) { playingItem = it }
                    NavSection.MOVIES -> CuratedMediaSectionView(visibleCatalog.filter { it.type == MediaType.MOVIE }, "filmer", "Inga filmer hittades i spellistan.") { playingItem = it }
                    NavSection.SERIES -> CuratedMediaSectionView(visibleCatalog.filter { it.type == MediaType.SERIES }, "serier", "Inga serier hittades i spellistan.") { playingItem = it }
                    NavSection.SETTINGS -> SettingsView(
                        localIp, mode, streamFormat, m3uUrl, xtServer, xtUser, xtPass, adultLockEnabled, adultPin, adultUnlocked,
                        onRequestUnlockAdult = { showUnlockDialog = true },
                        onLockAdultNow = { adultUnlocked = false },
                        onBack = { currentSection = NavSection.HOME },
                        onSave = ::saveAll
                    )
                }
            }
        }

        if (showUnlockDialog) {
            BackHandler { showUnlockDialog = false }
            PinUnlockOverlay(adultPin, { adultUnlocked = true; showUnlockDialog = false }, { showUnlockDialog = false })
        }
    }
}

@Composable
fun AppleTvHomeView(catalog: List<MediaEntry>, onPlay: (MediaEntry) -> Unit) {
    val movies = remember(catalog) { catalog.filter { it.type == MediaType.MOVIE }.take(16) }
    val series = remember(catalog) { catalog.filter { it.type == MediaType.SERIES }.take(16) }
    val live = remember(catalog) { catalog.filter { it.type == MediaType.LIVE }.take(16) }
    val ultraHd = remember(catalog) { catalog.filter { it.is4K }.take(16) }
    val defaultHero = remember(catalog) { catalog.firstOrNull { it.imageUrl.isNotBlank() } ?: catalog.firstOrNull() }
    var hero by remember(defaultHero) { mutableStateOf(defaultHero) }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        if (hero != null) HeroShowcaseBanner(hero!!) { onPlay(hero!!) }
        if (ultraHd.isNotEmpty()) MediaShelfRow("4K Ultra HD", ultraHd, true, { hero = it }, onPlay)
        if (movies.isNotEmpty()) MediaShelfRow("Filmer", movies, true, { hero = it }, onPlay)
        if (series.isNotEmpty()) MediaShelfRow("Serier", series, true, { hero = it }, onPlay)
        if (live.isNotEmpty()) MediaShelfRow("Live TV", live, false, { hero = it }, onPlay)
        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
fun LiveTvSplitView(items: List<MediaEntry>, onPlay: (MediaEntry) -> Unit) {
    if (items.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Inga Live TV-kanaler hittades.", color = Color(0xFF9CA3AF), fontSize = 17.sp)
        }
        return
    }

    val defaultSubs = listOf("Sport", "Nyheter", "Film", "Musik", "Dokumentär", "Barn")
    val countries = remember(items) { listOf("Alla länder") + items.map { it.country }.distinct().sorted() }
    val subcategories = remember(items) { listOf("Alla underkategorier") + (defaultSubs + items.map { it.genre }.distinct().sorted()).distinct() }

    var searchQuery by remember { mutableStateOf("") }
    var selectedCountry by remember(items) { mutableStateOf("Alla länder") }
    var selectedSubcategory by remember(items) { mutableStateOf("Alla underkategorier") }
    var countryMenuOpen by remember { mutableStateOf(false) }
    var subMenuOpen by remember { mutableStateOf(false) }

    val filtered = remember(items, searchQuery, selectedCountry, selectedSubcategory) {
        val q = searchQuery.trim().lowercase()
        items.filter { entry ->
            val okCountry = selectedCountry == "Alla länder" || entry.country == selectedCountry
            val okSub = selectedSubcategory == "Alla underkategorier" ||
                entry.genre.equals(selectedSubcategory, ignoreCase = true) ||
                entry.title.contains(selectedSubcategory, ignoreCase = true)
            val okSearch = q.isEmpty() ||
                entry.title.lowercase().contains(q) ||
                entry.country.lowercase().contains(q) ||
                entry.genre.lowercase().contains(q)
            okCountry && okSub && okSearch
        }
    }

    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        // Vänsterpanel: Sök + Rullgardiner (Land & Underkategori)
        Surface(
            color = Color(0xCC111520),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Color(0x22FFFFFF)),
            modifier = Modifier.weight(0.35f).fillMaxHeight()
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Live TV – Filter & Sök", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Sök kanal...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Land", color = Color(0xFF9CA3AF), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Box(modifier = Modifier.fillMaxWidth()) {
                    DropdownTriggerButton(selectedCountry, countryMenuOpen, Modifier.fillMaxWidth()) {
                        countryMenuOpen = !countryMenuOpen
                        subMenuOpen = false
                    }
                    DropdownMenu(expanded = countryMenuOpen, onDismissRequest = { countryMenuOpen = false }, modifier = Modifier.background(Color(0xFF161922))) {
                        countries.forEach { country ->
                            DropdownMenuItem(
                                text = { Text(country, color = if (country == selectedCountry) Color(0xFF60A5FA) else Color.White, fontWeight = if (country == selectedCountry) FontWeight.Bold else FontWeight.Normal) },
                                onClick = { selectedCountry = country; countryMenuOpen = false }
                            )
                        }
                    }
                }

                Text("Underkategori", color = Color(0xFF9CA3AF), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Box(modifier = Modifier.fillMaxWidth()) {
                    DropdownTriggerButton(selectedSubcategory, subMenuOpen, Modifier.fillMaxWidth()) {
                        subMenuOpen = !subMenuOpen
                        countryMenuOpen = false
                    }
                    DropdownMenu(expanded = subMenuOpen, onDismissRequest = { subMenuOpen = false }, modifier = Modifier.background(Color(0xFF161922))) {
                        subcategories.forEach { sub ->
                            DropdownMenuItem(
                                text = { Text(sub, color = if (sub == selectedSubcategory) Color(0xFF60A5FA) else Color.White, fontWeight = if (sub == selectedSubcategory) FontWeight.Bold else FontWeight.Normal) },
                                onClick = { selectedSubcategory = sub; subMenuOpen = false }
                            )
                        }
                    }
                }

                if (searchQuery.isNotEmpty() || selectedCountry != "Alla länder" || selectedSubcategory != "Alla underkategorier") {
                    NavPill("Återställ filter", false) {
                        searchQuery = ""
                        selectedCountry = "Alla länder"
                        selectedSubcategory = "Alla underkategorier"
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text("${filtered.size} kanaler matchar", color = Color(0xFF93C5FD), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // Högerpanel: Kanallista
        Surface(
            color = Color(0xB30E121C),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Color(0x1EFFFFFF)),
            modifier = Modifier.weight(0.65f).fillMaxHeight()
        ) {
            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Inga kanaler matchar din sökning eller ditt filter.", color = Color(0xFF9CA3AF), fontSize = 15.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filtered.take(300), key = { it.id }) { channel ->
                        LiveChannelRowItem(channel) { onPlay(channel) }
                    }
                }
            }
        }
    }
}

@Composable
fun LiveChannelRowItem(item: MediaEntry, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.018f else 1f, label = "chRow")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) Color(0xFF1F273B) else Color(0xFF141926))
            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color(0x1AFFFFFF), RoundedCornerShape(14.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(modifier = Modifier.size(64.dp, 42.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF0A0D14))) {
            AsyncCoverImage(item.imageUrl, item.title, ContentScale.Fit, 160, Modifier.fillMaxSize(), item.imageUrl.isBlank())
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${item.country} •${item.genre}", color = if (focused) Color(0xFF93C5FD) else Color(0xFF9CA3AF), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Surface(color = if (item.is4K) Color(0xFFE50914) else Color(0xCCDC2626), shape = RoundedCornerShape(6.dp)) {
            Text(if (item.is4K) "4K LIVE" else "LIVE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
}

@Composable
fun CuratedMediaSectionView(items: List<MediaEntry>, sectionTypeLabel: String, emptyLabel: String, onPlay: (MediaEntry) -> Unit) {
    if (items.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(emptyLabel, color = Color(0xFF9CA3AF), fontSize = 17.sp)
        }
        return
    }

    var searchQuery by remember { mutableStateOf("") }
    val filtered = remember(items, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) items else items.filter {
            it.title.lowercase().contains(q) || it.genre.lowercase().contains(q) || it.country.lowercase().contains(q)
        }
    }

    var activeHero by remember(filtered) { mutableStateOf(filtered.firstOrNull { it.imageUrl.isNotBlank() } ?: filtered.firstOrNull()) }
    val latestItems = remember(filtered) { filtered.take(16) }
    val newestItems = remember(filtered) { filtered.takeLast(16).reversed() }
    val ultraHdItems = remember(filtered) { filtered.filter { it.is4K }.take(16) }
    val byGenre = remember(filtered) { filtered.groupBy { it.genre }.entries.sortedByDescending { it.value.size }.take(5) }
    val byCountry = remember(filtered) { filtered.groupBy { it.country }.entries.sortedByDescending { it.value.size }.take(5) }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Sök bland $sectionTypeLabel...") },
                singleLine = true,
                modifier = Modifier.width(360.dp)
            )
            if (searchQuery.isNotEmpty()) {
                NavPill("Rensa", false) { searchQuery = "" }
                Text("${filtered.size} träffar", color = Color(0xFF93C5FD), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        if (filtered.isEmpty()) {
            Text("Inga $sectionTypeLabel matchar \"$searchQuery\".", color = Color(0xFF9CA3AF), fontSize = 16.sp)
        } else {
            if (activeHero != null) HeroShowcaseBanner(activeHero!!) { onPlay(activeHero!!) }
            MediaShelfRow("Senaste inlagt", latestItems, true, { activeHero = it }, onPlay)
            MediaShelfRow("Nya $sectionTypeLabel", newestItems, true, { activeHero = it }, onPlay)
            if (ultraHdItems.isNotEmpty()) MediaShelfRow("4K Ultra HD", ultraHdItems, true, { activeHero = it }, onPlay)
            byGenre.forEach { (gName, list) -> MediaShelfRow("Genre • $gName", list.take(16), true, { activeHero = it }, onPlay) }
            byCountry.forEach { (cName, list) -> MediaShelfRow("Land • $cName", list.take(16), true, { activeHero = it }, onPlay) }
        }
        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
fun DropdownTriggerButton(label: String, expanded: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.03f else 1f, label = "dd")
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) Color.White else Color(0xFF171B26))
            .border(if (focused) 2.5.dp else 1.dp, if (focused) Color.White else Color(0x33FFFFFF), RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 11.dp)
    ) {
        Text("$label${if (expanded) "▲" else "▼"}", color = if (focused) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun HeroShowcaseBanner(item: MediaEntry, onPlay: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.012f else 1f, label = "heroScale")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(270.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(if (focused) 24.dp else 12.dp, RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp))
            .border(if (focused) 2.5.dp else 1.dp, if (focused) Color.White else Color(0x22FFFFFF), RoundedCornerShape(22.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable { onPlay() }
    ) {
        AsyncCoverImage(item.imageUrl, item.title, ContentScale.Crop, 640, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xF006070B), Color(0xA606070B), Color(0x2606070B)))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x4006070B), Color(0xEB06070B)))))

        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 32.dp, vertical = 22.dp).fillMaxWidth(0.75f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Color(0x33FFFFFF), shape = RoundedCornerShape(6.dp)) {
                    Text(item.type.label.uppercase(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp))
                }
                if (item.is4K) {
                    Surface(color = Color(0xFFE50914), shape = RoundedCornerShape(6.dp)) {
                        Text("4K ULTRA HD", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp))
                    }
                }
                Text("${item.country}  •${item.genre}", color = Color(0xFFD1D5DB), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(item.title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Surface(color = Color.White, shape = RoundedCornerShape(8.dp)) {
                Text("▶  Spela nu", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 9.dp))
            }
        }
    }
}

@Composable
fun MediaShelfRow(title: String, items: List<MediaEntry>, isPoster: Boolean, onSelectFocus: (MediaEntry) -> Unit, onPlay: (MediaEntry) -> Unit) {
    Column {
        Text(title, color = Color(0xFFF3F4F6), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(items, key = { it.id }) { item ->
                if (isPoster) {
                    NetflixPosterCard(item, Modifier.width(156.dp).height(234.dp), { onSelectFocus(item) }) { onPlay(item) }
                } else {
                    PlexLiveCard(item, Modifier.width(250.dp).height(142.dp), { onSelectFocus(item) }) { onPlay(item) }
                }
            }
        }
    }
}

@Composable
fun NetflixPosterCard(item: MediaEntry, modifier: Modifier, onFocused: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.07f else 1f, label = "posterCard")

    Box(
        modifier = modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(if (focused) 20.dp else 6.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF10131C))
            .border(if (focused) 2.5.dp else 1.dp, if (focused) Color.White else Color(0x1EFFFFFF), RoundedCornerShape(14.dp))
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .focusable()
            .clickable { onClick() }
    ) {
        AsyncCoverImage(item.imageUrl, item.title, ContentScale.Crop, 320, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x18000000), Color(0xE806070B)))))
        if (item.is4K) {
            Surface(color = Color(0xE6E50914), shape = RoundedCornerShape(5.dp), modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Text("4K", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
        Column(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.genre, color = if (focused) Color(0xFF93C5FD) else Color(0xFFB0B7C6), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun PlexLiveCard(item: MediaEntry, modifier: Modifier, onFocused: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "liveCard")

    Box(
        modifier = modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(if (focused) 20.dp else 6.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(if (focused) listOf(Color(0xFF1F2433), Color(0xFF10131C)) else listOf(Color(0xFF151822), Color(0xFF0B0D14))))
            .border(if (focused) 2.5.dp else 1.dp, if (focused) Color.White else Color(0x1EFFFFFF), RoundedCornerShape(14.dp))
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .focusable()
            .clickable { onClick() }
    ) {
        if (item.imageUrl.isNotBlank()) {
            AsyncCoverImage(item.imageUrl, item.title, ContentScale.Fit, 300, Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 46.dp), false)
        } else {
            AsyncCoverImage("", item.title, ContentScale.Crop, 300, Modifier.fillMaxSize(), true)
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x2A06070B), Color(0xEB06070B)))))
        Surface(color = if (item.is4K) Color(0xE6E50914) else Color(0xCCDC2626), shape = RoundedCornerShape(6.dp), modifier = Modifier.align(Alignment.TopEnd).padding(9.dp)) {
            Text(if (item.is4K) "4K LIVE" else "LIVE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
        }
        Column(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 13.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${item.country} •${item.genre}", color = if (focused) Color(0xFF93C5FD) else Color(0xFF9CA3AF), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun AsyncCoverImage(url: String, title: String, contentScale: ContentScale, maxDimension: Int, modifier: Modifier = Modifier, fallbackBackdrop: Boolean = true) {
    val epoch = CoverImageCache.epoch
    val cacheKey = "${epoch}_${url}_$maxDimension"
    var bitmap by remember(cacheKey) { mutableStateOf(CoverImageCache.get("${url}_$maxDimension")) }

    LaunchedEffect(cacheKey) {
        if (bitmap == null && url.isNotBlank()) {
            val loaded = withContext(Dispatchers.IO) { runCatching { loadScaledBitmap(url, maxDimension) }.getOrNull() }
            if (loaded != null) {
                CoverImageCache.put("${url}_$maxDimension", loaded)
                bitmap = loaded
            }
        }
    }

    val fallbackBrush = remember(title) {
        val palettes = listOf(
            listOf(Color(0xFF1B2234), Color(0xFF0C0F17)),
            listOf(Color(0xFF281A2E), Color(0xFF100B14)),
            listOf(Color(0xFF13292B), Color(0xFF091315)),
            listOf(Color(0xFF2E1D1B), Color(0xFF140C0B))
        )
        Brush.linearGradient(palettes[abs(title.hashCode()) % palettes.size])
    }

    Box(modifier = modifier.then(if (fallbackBackdrop) Modifier.background(fallbackBrush) else Modifier), contentAlignment = Alignment.Center) {
        val bmp = bitmap
        if (bmp != null) {
            Image(bitmap = bmp, contentDescription = title, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        } else if (fallbackBackdrop) {
            val initials = title.split(" ", "-", "|").filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }.ifBlank { "TV" }
            Text(initials, color = Color(0x24FFFFFF), fontSize = 32.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
fun NavPill(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, label = "pill")
    Box(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(50))
            .background(if (focused) Color.White else if (selected) Color(0x36FFFFFF) else Color.Transparent)
            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else if (selected) Color(0x50FFFFFF) else Color.Transparent, RoundedCornerShape(50))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .padding(horizontal = 15.dp, vertical = 8.dp)
    ) {
        Text(label, color = if (focused) Color.Black else Color.White, fontSize = 13.sp, fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
fun PinUnlockOverlay(expectedPin: String, onUnlocked: () -> Unit, onDismiss: () -> Unit) {
    var enteredPin by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    Box(modifier = Modifier.fillMaxSize().background(Color(0xD9000000)).padding(24.dp), contentAlignment = Alignment.Center) {
        Surface(color = Color(0xFF141822), shape = RoundedCornerShape(22.dp), border = BorderStroke(1.5.dp, Color(0x44FFFFFF)), modifier = Modifier.width(420.dp)) {
            Column(modifier = Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Lås upp 18+ / Vuxenkanaler", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Ange din PIN-kod för att visa vuxeninnehåll.", color = Color(0xFF9CA3AF), fontSize = 14.sp)
                OutlinedTextField(value = enteredPin, onValueChange = { enteredPin = it; errorMsg = null }, label = { Text("Lösenord / PIN") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                if (errorMsg != null) Text(errorMsg!!, color = Color(0xFFF87171), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { if (enteredPin == expectedPin) onUnlocked() else errorMsg = "Fel lösenord, försök igen." }, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black), shape = RoundedCornerShape(50), modifier = Modifier.weight(1f)) { Text("Lås upp", fontWeight = FontWeight.Bold) }
                    Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF252A38), contentColor = Color.White), shape = RoundedCornerShape(50), modifier = Modifier.weight(1f)) { Text("Avbryt") }
                }
            }
        }
    }
}

@Composable
fun SettingsView(
    localIp: String, initialMode: String, initialFormat: String,
    initialM3u: String, initialServer: String, initialUser: String, initialPass: String,
    initialAdultLock: Boolean, initialAdultPin: String, adultUnlocked: Boolean,
    onRequestUnlockAdult: () -> Unit, onLockAdultNow: () -> Unit, onBack: () -> Unit,
    onSave: (String, String, String, String, String, String, Boolean, String) -> Unit
) {
    var selectedMode by remember { mutableStateOf(initialMode) }
    var selectedFormat by remember { mutableStateOf(initialFormat) }
    var m3u by remember { mutableStateOf(initialM3u) }
    var server by remember { mutableStateOf(initialServer) }
    var user by remember { mutableStateOf(initialUser) }
    var pass by remember { mutableStateOf(initialPass) }
    var adultLock by remember { mutableStateOf(initialAdultLock) }
    var adultPin by remember { mutableStateOf(initialAdultPin) }
    var pinWarning by remember { mutableStateOf<String?>(null) }
    val isCurrentlyLocked = initialAdultLock && initialAdultPin.isNotBlank() && !adultUnlocked

    Surface(color = Color(0xFF11141D), shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, Color(0x24FFFFFF)), modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Column(modifier = Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                NavPill("← Tillbaka", false, onBack)
                Text("Inställningar", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Surface(color = Color(0xFF181D2B), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Color(0xFF38BDF8)), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Snabbinmatning från dator eller mobil (samma Wi-Fi):", color = Color(0xFF93C5FD), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("http
