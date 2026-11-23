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
        if (entry.type == MediaType.SERIES) {
            selectedSeries = entry
        } else {
            playingItem = entry
        }
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
                    errorReason = "${t.javaClass.simpleName}:${t.message ?: "Okänt fel"}"
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
                    title = "${extractSeriesBaseTitle(selectedSeries!!.title)} • S${ep.season}E${ep.episodeNum}${ep.title}",
                    url = ep.url
                )
            }
        )
        return
    }

    BackHandler(enabled = currentSection != NavSection.HOME && !showUnlockDialog) { currentSection = NavSection.HOME }

    Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF05070C), Color(0xFF0B0F1A), Color(0xFF030407))))) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(Color(0xFF38BDF8)))
                    Text("OBSIDIAN", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
                }
                Surface(color = Color(0xD9121622), shape = RoundedCornerShape(50), border = BorderStroke(1.dp, Color(0x2EFFFFFF))) {
                    Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NavSection.entries.forEach { s -> NavPill(s.label, currentSection == s) { currentSection = s } }
                    }
                }
                Surface(color = Color(0x99121622), shape = RoundedCornerShape(50), border = BorderStroke(1.dp, Color(0x1FFFFFFF))) {
                    Text(statusText, color = Color(0xFF93C5FD), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

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
    var seasonsMap by remember(series.id) { mutableStateOf>>(emptyMap()) }
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
            .padding(horizontal = 36.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            NavPill("← Tillbaka", false, onBack)
            Column(modifier = Modifier.weight(1f)) {
                Text(extractSeriesBaseTitle(series.title), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${series.country} •${series.genre}", color = Color(0xFF93C5FD), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        if (loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Hämtar säsonger och avsnitt...", color = Color(0xFF93C5FD), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        } else if (seasonsMap.isEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(errorText ?: "Hittade inga separata säsonger/avsnitt för denna serie
