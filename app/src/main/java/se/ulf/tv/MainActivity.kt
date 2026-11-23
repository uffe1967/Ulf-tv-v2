package se.ulf.tv

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
    var currentSection by remember { mutableStateOf(NavSection.HOME) }
    var playingItem by remember { mutableStateOf<MediaEntry?>(null) }
    var statusText by remember { mutableStateOf("http://$localIp:8099/add") }

    val isAdultRestricted = adultLockEnabled && adultPin.isNotBlank() && !adultUnlocked
    val visibleCatalog = remember(catalog, isAdultRestricted) {
        if (isAdultRestricted) catalog.filterNot { it.isAdult } else catalog
    }

    fun syncCatalog(useMode: String, useFormat: String, url: String, server: String, user: String, pass: String) {
        scope.launch {
            statusText = "Synkar ($useFormat)..."
            try {
                val loaded = withContext(Dispatchers.IO) {
                    if (useMode == "XTREAM" && server.isNotBlank() && user.isNotBlank()) {
                        fetchXtreamCatalog(server, user, pass, useFormat)
                    } else if (url.isNotBlank()) {
                        parseM3u(fetchText(applyPlaylistFormat(url.trim(), useFormat)), useFormat)
                    } else {
                        demoCatalog()
                    }
                }
                if (loaded.isNotEmpty()) {
                    catalog = loaded
                    statusText = "${loaded.count { it.type == MediaType.LIVE }} Live • ${loaded.count { it.type == MediaType.MOVIE }} Filmer • ${loaded.count { it.type == MediaType.SERIES }} Serier"
                } else {
                    catalog = demoCatalog()
                    statusText = "Demoläge • http://$localIp:8099/add"
                }
            } catch (e: Exception) {
                statusText = "Kunde inte ladda: ${e.message ?: "Kontrollera adressen"}"
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
            .putString("mode", newMode).putString("stream_format", newFormat).putString("m3u_url", newM3u)
            .putString("xt_server", newServer).putString("xt_user", newUser).putString("xt_pass", newPass)
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
        if ((mode == "M3U" && m3uUrl.isNotBlank()) || (mode == "XTREAM" && xtServer.isNotBlank())) {
            syncCatalog(mode, streamFormat, m3uUrl, xtServer, xtUser, xtPass)
        }
    }

    if (playingItem != null) {
        BackHandler { playingItem = null }
        PlayerScreen(item = playingItem!!, streamFormatMode = streamFormat, onBack = { playingItem = null })
        return
    }

    BackHandler(enabled = currentSection != NavSection.HOME && !showUnlockDialog) { currentSection = NavSection.HOME }

    Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF05070C), Color(0xFF0B101B), Color(0xFF040609))))) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 20.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("OBSIDIAN", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                Surface(color = Color(0x99161C29), shape = RoundedCornerShape(50), border = BorderStroke(1.dp, Color(0x28FFFFFF))) {
                    Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NavSection.entries.forEach { s -> NavPill(s.label, currentSection == s) { currentSection = s } }
                    }
                }
                Text(statusText, color = Color(0xFF8E99AD), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(modifier = Modifier.height(20.dp))
            when (currentSection) {
                NavSection.HOME -> HomeView(visibleCatalog) { playingItem = it }
                NavSection.LIVE -> CatalogView(visibleCatalog.filter { it.type == MediaType.LIVE }, MediaType.LIVE, "Inga Live TV-kanaler hittades.") { playingItem = it }
                NavSection.MOVIES -> CatalogView(visibleCatalog.filter { it.type == MediaType.MOVIE }, MediaType.MOVIE, "Inga filmer hittades i spellistan.") { playingItem = it }
                NavSection.SERIES -> CatalogView(visibleCatalog.filter { it.type == MediaType.SERIES }, MediaType.SERIES, "Inga serier hittades i spellistan.") { playingItem = it }
                NavSection.SETTINGS -> SettingsView(
                    localIp, mode, streamFormat, m3uUrl, xtServer, xtUser, xtPass, adultLockEnabled, adultPin, adultUnlocked,
                    onRequestUnlockAdult = { showUnlockDialog = true },
                    onLockAdultNow = { adultUnlocked = false },
                    onBack = { currentSection = NavSection.HOME },
                    onSave = ::saveAll
                )
            }
        }
        if (showUnlockDialog) {
            BackHandler { showUnlockDialog = false }
            PinUnlockOverlay(adultPin, { adultUnlocked = true; showUnlockDialog = false }, { showUnlockDialog = false })
        }
    }
}

