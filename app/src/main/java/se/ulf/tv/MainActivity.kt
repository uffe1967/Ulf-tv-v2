package se.ulf.tv

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import kotlinx.coroutines.delay
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
    var selectedSeries by remember { mutableStateOf<MediaEntry?>(null) }
    var statusText by remember { mutableStateOf("http://$localIp:8099/add") }
    var syncJob by remember { mutableStateOf<Job?>(null) }

    val isAdultRestricted = adultLockEnabled && adultPin.isNotBlank() && !adultUnlocked
    val visibleCatalog = remember(catalog, isAdultRestricted) {
        if (isAdultRestricted) catalog.filterNot { it.isAdult } else catalog
    }
    val liveItems = remember(visibleCatalog) { visibleCatalog.filter { it.type == MediaType.LIVE } }
    val movieItems = remember(visibleCatalog) { visibleCatalog.filter { it.type == MediaType.MOVIE } }
    val seriesItems = remember(visibleCatalog) { visibleCatalog.filter { it.type == MediaType.SERIES } }

    fun handleMediaClick(entry: MediaEntry) {
        if (entry.type == MediaType.SERIES) selectedSeries = entry else playingItem = entry
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
                        parseM3uFromUrl(applyPlaylistFormat(url.trim(), useFormat), useFormat) { count ->
                            scope.launch(Dispatchers.Main) { statusText = "Synkar... $count poster" }
                        }
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
                withContext(Dispatchers.IO) { saveCatalogCache(context, loaded) }
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
        mode = newMode; streamFormat = newFormat; m3uUrl = newM3u
        xtServer = newServer; xtUser = newUser; xtPass = newPass
        adultLockEnabled = newAdultLock; adultPin = newAdultPin
        if (turningOnLock) adultUnlocked = false
        prefs.edit()
            .putString("mode", newMode).putString("stream_format", newFormat)
            .putString("m3u_url", newM3u).putString("xt_server", newServer)
            .putString("xt_user", newUser).putString("xt_pass", newPass)
            .putBoolean("adult_lock_enabled", newAdultLock).putString("adult_pin", newAdultPin)
            .apply()
        currentSection = NavSection.HOME
        syncCatalog(newMode, newFormat, newM3u, newServer, newUser, newPass)
    }

    DisposableEffect(Unit) {
        var serverSocket: ServerSocket? = null
        val job = scope.launch(Dispatchers.IO) {
            try {
                val socket = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(8099)) }
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
        onDispose { try { serverSocket?.close() } catch (_: Exception) { }; job.cancel() }
    }

    LaunchedEffect(Unit) {
        statusText = "Läser sparat bibliotek..."
        val cached = withContext(Dispatchers.IO) { loadCatalogCache(context) }
        if (cached.isNotEmpty()) {
            catalog = cached
            catalogVersion += 1
            statusText = "${cached.count { it.type == MediaType.LIVE }} Live • ${cached.count { it.type == MediaType.MOVIE }} Filmer • ${cached.count { it.type == MediaType.SERIES }} Serier"
        } else if ((mode == "M3U" && m3uUrl.isNotBlank()) || (mode == "XTREAM" && xtServer.isNotBlank())) {
            syncCatalog(mode, streamFormat, m3uUrl, xtServer, xtUser, xtPass)
        } else {
            statusText = "http://$localIp:8099/add"
        }
    }

    if (playingItem != null) {
        BackHandler { playingItem = null }
        PlayerScreen(item = playingItem!!, streamFormatMode = streamFormat, onBack = { playingItem = null })
        return
    }

    if (selectedSeries != null) {
        BackHandler { selectedSeries = null }
        SeriesDetailScreen(
            series = selectedSeries!!,
            mode = mode,
            xtServer = xtServer,
            xtUser = xtUser,
            xtPass = xtPass,
            allSeriesItems = seriesItems,
            onBack = { selectedSeries = null },
            onPlayEpisode = { ep ->
                playingItem = selectedSeries!!.copy(
                    id = ep.id,
                    title = "${extractSeriesBaseTitle(selectedSeries!!.title)} • S${ep.season}E${ep.episodeNum} ${ep.title}",
                    url = ep.url
                )
            }
        )
        return
    }

    BackHandler(enabled = currentSection != NavSection.HOME && !showUnlockDialog) { currentSection = NavSection.HOME }

    Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF05070C), Color(0xFF0B0F1A), Color(0xFF030407))))) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 14.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(Color(0xFF38BDF8)))
                    Text("OBSIDIAN", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                }
                Surface(color = Color(0xD9121622), shape = RoundedCornerShape(50), border = BorderStroke(1.dp, Color(0x2EFFFFFF))) {
                    Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        NavSection.entries.forEach { s -> NavPill(s.label, currentSection == s) { currentSection = s } }
                    }
                }
                Surface(color = Color(0x99121622), shape = RoundedCornerShape(50), border = BorderStroke(1.dp, Color(0x1FFFFFFF))) {
                    Text(statusText, color = Color(0xFF93C5FD), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            key(catalogVersion) {
                when (currentSection) {
                    NavSection.HOME -> AppleTvHomeView(visibleCatalog, liveItems, movieItems, seriesItems, ::handleMediaClick)
                    NavSection.LIVE -> LiveTvSplitView(liveItems) { playingItem = it }
                    NavSection.MOVIES -> CuratedMediaSectionView(movieItems, "filmer", "Inga filmer hittades i spellistan.", ::handleMediaClick)
                    NavSection.SERIES -> CuratedMediaSectionView(seriesItems, "serier", "Inga serier hittades i spellistan.", ::handleMediaClick)
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
fun SeriesDetailScreen(
    series: MediaEntry,
    mode: String,
    xtServer: String,
    xtUser: String,
    xtPass: String,
    allSeriesItems: List<MediaEntry>,
    onBack: () -> Unit,
    onPlayEpisode: (SeriesEpisode) -> Unit
) {
    var seasonsMap by remember(series.id) { mutableStateOf(emptyMap<Int, List<SeriesEpisode>>()) }
    var loading by remember(series.id) { mutableStateOf(true) }
    var errorText by remember(series.id) { mutableStateOf<String?>(null) }
    var selectedSeason by remember(series.id) { mutableStateOf(1) }

    LaunchedEffect(series.id) {
        loading = true
        errorText = null
        val loaded = withContext(Dispatchers.IO) {
            try {
                if (mode == "XTREAM" && xtServer.isNotBlank() && series.id.startsWith("SERIES_")) {
                    val sid = series.id.substringAfterLast('_')
                    fetchXtreamSeriesEpisodes(xtServer, xtUser, xtPass, sid)
                } else {
                    buildM3uSeriesEpisodes(series.title, allSeriesItems)
                }
            } catch (t: Throwable) {
                errorText = "Kunde inte hämta avsnitt: ${t.message ?: t.javaClass.simpleName}"
                emptyMap()
            }
        }
        seasonsMap = loaded
        if (loaded.isNotEmpty()) selectedSeason = loaded.keys.first()
        loading = false
    }

    val seasonNumbers = remember(seasonsMap) { seasonsMap.keys.toList() }
    val episodes = seasonsMap[selectedSeason].orEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF05070C), Color(0xFF0B0F1A), Color(0xFF030407))))
            .padding(horizontal = 32.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            NavPill("← Tillbaka", false, onBack)
            Column(modifier = Modifier.weight(1f)) {
                Text(extractSeriesBaseTitle(series.title), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${series.country} • ${series.genre}", color = Color(0xFF93C5FD), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        if (loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Hämtar säsonger och avsnitt...", color = Color(0xFF93C5FD), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        } else if (seasonsMap.isEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(errorText ?: "Hittade inga separata säsonger/avsnitt för denna serie.", color = Color(0xFFF87171), fontSize = 14.sp)
                NavPill("▶ Försök spela direkt", true) {
                    onPlayEpisode(SeriesEpisode(series.id, 1, 1, series.title, series.url))
                }
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(seasonNumbers) { sNum ->
                    NavPill("Säsong $sNum", selectedSeason == sNum) { selectedSeason = sNum }
                }
            }
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                items(episodes, key = { "${it.season}_${it.id}" }) { ep -> EpisodeRowItem(ep) { onPlayEpisode(ep) } }
            }
        }
    }
}

