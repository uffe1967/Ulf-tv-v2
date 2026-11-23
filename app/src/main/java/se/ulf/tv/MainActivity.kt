package se.ulf.tv

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder

enum class MediaType(val label: String) {
    LIVE("Live TV"),
    MOVIE("Filmer"),
    SERIES("Serier")
}

enum class NavSection(val label: String) {
    HOME("Hem"),
    LIVE("Live TV"),
    MOVIES("Filmer"),
    SERIES("Serier"),
    SETTINGS("Inställningar")
}

data class MediaEntry(
    val id: String,
    val title: String,
    val country: String,
    val genre: String,
    val url: String,
    val type: MediaType,
    val isAdult: Boolean = false
)

data class TrackOption(
    val label: String,
    val groupIndex: Int,
    val trackIndex: Int
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                UlfTvApp()
            }
        }
    }
}

@Composable
fun UlfTvApp() {
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
    var pendingAdultItem by remember { mutableStateOf<MediaEntry?>(null) }

    var catalog by remember { mutableStateOf(demoCatalog()) }
    var currentSection by remember { mutableStateOf(NavSection.HOME) }
    var playingItem by remember { mutableStateOf<MediaEntry?>(null) }
    var statusText by remember { mutableStateOf("Webbsida: http://$localIp:8099/add") }

    val isAdultRestricted = adultLockEnabled && adultPin.isNotBlank() && !adultUnlocked
    val hasAdultItems = remember(catalog) { catalog.any { it.isAdult } }

    fun handleSelectMedia(entry: MediaEntry) {
        if (entry.isAdult && isAdultRestricted) {
            pendingAdultItem = entry
            showUnlockDialog = true
        } else {
            playingItem = entry
        }
    }

    fun syncCatalog(
        useMode: String,
        useFormat: String,
        url: String,
        server: String,
        user: String,
        pass: String
    ) {
        scope.launch {
            statusText = "Synkar kanaler, filmer och serier ($useFormat)..."
            try {
                val loaded = withContext(Dispatchers.IO) {
                    if (useMode == "XTREAM" && server.isNotBlank() && user.isNotBlank()) {
                        fetchXtreamCatalog(server, user, pass, useFormat)
                    } else if (url.isNotBlank()) {
                        val adjustedPlaylistUrl = applyPlaylistFormat(url.trim(), useFormat)
                        parseM3u(fetchText(adjustedPlaylistUrl), useFormat)
                    } else {
                        demoCatalog()
                    }
                }
                if (loaded.isNotEmpty()) {
                    catalog = loaded
                    val liveCount = loaded.count { it.type == MediaType.LIVE }
                    val movieCount = loaded.count { it.type == MediaType.MOVIE }
                    val seriesCount = loaded.count { it.type == MediaType.SERIES }
                    statusText = "$liveCount Live • $movieCount Filmer • $seriesCount Serier ($useFormat)"
                } else {
                    catalog = demoCatalog()
                    statusText = "Inga strömmar hittades – visar demokatalog"
                }
            } catch (e: Exception) {
                statusText = "Kunde inte ladda: ${e.message ?: "Kontrollera adressen"}"
            }
        }
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
                        handleWebClient(
                            socket = client,
                            currentMode = mode,
                            currentFormat = streamFormat,
                            currentM3u = m3uUrl,
                            currentServer = xtServer,
                            currentUser = xtUser,
                            currentPass = xtPass,
                            currentAdultLock = adultLockEnabled,
                            currentAdultPin = adultPin,
                            onApplyConfig = { newMode, newFormat, newM3u, newServer, newUser, newPass, newAdultLock, newAdultPin ->
                                withContext(Dispatchers.Main) {
                                    mode = newMode
                                    streamFormat = newFormat
                                    m3uUrl = newM3u
                                    xtServer = newServer
                                    xtUser = newUser
                                    xtPass = newPass
                                    adultLockEnabled = newAdultLock
                                    adultPin = newAdultPin
                                    if (newAdultLock) {
                                        adultUnlocked = false
                                    }
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
                            }
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }
        onDispose {
            try {
                serverSocket?.close()
            } catch (_: Exception) {
            }
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
        PlayerScreen(
            item = playingItem!!,
            streamFormatMode = streamFormat,
            onBack = { playingItem = null }
        )
        return
    }

    BackHandler(enabled = currentSection != NavSection.HOME && !showUnlockDialog) {
        currentSection = NavSection.HOME
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0A0D14), Color(0xFF111827), Color(0xFF090B10))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 36.dp, vertical = 22.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ULF TV",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.5.sp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    NavSection.entries.forEach { section ->
                        NavPill(
                            label = section.label,
                            selected = currentSection == section,
                            onClick = { currentSection = section }
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (adultLockEnabled && adultPin.isNotBlank() && hasAdultItems) {
                        NavPill(
                            label = if (adultUnlocked) "🔓 Lås 18+" else "🔒 Lås upp 18+",
                            selected = adultUnlocked,
                            onClick = {
                                if (adultUnlocked) {
                                    adultUnlocked = false
                                } else {
                                    pendingAdultItem = null
                                    showUnlockDialog = true
                                }
                            }
                        )
                    }
                    Text(
                        text = statusText,
                        color = Color(0xFF9CA3AF),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            when (currentSection) {
                NavSection.HOME -> HomeView(
                    catalog = if (isAdultRestricted) catalog.filterNot { it.isAdult } else catalog,
                    hideAdult = isAdultRestricted,
                    onPlay = ::handleSelectMedia
                )
                NavSection.LIVE -> CatalogView(
                    items = catalog.filter { it.type == MediaType.LIVE },
                    hideAdult = isAdultRestricted,
                    emptyLabel = "Inga Live TV-kanaler hittades.",
                    onPlay = ::handleSelectMedia
                )
                NavSection.MOVIES -> CatalogView(
                    items = catalog.filter { it.type == MediaType.MOVIE },
                    hideAdult = isAdultRestricted,
                    emptyLabel = "Inga filmer hittades i spellistan.",
                    onPlay = ::handleSelectMedia
                )
                NavSection.SERIES -> CatalogView(
                    items = catalog.filter { it.type == MediaType.SERIES },
                    hideAdult = isAdultRestricted,
                    emptyLabel = "Inga serier hittades i spellistan.",
                    onPlay = ::handleSelectMedia
                )
                NavSection.SETTINGS -> SettingsView(
                    localIp = localIp,
                    initialMode = mode,
                    initialFormat = streamFormat,
                    initialM3u = m3uUrl,
                    initialServer = xtServer,
                    initialUser = xtUser,
                    initialPass = xtPass,
                    initialAdultLock = adultLockEnabled,
                    initialAdultPin = adultPin,
                    onBack = { currentSection = NavSection.HOME },
                    onSave = { newMode, newFormat, newM3u, newServer, newUser, newPass, newAdultLock, newAdultPin ->
                        mode = newMode
                        streamFormat = newFormat
                        m3uUrl = newM3u
                        xtServer = newServer
                        xtUser = newUser
                        xtPass = newPass
                        adultLockEnabled = newAdultLock
                        adultPin = newAdultPin
                        if (newAdultLock) {
                            adultUnlocked = false
                        }
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
                )
            }
        }

        if (showUnlockDialog) {
            BackHandler {
                showUnlockDialog = false
                pendingAdultItem = null
            }
            PinUnlockOverlay(
                expectedPin = adultPin,
                onUnlocked = {
                    adultUnlocked = true
                    showUnlockDialog = false
                    val toPlay = pendingAdultItem
                    pendingAdultItem = null
                    if (toPlay != null) {
                        playingItem = toPlay
                    }
                },
                onDismiss = {
                    showUnlockDialog = false
                    pendingAdultItem = null
                }
            )
        }
    }
}

@Composable
fun PinUnlockOverlay(
    expectedPin: String,
    onUnlocked: () -> Unit,
    onDismiss: () -> Unit
) {
    var enteredPin by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = Color(0xFF161D2B),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.5.dp, Color(0x44FFFFFF)),
            modifier = Modifier.width(420.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "🔒 18+ / Vuxenkanaler låsta",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Ange ditt lösenord för att låsa upp 18+-innehåll.",
                    color = Color(0xFF9CA3AF),
                    fontSize = 14.sp
                )
                OutlinedTextField(
                    value = enteredPin,
                    onValueChange = {
                        enteredPin = it
                        errorMsg = null
                    },
                    label = { Text("Lösenord / PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (errorMsg != null) {
                    Text(
                        text = errorMsg!!,
                        color = Color(0xFFF87171),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = {
                            if (enteredPin == expectedPin) {
                                onUnlocked()
                            } else {
                                errorMsg = "Fel lösenord, försök igen."
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Lås upp", fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF263042),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Avbryt")
                    }
                }
            }
        }
    }
}

@Composable
fun NavPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> Color.White
        selected -> Color(0x33FFFFFF)
        else -> Color.Transparent
    }
    val textColor = if (focused) Color.Black else Color.White

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Color.White else if (selected) Color(0x55FFFFFF) else Color.Transparent,
                shape = RoundedCornerShape(50)
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 9.dp)
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 14.sp,
            fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