@Composable
fun AsyncCoverImage(url: String, title: String, contentScale: ContentScale, maxDimension: Int, modifier: Modifier = Modifier, fallbackBackdrop: Boolean = true) {
    val cacheKey = "${url}_$maxDimension"
    var bitmap by remember(cacheKey) { mutableStateOf(CoverImageCache.get(cacheKey)) }
    LaunchedEffect(cacheKey) {
        if (bitmap == null && (url.startsWith("http://", true) || url.startsWith("https://", true))) {
            val loaded = withContext(Dispatchers.IO) { loadScaledBitmap(url, maxDimension) }
            if (loaded != null) { CoverImageCache.put(cacheKey, loaded); bitmap = loaded }
        }
    }
    val fallbackBrush = remember(title) {
        val palettes = listOf(
            listOf(Color(0xFF1A2540), Color(0xFF0D1322)), listOf(Color(0xFF2B1C34), Color(0xFF120C18)),
            listOf(Color(0xFF142F2E), Color(0xFF0A1718)), listOf(Color(0xFF33201E), Color(0xFF160E0D)),
            listOf(Color(0xFF1E293B), Color(0xFF0F172A))
        )
        Brush.linearGradient(palettes[abs(title.hashCode()) % palettes.size])
    }
    Box(modifier = modifier.then(if (fallbackBackdrop) Modifier.background(fallbackBrush) else Modifier), contentAlignment = Alignment.Center) {
        val bmp = bitmap
        if (bmp != null) {
            Image(bitmap = bmp, contentDescription = title, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        } else if (fallbackBackdrop) {
            val initials = title.split(" ", "-", "|").filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }.ifBlank { "TV" }
            Text(initials, color = Color(0x26FFFFFF), fontSize = 44.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
        }
    }
}

@Composable
fun PinUnlockOverlay(expectedPin: String, onUnlocked: () -> Unit, onDismiss: () -> Unit) {
    var enteredPin by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    Box(modifier = Modifier.fillMaxSize().background(Color(0xD9000000)).padding(24.dp), contentAlignment = Alignment.Center) {
        Surface(color = Color(0xFF141B29), shape = RoundedCornerShape(24.dp), border = BorderStroke(1.5.dp, Color(0x44FFFFFF)), modifier = Modifier.width(420.dp)) {
            Column(modifier = Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Lås upp 18+ / Vuxenkanaler", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Ange din PIN-kod för att visa vuxeninnehåll.", color = Color(0xFF9CA3AF), fontSize = 14.sp)
                OutlinedTextField(value = enteredPin, onValueChange = { enteredPin = it; errorMsg = null }, label = { Text("Lösenord / PIN") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                if (errorMsg != null) Text(errorMsg!!, color = Color(0xFFF87171), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { if (enteredPin == expectedPin) onUnlocked() else errorMsg = "Fel lösenord, försök igen." }, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black), shape = RoundedCornerShape(50), modifier = Modifier.weight(1f)) { Text("Lås upp", fontWeight = FontWeight.Bold) }
                    Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF263042), contentColor = Color.White), shape = RoundedCornerShape(50), modifier = Modifier.weight(1f)) { Text("Avbryt") }
                }
            }
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
            .background(if (focused) Color.White else if (selected) Color(0x38FFFFFF) else Color.Transparent)
            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else if (selected) Color(0x55FFFFFF) else Color.Transparent, RoundedCornerShape(50))
            .onFocusChanged { focused = it.isFocused }.focusable().clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 9.dp)
    ) {
        Text(label, color = if (focused) Color.Black else Color.White, fontSize = 14.sp, fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
fun HomeView(catalog: List<MediaEntry>, onPlay: (MediaEntry) -> Unit) {
    val movies = remember(catalog) { catalog.filter { it.type == MediaType.MOVIE }.take(24) }
    val series = remember(catalog) { catalog.filter { it.type == MediaType.SERIES }.take(24) }
    val live = remember(catalog) { catalog.filter { it.type == MediaType.LIVE }.take(24) }
    val defaultFeatured = remember(catalog) { catalog.firstOrNull { it.imageUrl.isNotBlank() && it.type != MediaType.LIVE } ?: catalog.firstOrNull { it.imageUrl.isNotBlank() } ?: catalog.firstOrNull() }
    var heroItem by remember(defaultFeatured) { mutableStateOf(defaultFeatured) }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(26.dp)) {
        if (heroItem != null) AppleTvHeroBanner(heroItem!!) { onPlay(heroItem!!) }
        if (movies.isNotEmpty()) MediaRow("Filmer", movies, true, { heroItem = it }, onPlay)
        if (series.isNotEmpty()) MediaRow("Serier", series, true, { heroItem = it }, onPlay)
        if (live.isNotEmpty()) MediaRow("Live TV", live, false, { heroItem = it }, onPlay)
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun AppleTvHeroBanner(item: MediaEntry, onPlay: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.015f else 1f, label = "hero")
    Box(
        modifier = Modifier.fillMaxWidth().height(290.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(if (focused) 24.dp else 10.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .border(if (focused) 2.5.dp else 1.dp, if (focused) Color.White else Color(0x28FFFFFF), RoundedCornerShape(24.dp))
            .onFocusChanged { focused = it.isFocused }.focusable().clickable { onPlay() }
    ) {
        AsyncCoverImage(item.imageUrl, item.title, ContentScale.Crop, 960, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xE605070C), Color(0x9905070C), Color(0x2205070C)))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x5505070C), Color(0xE605070C)))))
        Column(modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 32.dp, vertical = 26.dp).fillMaxWidth(0.72f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(color = Color(0x38FFFFFF), shape = RoundedCornerShape(50), border = BorderStroke(1.dp, Color(0x40FFFFFF))) {
                Text("${item.type.label.uppercase()}  •  ${item.country}  •  ${item.genre}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
            }
            Text(item.title, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Surface(color = Color.White, shape = RoundedCornerShape(50)) {
                Text("▶  Spela nu", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp))
            }
        }
    }
}