@Composable
fun EpisodeRowItem(ep: SeriesEpisode, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.012f else 1f, label = "epRow")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) Color(0xFF1F273B) else Color(0xFF141926))
            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color(0x1AFFFFFF), RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(color = Color(0xFF1E3A8A), shape = RoundedCornerShape(6.dp)) {
            Text("S${ep.season} E${ep.episodeNum}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        }
        Text(ep.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("▶ Spela", color = if (focused) Color(0xFF38BDF8) else Color(0xFF9CA3AF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun AppleTvHomeView(
    catalog: List<MediaEntry>,
    liveAll: List<MediaEntry>,
    moviesAll: List<MediaEntry>,
    seriesAll: List<MediaEntry>,
    onPlay: (MediaEntry) -> Unit
) {
    val movies = remember(moviesAll) { moviesAll.take(14) }
    val series = remember(seriesAll) { seriesAll.take(14) }
    val live = remember(liveAll) { liveAll.take(14) }
    val ultraHd = remember(catalog) { catalog.filter { it.is4K }.take(14) }
    val defaultHero = remember(catalog) { catalog.firstOrNull { it.imageUrl.isNotBlank() } ?: catalog.firstOrNull() }
    var hero by remember(defaultHero) { mutableStateOf(defaultHero) }

    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        if (hero != null) item(key = "hero") { HeroShowcaseBanner(hero!!) { onPlay(hero!!) } }
        if (ultraHd.isNotEmpty()) item(key = "4k") { MediaShelfRow("4K Ultra HD", ultraHd, true, { hero = it }, onPlay) }
        if (movies.isNotEmpty()) item(key = "movies") { MediaShelfRow("Filmer", movies, true, { hero = it }, onPlay) }
        if (series.isNotEmpty()) item(key = "series") { MediaShelfRow("Serier", series, true, { hero = it }, onPlay) }
        if (live.isNotEmpty()) item(key = "live") { MediaShelfRow("Live TV", live, false, { hero = it }, onPlay) }
    }
}

@Composable
fun LiveTvSplitView(items: List<MediaEntry>, onPlay: (MediaEntry) -> Unit) {
    if (items.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Inga Live TV-kanaler hittades.", color = Color(0xFF9CA3AF), fontSize = 16.sp)
        }
        return
    }

    val defaultSubs = listOf("Alla", "Sport", "Nyheter", "Film", "Musik", "Dokumentär", "Barn")
    val countries = remember(items) { listOf("Alla länder") + items.map { it.country }.distinct().sorted() }
    val subcategories = remember(items) { (defaultSubs + items.map { it.genre }.distinct().sorted()).distinct().take(18) }

    var searchQuery by remember { mutableStateOf("") }
    var selectedCountry by remember(items) { mutableStateOf("Alla länder") }
    var selectedSubcategory by remember(items) { mutableStateOf("Alla") }
    var filtered by remember(items) { mutableStateOf(items.take(250)) }
    var totalMatches by remember(items) { mutableStateOf(items.size) }

    LaunchedEffect(items, searchQuery, selectedCountry, selectedSubcategory) {
        if (searchQuery.isNotEmpty()) delay(250)
        val q = searchQuery.trim().lowercase()
        val result = withContext(Dispatchers.Default) {
            items.filter { entry ->
                val okCountry = selectedCountry == "Alla länder" || entry.country == selectedCountry
                val okSub = selectedSubcategory == "Alla" ||
                    entry.genre.equals(selectedSubcategory, ignoreCase = true) ||
                    entry.title.contains(selectedSubcategory, ignoreCase = true)
                val okSearch = q.isEmpty() ||
                    entry.title.lowercase().contains(q) ||
                    entry.country.lowercase().contains(q) ||
                    entry.genre.lowercase().contains(q)
                okCountry && okSub && okSearch
            }
        }
        totalMatches = result.size
        filtered = result.take(250)
    }

    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        // Vänsterpanel: Bläddringsbar landlista (D-pad-vänlig)
        Surface(color = Color(0xCC111520), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Color(0x22FFFFFF)), modifier = Modifier.weight(0.26f).fillMaxHeight()) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                Text("Länder (${countries.size - 1})", color = Color(0xFF93C5FD), fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp))
                LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(countries, key = { it }) { country ->
                        SideListItem(country, country == selectedCountry) { selectedCountry = country }
                    }
                }
            }
        }

        // Högerpanel: Sök + Underkategorier + Kanallista
        Column(modifier = Modifier.weight(0.74f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Sök kanal...", fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                if (searchQuery.isNotEmpty() || selectedCountry != "Alla länder" || selectedSubcategory != "Alla") {
                    NavPill("Rensa", false) { searchQuery = ""; selectedCountry = "Alla länder"; selectedSubcategory = "Alla" }
                }
                Text("$totalMatches kanaler", color = Color(0xFF93C5FD), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(subcategories, key = { it }) { sub ->
                    NavPill(sub, selectedSubcategory == sub) { selectedSubcategory = sub }
                }
            }

            Surface(color = Color(0xB30E121C), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Color(0x1EFFFFFF)), modifier = Modifier.fillMaxSize()) {
                if (filtered.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Inga kanaler matchar.", color = Color(0xFF9CA3AF), fontSize = 15.sp)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(filtered, key = { it.id }) { channel -> LiveChannelRowItem(channel) { onPlay(channel) } }
                    }
                }
            }
        }
    }
}