fun HomeView(
    catalog: List<MediaEntry>,
    hideAdult: Boolean,
    onPlay: (MediaEntry) -> Unit
) {
    val featured = catalog.firstOrNull()
    val live = remember(catalog) { catalog.filter { it.type == MediaType.LIVE }.take(20) }
    val movies = remember(catalog) { catalog.filter { it.type == MediaType.MOVIE }.take(20) }
    val series = remember(catalog) { catalog.filter { it.type == MediaType.SERIES }.take(20) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        if (featured != null) {
            Surface(
                color = Color(0xFF161D2B),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Color(0x22FFFFFF)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${featured.type.label.uppercase()} • ${featured.country} •${featured.genre}",
                            color = Color(0xFF60A5FA),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = featured.title,
                            color = Color.White,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Button(
                        onClick = { onPlay(featured) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(50)
                    ) {
                        Text("Spela nu", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (live.isNotEmpty()) {
            MediaRow(title = "Live TV", items = live, hideAdult = hideAdult, onPlay = onPlay)
        }
        if (movies.isNotEmpty()) {
            MediaRow(title = "Filmer", items = movies, hideAdult = hideAdult, onPlay = onPlay)
        }
        if (series.isNotEmpty()) {
            MediaRow(title = "Serier", items = series, hideAdult = hideAdult, onPlay = onPlay)
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun MediaRow(
    title: String,
    items: List<MediaEntry>,
    hideAdult: Boolean,
    onPlay: (MediaEntry) -> Unit
) {
    Column {
        Text(
            text = title,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(items) { item ->
                MediaCard(
                    item = item,
                    locked = item.isAdult && hideAdult,
                    modifier = Modifier.width(230.dp),
                    onClick = { onPlay(item) }
                )
            }
        }
    }
}

@Composable
fun CatalogView(
    items: List<MediaEntry>,
    hideAdult: Boolean,
    emptyLabel: String,
    onPlay: (MediaEntry) -> Unit
) {
    var selectedCountry by remember(items) { mutableStateOf("Alla länder") }
    var selectedGenre by remember(items) { mutableStateOf("Alla genrer") }

    val countries = remember(items) {
        listOf("Alla länder") + items.map { it.country }.distinct().sorted()
    }
    val countryFiltered = remember(items, selectedCountry) {
        if (selectedCountry == "Alla länder") items else items.filter { it.country == selectedCountry }
    }
    val genres = remember(countryFiltered) {
        listOf("Alla genrer") + countryFiltered.map { it.genre }.distinct().sorted()
    }
    val finalItems = remember(countryFiltered, selectedGenre) {
        if (selectedGenre == "Alla genrer") countryFiltered else countryFiltered.filter { it.genre == selectedGenre }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(countries) { country ->
                NavPill(
                    label = country,
                    selected = selectedCountry == country,
                    onClick = {
                        selectedCountry = country
                        selectedGenre = "Alla genrer"
                    }
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(genres) { genre ->
                NavPill(
                    label = genre,
                    selected = selectedGenre == genre,
                    onClick = { selectedGenre = genre }
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        if (finalItems.isEmpty()) {
            Text(text = emptyLabel, color = Color(0xFF9CA3AF), fontSize = 16.sp)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 220.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(finalItems) { item ->
                    MediaCard(
                        item = item,
                        locked = item.isAdult && hideAdult,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onPlay(item) }
                    )
                }
            }
        }
    }
}

@Composable
fun MediaCard(
    item: MediaEntry,
    locked: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }

    Surface(
        color = if (focused) Color(0xFF232F46) else Color(0xFF151B28),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            width = if (focused) 2.5.dp else 1.dp,
            color = if (focused) Color.White else Color(0x1FFFFFFF)
        ),
        modifier = modifier
            .height(112.dp)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (locked) "18+ • Skyddat innehåll" else "${item.country} •${item.genre}",
                color = if (locked) Color(0xFFFBBF24) else if (focused) Color(0xFF93C5FD) else Color(0xFF9CA3AF),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (locked) "🔒 18+ Låst kanal" else item.title,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun SettingsView(
    localIp: String,
    initialMode: String,
    initialFormat: String,
    initialM3u: String,
    initialServer: String,
    initialUser: String,
    initialPass: String,
    initialAdultLock: Boolean,
    initialAdultPin: String,
    onBack: () -> Unit,
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

    Surface(
        color = Color(0xFF151B28),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color(0x22FFFFFF)),
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavPill(label = "← Tillbaka", selected = false, onClick = onBack)
                Text(
                    text = "Inställningar",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Surface(
                color = Color(0xFF1E293B),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color(0xFF38BDF8)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Snabbinmatning från dator eller mobil (samma Wi-Fi):",
                        color = Color(0xFF93C5FD),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "http://$localIp:8099/add",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            Text(
                text = "IPTV-källa",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NavPill(
                    label = "M3U-länk",
                    selected = selectedMode == "M3U",
                    onClick = { selectedMode = "M3U" }
                )
                NavPill(
                    label = "Xtream Codes",
                    selected = selectedMode == "XTREAM",
                    onClick = { selectedMode = "XTREAM" }
                )
            }

            if (selectedMode == "M3U") {
                OutlinedTextField(
                    value = m3u,
                    onValueChange = { m3u = it },
                    label = { Text("M3U-spellista URL (http://...)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = { Text("Server-URL (t.ex. http://server.com:8080)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = user,
                        onValueChange = { user = it },
                        label = { Text("Användarnamn") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it },
                        label = { Text("Lösenord") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Strömformat för Live TV",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NavPill(
                    label = "Auto (Smart växling)",
                    selected = selectedFormat == "AUTO",
                    onClick = { selectedFormat = "AUTO" }
                )
                NavPill(
                    label = "Alltid HLS (.m3u8)",
                    selected = selectedFormat == "HLS",
                    onClick = { selectedFormat = "HLS" }
                )
                NavPill(
                    label = "Alltid MPEG-TS (.ts)",
                    selected = selectedFormat == "MPEG-TS",
                    onClick = { selectedFormat = "MPEG-TS" }
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Föräldrakontroll (18+ / Vuxenkanaler)",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NavPill(
                    label = "Av (Olåst)",
                    selected = !adultLock,
                    onClick = {
                        adultLock = false
                        pinWarning = null
                    }
                )
                NavPill(
                    label = "På (Kräv lösenord)",
                    selected = adultLock,
                    onClick = { adultLock = true }
                )
            }

            if (adultLock) {
                OutlinedTextField(
                    value = adultPin,
                    onValueChange = {
                        adultPin = it
                        pinWarning = null
                    },
                    label = { Text("Lösenord / PIN-kod för 18+ (t.ex. 1234)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (pinWarning != null) {
                    Text(
                        text = pinWarning!!,
                        color = Color(0xFFFBBF24),
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = {
                    if (adultLock && adultPin.trim().isEmpty()) {
                        pinWarning = "Ange ett lösenord/PIN-kod innan du sparar, eller välj Av."
                    } else {
                        onSave(
                            selectedMode,
                            selectedFormat,
                            m3u.trim(),
                            server.trim(),
                            user.trim(),
                            pass.trim(),
                            adultLock,
                            adultPin.trim()
                        )
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(50)
            ) {
                Text("Spara & synka kanaler", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@kotlin.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    item: MediaEntry,
    streamFormatMode: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var activeUrl by remember(item.url) { mutableStateOf(item.url) }
    var preferSoftware by remember(item.url) { mutableStateOf(false) }
    var triedFormatSwap by remember(item.url) { mutableStateOf(false) }
    var autoDecoderFallbackTriggered by remember(item.url) { mutableStateOf(false) }
    var resumePositionMs by remember(item.url) { mutableStateOf(0L) }
    var resolutionText by remember(item.url) { mutableStateOf("Laddar ström...") }
    var audioTracks by remember(