@Composable
fun MediaRow(title: String, items: List<MediaEntry>, isPosterStyle: Boolean, onFocusItem: (MediaEntry) -> Unit, onPlay: (MediaEntry) -> Unit) {
    Column {
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            items(items) { item ->
                if (isPosterStyle) PosterCard(item, Modifier.width(164.dp).height(246.dp), { onFocusItem(item) }) { onPlay(item) }
                else LiveChannelCard(item, Modifier.width(248.dp).height(146.dp), { onFocusItem(item) }) { onPlay(item) }
            }
        }
    }
}

@Composable
fun CatalogView(items: List<MediaEntry>, mediaType: MediaType, emptyLabel: String, onPlay: (MediaEntry) -> Unit) {
    var selectedCountry by remember(items) { mutableStateOf("Alla länder") }
    var selectedGenre by remember(items) { mutableStateOf("Alla genrer") }
    val countries = remember(items) { listOf("Alla länder") + items.map { it.country }.distinct().sorted() }
    val countryFiltered = remember(items, selectedCountry) { if (selectedCountry == "Alla länder") items else items.filter { it.country == selectedCountry } }
    val genres = remember(countryFiltered) { listOf("Alla genrer") + countryFiltered.map { it.genre }.distinct().sorted() }
    val finalItems = remember(countryFiltered, selectedGenre) { if (selectedGenre == "Alla genrer") countryFiltered else countryFiltered.filter { it.genre == selectedGenre } }
    val isPosterGrid = mediaType != MediaType.LIVE

    Column(modifier = Modifier.fillMaxSize()) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(countries) { c -> NavPill(c, selectedCountry == c) { selectedCountry = c; selectedGenre = "Alla genrer" } } }
        Spacer(modifier = Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(genres) { g -> NavPill(g, selectedGenre == g) { selectedGenre = g } } }
        Spacer(modifier = Modifier.height(14.dp))
        if (finalItems.isEmpty()) Text(emptyLabel, color = Color(0xFF9CA3AF), fontSize = 16.sp)
        else LazyVerticalGrid(
            columns = GridCells.Adaptive(if (isPosterGrid) 160.dp else 230.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp), horizontalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 12.dp), modifier = Modifier.fillMaxSize()
        ) {
            items(finalItems) { item ->
                if (isPosterGrid) PosterCard(item, Modifier.fillMaxWidth().aspectRatio(2f / 3f), {}) { onPlay(item) }
                else LiveChannelCard(item, Modifier.fillMaxWidth().aspectRatio(16f / 9f), {}) { onPlay(item) }
            }
        }
    }
}