@Composable
fun SideListItem(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Color.White else if (selected) Color(0x3838BDF8) else Color.Transparent)
            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else if (selected) Color(0x8838BDF8) else Color.Transparent, RoundedCornerShape(10.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Text(label, color = if (focused) Color.Black else Color.White, fontSize = 13.sp, fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun LiveChannelRowItem(item: MediaEntry, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.015f else 1f, label = "chRow")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) Color(0xFF1F273B) else Color(0xFF141926))
            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color(0x1AFFFFFF), RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(modifier = Modifier.size(56.dp, 36.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF0A0D14))) {
            AsyncCoverImage(item.imageUrl, item.title, ContentScale.Fit, 200, Modifier.fillMaxSize(), item.imageUrl.isBlank())
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${item.country} • ${item.genre}", color = if (focused) Color(0xFF93C5FD) else Color(0xFF9CA3AF), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Surface(color = if (item.is4K) Color(0xFFE50914) else Color(0xCCDC2626), shape = RoundedCornerShape(6.dp)) {
            Text(if (item.is4K) "4K" else "LIVE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
        }
    }
}

@Composable
fun CuratedMediaSectionView(items: List<MediaEntry>, sectionTypeLabel: String, emptyLabel: String, onPlay: (MediaEntry) -> Unit) {
    if (items.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(emptyLabel, color = Color(0xFF9CA3AF), fontSize = 16.sp)
        }
        return
    }

    var searchQuery by remember { mutableStateOf("") }
    var filtered by remember(items) { mutableStateOf(items) }

    LaunchedEffect(items, searchQuery) {
        if (searchQuery.isNotEmpty()) delay(300)
        val q = searchQuery.trim().lowercase()
        filtered = if (q.isEmpty()) items else withContext(Dispatchers.Default) {
            items.filter { it.title.lowercase().contains(q) || it.genre.lowercase().contains(q) || it.country.lowercase().contains(q) }
        }
    }

    var activeHero by remember(filtered) { mutableStateOf(filtered.firstOrNull { it.imageUrl.isNotBlank() } ?: filtered.firstOrNull()) }
    val latestItems = remember(filtered) { filtered.take(14) }
    val newestItems = remember(filtered) { filtered.takeLast(14).reversed() }
    val ultraHdItems = remember(filtered) { filtered.filter { it.is4K }.take(14) }
    val byGenre = remember(filtered) { filtered.groupBy { it.genre }.entries.sortedByDescending { it.value.size }.take(4) }
    val byCountry = remember(filtered) { filtered.groupBy { it.country }.entries.sortedByDescending { it.value.size }.take(4) }

    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item(key = "search") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it }, placeholder = { Text("Sök bland $sectionTypeLabel...", fontSize = 13.sp) }, singleLine = true, modifier = Modifier.width(340.dp))
                if (searchQuery.isNotEmpty()) {
                    NavPill("Rensa", false) { searchQuery = "" }
                    Text("${filtered.size} träffar", color = Color(0xFF93C5FD), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (filtered.isEmpty()) {
            item(key = "empty") { Text("Inga $sectionTypeLabel matchar \"$searchQuery\".", color = Color(0xFF9CA3AF), fontSize = 15.sp) }
        } else {
            if (activeHero != null) item(key = "hero") { HeroShowcaseBanner(activeHero!!) { onPlay(activeHero!!) } }
            item(key = "latest") { MediaShelfRow("Senaste inlagt", latestItems, true, { activeHero = it }, onPlay) }
            item(key = "newest") { MediaShelfRow("Nya $sectionTypeLabel", newestItems, true, { activeHero = it }, onPlay) }
            if (ultraHdItems.isNotEmpty()) item(key = "4k") { MediaShelfRow("4K Ultra HD", ultraHdItems, true, { activeHero = it }, onPlay) }
            byGenre.forEach { (gName, list) -> item(key = "g_$gName") { MediaShelfRow("Genre • $gName", list.take(14), true, { activeHero = it }, onPlay) } }
            byCountry.forEach { (cName, list) -> item(key = "c_$cName") { MediaShelfRow("Land • $cName", list.take(14), true, { activeHero = it }, onPlay) } }
        }
    }
}

@Composable
fun HeroShowcaseBanner(item: MediaEntry, onPlay: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.012f else 1f, label = "heroScale")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(210.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(if (focused) 18.dp else 8.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .border(if (focused) 2.5.dp else 1.dp, if (focused) Color.White else Color(0x22FFFFFF), RoundedCornerShape(18.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable { onPlay() }
    ) {
        AsyncCoverImage(item.imageUrl, item.title, ContentScale.Crop, 900, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xF006070B), Color(0xA606070B), Color(0x2606070B)))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x4006070B), Color(0xEB06070B)))))

        Column(modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 26.dp, vertical = 16.dp).fillMaxWidth(0.75f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Color(0x33FFFFFF), shape = RoundedCornerShape(5.dp)) {
                    Text(item.type.label.uppercase(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                }
                if (item.is4K) {
                    Surface(color = Color(0xFFE50914), shape = RoundedCornerShape(5.dp)) {
                        Text("4K", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                }
                Text("${item.country}  •  ${item.genre}", color = Color(0xFFD1D5DB), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(item.title, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Surface(color = Color.White, shape = RoundedCornerShape(8.dp)) {
                Text(if (item.type == MediaType.SERIES) "▶  Välj säsong & avsnitt" else "▶  Spela nu", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp))
            }
        }
    }
}

@Composable
fun MediaShelfRow(title: String, items: List<MediaEntry>, isPoster: Boolean, onSelectFocus: (MediaEntry) -> Unit, onPlay: (MediaEntry) -> Unit) {
    Column {
        Text(title, color = Color(0xFFF3F4F6), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.id }) { item ->
                if (isPoster) {
                    NetflixPosterCard(item, Modifier.width(192.dp).height(108.dp), { onSelectFocus(item) }) { onPlay(item) }
                } else {
                    PlexLiveCard(item, Modifier.width(192.dp).height(108.dp), { onSelectFocus(item) }) { onPlay(item) }
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
            .shadow(if (focused) 14.dp else 4.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF10131C))
            .border(if (focused) 2.5.dp else 1.dp, if (focused) Color.White else Color(0x1EFFFFFF), RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .focusable()
            .clickable { onClick() }
    ) {
        AsyncCoverImage(item.imageUrl, item.title, ContentScale.Crop, 480, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x18000000), Color(0xE806070B)))))
        if (item.is4K) {
            Surface(color = Color(0xE6E50914), shape = RoundedCornerShape(4.dp), modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                Text("4K", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
            }
        }
        Column(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(9.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(item.genre, color = if (focused) Color(0xFF93C5FD) else Color(0xFFB0B7C6), fontSize = 9.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun PlexLiveCard(item: MediaEntry, modifier: Modifier, onFocused: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.07f else 1f, label = "liveCard")

    Box(
        modifier = modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(if (focused) 14.dp else 4.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.verticalGradient(if (focused) listOf(Color(0xFF1F2433), Color(0xFF10131C)) else listOf(Color(0xFF151822), Color(0xFF0B0D14))))
            .border(if (focused) 2.5.dp else 1.dp, if (focused) Color.White else Color(0x1EFFFFFF), RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .focusable()
            .clickable { onClick() }
    ) {
        if (item.imageUrl.isNotBlank()) {
            AsyncCoverImage(item.imageUrl, item.title, ContentScale.Fit, 480, Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 36.dp), false)
        } else {
            AsyncCoverImage("", item.title, ContentScale.Crop, 480, Modifier.fillMaxSize(), true)
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x2A06070B), Color(0xEB06070B)))))
        Surface(color = if (item.is4K) Color(0xE6E50914) else Color(0xCCDC2626), shape = RoundedCornerShape(5.dp), modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)) {
            Text(if (item.is4K) "4K" else "LIVE", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
        }
        Column(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 9.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text("${item.country} • ${item.genre}", color = if (focused) Color(0xFF93C5FD) else Color(0xFF9CA3AF), fontSize = 9.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            Text(initials, color = Color(0x24FFFFFF), fontSize = 26.sp, fontWeight = FontWeight.Black)
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
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(label, color = if (focused) Color.Black else Color.White, fontSize = 12.sp, fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium)
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
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                NavPill("← Tillbaka", false, onBack)
                Text("Inställningar", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Surface(color = Color(0xFF181D2B), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Color(0xFF38BDF8)), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Snabbinmatning från dator eller mobil (samma Wi-Fi):", color = Color(0xFF93C5FD), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("http://$localIp:8099/add", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
            Text("IPTV-källa", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NavPill("M3U-länk", selectedMode == "M3U") { selectedMode = "M3U" }
                NavPill("Xtream Codes", selectedMode == "XTREAM") { selectedMode = "XTREAM" }
            }
            if (selectedMode == "M3U") {
                OutlinedTextField(value = m3u, onValueChange = { m3u = it }, label = { Text("M3U-spellista URL (http://...)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            } else {
                OutlinedTextField(value = server, onValueChange = { server = it }, label = { Text("Server-URL (t.ex. http://server.com:8080)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("Användarnamn") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = pass, onValueChange = { pass = it }, label = { Text("Lösenord") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f))
                }
            }
            Text("Strömformat för Live TV", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NavPill("Auto (Smart växling)", selectedFormat == "AUTO") { selectedFormat = "AUTO" }
                NavPill("Alltid HLS (.m3u8)", selectedFormat == "HLS") { selectedFormat = "HLS" }
                NavPill("Alltid MPEG-TS (.ts)", selectedFormat == "MPEG-TS") { selectedFormat = "MPEG-TS" }
            }
            Surface(color = Color(0xFF161A26), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Color(0x22FFFFFF)), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Föräldrakontroll (18+ / Vuxenkanaler)", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    if (isCurrentlyLocked) {
                        Text("18+-innehåll är dolt och skyddat med PIN-kod.", color = Color(0xFF9CA3AF), fontSize = 13.sp)
                        NavPill("Lås upp 18+ med PIN", true, onRequestUnlockAdult)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            NavPill("Av (Olåst)", !adultLock) { adultLock = false; pinWarning = null }
                            NavPill("På (Kräv lösenord)", adultLock) { adultLock = true }
                            if (initialAdultLock && initialAdultPin.isNotBlank() && adultUnlocked) NavPill("Lås 18+ nu", false, onLockAdultNow)
                        }
                        if (adultLock) {
                            OutlinedTextField(value = adultPin, onValueChange = { adultPin = it; pinWarning = null }, label = { Text("Lösenord / PIN-kod för 18+") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                            if (pinWarning != null) Text(pinWarning!!, color = Color(0xFFFBBF24), fontSize = 13.sp)
                        }
                    }
                }
            }
            Button(
                onClick = {
                    val saveLock = if (isCurrentlyLocked) initialAdultLock else adultLock
                    val savePin = if (isCurrentlyLocked) initialAdultPin else adultPin.trim()
                    if (saveLock && savePin.isEmpty()) pinWarning = "Ange en PIN-kod innan du sparar, eller välj Av."
                    else onSave(selectedMode, selectedFormat, m3u.trim(), server.trim(), user.trim(), pass.trim(), saveLock, savePin)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                shape = RoundedCornerShape(50)
            ) { Text("Spara & synka kanaler", fontWeight = FontWeight.Bold) }
        }
    }
}

@kotlin.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(item: MediaEntry, streamFormatMode: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var activeUrl by remember(item.url) { mutableStateOf(item.url) }
    var preferSoftware by remember(item.url) { mutableStateOf(false) }
    var triedFormatSwap by remember(item.url) { mutableStateOf(false) }
    var autoDecoderFallback by remember(item.url) { mutableStateOf(false) }
    var resumeMs by remember(item.url) { mutableStateOf(0L) }
    var resolutionText by remember(item.url) { mutableStateOf("Laddar...") }
    var audioTracks by remember(item.url) { mutableStateOf<List<TrackOption>>(emptyList()) }
    var subtitleTracks by remember(item.url) { mutableStateOf<List<TrackOption>>(emptyList()) }
    var selectedAudioIdx by remember(item.url) { mutableStateOf(0) }
    var selectedSubIdx by remember(item.url) { mutableStateOf(-1) }
    var currentTracksObj by remember(item.url) { mutableStateOf<Tracks?>(null) }

    var showOverlay by remember { mutableStateOf(true) }
    var overlayBump by remember { mutableStateOf(0) }

    LaunchedEffect(showOverlay, overlayBump) {
        if (showOverlay) {
            delay(4000)
            showOverlay = false
        }
    }

    val exoPlayer = remember(activeUrl, preferSoftware) {
        val selector = if (preferSoftware) {
            MediaCodecSelector { mime, sec, tun ->
                val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, sec, tun)
                all.filter { !it.hardwareAccelerated } + all.filter { it.hardwareAccelerated }
            }
        } else MediaCodecSelector.DEFAULT
        val rf = DefaultRenderersFactory(context).setEnableDecoderFallback(true).setMediaCodecSelector(selector)
        ExoPlayer.Builder(context, rf).build().apply {
            setMediaItem(MediaItem.fromUri(activeUrl))
            if (resumeMs > 0L) seekTo(resumeMs)
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(s: VideoSize) {
                if (s.width > 0 && s.height > 0) {
                    val q = when { s.height >= 2160 -> "4K"; s.height >= 1080 -> "1080p"; s.height >= 720 -> "720p"; else -> "SD" }
                    val tag = detectStreamTag(activeUrl)
                    resolutionText = if (tag != null) "$q • $tag" else q
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                showOverlay = true; overlayBump++
                if (streamFormatMode == "AUTO" && !triedFormatSwap && canSwapFormat(activeUrl)) {
                    val swapped = swapStreamFormat(activeUrl); triedFormatSwap = true
                    resolutionText = "Byter format..."
                    activeUrl = swapped
                } else if (!preferSoftware && !autoDecoderFallback) {
                    autoDecoderFallback = true; resumeMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                    resolutionText = "Byter till SW-avkodare..."; preferSoftware = true
                } else resolutionText = "Fel (${error.errorCodeName})"
            }
            override fun onTracksChanged(tracks: Tracks) {
                currentTracksObj = tracks
                val audios = mutableListOf<TrackOption>(); val subs = mutableListOf<TrackOption>()
                tracks.groups.forEachIndexed { gIdx, g ->
                    if (g.type == C.TRACK_TYPE_AUDIO) {
                        for (i in 0 until g.length) { val f = g.getTrackFormat(i); audios.add(TrackOption((f.language ?: f.label ?: "Spår ${audios.size + 1}").uppercase(), gIdx, i)) }
                    } else if (g.type == C.TRACK_TYPE_TEXT) {
                        for (i in 0 until g.length) { val f = g.getTrackFormat(i); subs.add(TrackOption((f.language ?: f.label ?: "Text ${subs.size + 1}").uppercase(), gIdx, i)) }
                    }
                }
                audioTracks = audios; subtitleTracks = subs
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener); exoPlayer.release() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusable()
            .clickable { showOverlay = !showOverlay; overlayBump++ }
    ) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer; useController = true; controllerShowTimeoutMs = 4000 } },
            update = { it.player = exoPlayer },
            modifier = Modifier.fillMaxSize()
        )

        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxWidth().align(Alignment.TopStart)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x99000000))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    NavPill("←", false, onBack)
                    Text(item.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("• $resolutionText", color = Color(0xFF93C5FD), fontSize = 11.sp, maxLines = 1)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val tag = detectStreamTag(activeUrl)
                    if (tag != null) NavPill(tag, false) { overlayBump++; activeUrl = swapStreamFormat(activeUrl) }
                    NavPill(if (preferSoftware) "SW" else "HW", preferSoftware) { overlayBump++; resumeMs = exoPlayer.currentPosition.coerceAtLeast(0L); preferSoftware = !preferSoftware }
                    NavPill("Ljud: ${audioTracks.getOrNull(selectedAudioIdx)?.label ?: "Std"}", false) {
                        overlayBump++
                        val t = currentTracksObj ?: return@NavPill
                        if (audioTracks.isEmpty()) return@NavPill
                        val next = (selectedAudioIdx + 1) % audioTracks.size; selectedAudioIdx = next; val c = audioTracks[next]
                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(t.groups[c.groupIndex].mediaTrackGroup, c.trackIndex)).build()
                    }
                    val subLabel = if (selectedSubIdx == -1) "Av" else subtitleTracks.getOrNull(selectedSubIdx)?.label ?: "Av"
                    NavPill("Text: $subLabel", false) {
                        overlayBump++
                        val t = currentTracksObj ?: return@NavPill
                        if (subtitleTracks.isEmpty()) return@NavPill
                        val next = if (selectedSubIdx + 1 >= subtitleTracks.size) -1 else selectedSubIdx + 1; selectedSubIdx = next
                        val b = exoPlayer.trackSelectionParameters.buildUpon()
                        if (next == -1) b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true) else { val c = subtitleTracks[next]; b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false); b.setOverrideForType(TrackSelectionOverride(t.groups[c.groupIndex].mediaTrackGroup, c.trackIndex)) }
                        exoPlayer.trackSelectionParameters = b.build()
                    }
                }
            }
        }
    }
}