@Composable
fun PosterCard(item: MediaEntry, modifier: Modifier, onFocused: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.07f else 1f, label = "poster")
    Box(
        modifier = modifier.zIndex(if (focused) 1f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(if (focused) 22.dp else 6.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .border(if (focused) 3.dp else 1.dp, if (focused) Color.White else Color(0x26FFFFFF), RoundedCornerShape(18.dp))
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }.focusable().clickable { onClick() }
    ) {
        AsyncCoverImage(item.imageUrl, item.title, ContentScale.Crop, 480, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x22000000), Color(0xE605070C)))))
        Column(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(item.genre, color = if (focused) Color(0xFF93C5FD) else Color(0xFFB4BED2), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun LiveChannelCard(item: MediaEntry, modifier: Modifier, onFocused: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.07f else 1f, label = "live")
    Box(
        modifier = modifier.zIndex(if (focused) 1f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(if (focused) 22.dp else 6.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(if (focused) listOf(Color(0xFF202B40), Color(0xFF101624)) else listOf(Color(0xFF161D2C), Color(0xFF0C101A))))
            .border(if (focused) 3.dp else 1.dp, if (focused) Color.White else Color(0x26FFFFFF), RoundedCornerShape(18.dp))
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }.focusable().clickable { onClick() }
    ) {
        if (item.imageUrl.isNotBlank()) AsyncCoverImage(item.imageUrl, item.title, ContentScale.Fit, 420, Modifier.fillMaxSize().padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 50.dp), false)
        else AsyncCoverImage("", item.title, ContentScale.Crop, 420, Modifier.fillMaxSize(), true)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x3305070C), Color(0xE605070C)))))
        Surface(color = Color(0xCCDC2626), shape = RoundedCornerShape(50), modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)) {
            Text("LIVE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
        }
        Column(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${item.country} • ${item.genre}", color = if (focused) Color(0xFF93C5FD) else Color(0xFF9CA3AF), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

    Surface(color = Color(0xFF111726), shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, Color(0x28FFFFFF)), modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Column(modifier = Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                NavPill("← Tillbaka", false, onBack)
                Text("Inställningar", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Surface(color = Color(0xFF192338), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Color(0xFF38BDF8)), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Snabbinmatning från dator eller mobil (samma Wi-Fi):", color = Color(0xFF93C5FD), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("http://$localIp:8099/add", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
            Text("IPTV-källa", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
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
            Text("Strömformat för Live TV", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NavPill("Auto (Smart växling)", selectedFormat == "AUTO") { selectedFormat = "AUTO" }
                NavPill("Alltid HLS (.m3u8)", selectedFormat == "HLS") { selectedFormat = "HLS" }
                NavPill("Alltid MPEG-TS (.ts)", selectedFormat == "MPEG-TS") { selectedFormat = "MPEG-TS" }
            }
            Surface(color = Color(0xFF172033), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Color(0x26FFFFFF)), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Föräldrakontroll (18+ / Vuxenkanaler)", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    if (isCurrentlyLocked) {
                        Text("18+-innehåll är dolt och skyddat med PIN-kod. Lås upp här nedan för att visa vuxenkanaler eller ändra PIN.", color = Color(0xFF9CA3AF), fontSize = 13.sp)
                        NavPill("Lås upp 18+ med PIN", true, onRequestUnlockAdult)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            NavPill("Av (Olåst)", !adultLock) { adultLock = false; pinWarning = null }
                            NavPill("På (Kräv lösenord)", adultLock) { adultLock = true }
                            if (initialAdultLock && initialAdultPin.isNotBlank() && adultUnlocked) NavPill("Lås 18+ nu", false, onLockAdultNow)
                        }
                        if (adultLock) {
                            OutlinedTextField(value = adultPin, onValueChange = { adultPin = it; pinWarning = null }, label = { Text("Lösenord / PIN-kod för 18+ (t.ex. 1234)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
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
    var resolutionText by remember(item.url) { mutableStateOf("Laddar ström...") }
    var audioTracks by remember(item.url) { mutableStateOf<List<TrackOption>>(emptyList()) }
    var subtitleTracks by remember(item.url) { mutableStateOf<List<TrackOption>>(emptyList()) }
    var selectedAudioIdx by remember(item.url) { mutableStateOf(0) }
    var selectedSubIdx by remember(item.url) { mutableStateOf(-1) }
    var currentTracksObj by remember(item.url) { mutableStateOf<Tracks?>(null) }

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
            prepare(); playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(s: VideoSize) {
                if (s.width > 0 && s.height > 0) {
                    val q = when { s.height >= 2160 -> "4K UHD"; s.height >= 1080 -> "Full HD"; s.height >= 720 -> "HD"; else -> "SD" }
                    val tag = detectStreamTag(activeUrl)
                    resolutionText = if (tag != null) "${s.width}×${s.height} ($q • $tag)" else "${s.width}×${s.height} ($q)"
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                if (streamFormatMode == "AUTO" && !triedFormatSwap && canSwapFormat(activeUrl)) {
                    val swapped = swapStreamFormat(activeUrl); triedFormatSwap = true
                    resolutionText = "Byter automatiskt till ${detectStreamTag(swapped) ?: "annat format"}..."
                    activeUrl = swapped
                } else if (!preferSoftware && !autoDecoderFallback) {
                    autoDecoderFallback = true; resumeMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                    resolutionText = "Byter till mjukvaruavkodare..."; preferSoftware = true
                } else resolutionText = "Uppspelningsfel (${error.errorCodeName})"
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

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer; useController = true } }, update = { it.player = exoPlayer }, modifier = Modifier.fillMaxSize())
        Row(modifier = Modifier.fillMaxWidth().align(Alignment.TopStart).background(Color(0xAA000000)).padding(horizontal = 24.dp, vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                NavPill("← Tillbaka", false, onBack)
                Column {
                    Text(item.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("$resolutionText • ${item.country} • ${item.genre}", color = Color(0xFF93C5FD), fontSize = 12.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val tag = detectStreamTag(activeUrl)
                if (tag != null) NavPill("Format: $tag", false) { val sw = swapStreamFormat(activeUrl); resolutionText = "Växlar till ${detectStreamTag(sw) ?: ""}..."; activeUrl = sw }
                NavPill(if (preferSoftware) "Avkodare: SW" else "Avkodare: Auto", preferSoftware) { resumeMs = exoPlayer.currentPosition.coerceAtLeast(0L); preferSoftware = !preferSoftware }
                NavPill("Ljud: ${audioTracks.getOrNull(selectedAudioIdx)?.label ?: "Standard"}", false) {
                    val t = currentTracksObj ?: return@NavPill
                    if (audioTracks.isEmpty()) return@NavPill
                    val next = (selectedAudioIdx + 1) % audioTracks.size; selectedAudioIdx = next; val c = audioTracks[next]
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(t.groups[c.groupIndex].mediaTrackGroup, c.trackIndex)).build()
                }
                val subLabel = if (selectedSubIdx == -1) "Av" else subtitleTracks.getOrNull(selectedSubIdx)?.label ?: "Av"
                NavPill("Text: $subLabel", false) {
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
// SLUT PÅ MAINACTIVITY
