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

    // Inbyggd webbserver på port 8099 (/add) så du kan klistra in länkar från dator/mobil
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
                // Stängs ner när appen avslutas
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
                            text = "${featured.type.label.uppercase()} • ${featured.country} • ${featured.genre}",
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
                text = if (locked) "18+ • Skyddat innehåll" else "${item.country} • ${item.genre}",
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
                fontSize = 20.sp,
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
    var audioTracks by remember(item.url) { mutableStateOf<List<TrackOption>>(emptyList()) }
    var subtitleTracks by remember(item.url) { mutableStateOf<List<TrackOption>>(emptyList()) }
    var selectedAudioIdx by remember(item.url) { mutableStateOf(0) }
    var selectedSubIdx by remember(item.url) { mutableStateOf(-1) }
    var currentTracksObj by remember(item.url) { mutableStateOf<Tracks?>(null) }

    val exoPlayer = remember(activeUrl, preferSoftware) {
        val codecSelector = if (preferSoftware) {
            MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                val all = MediaCodecSelector.DEFAULT.getDecoderInfos(
                    mimeType,
                    requiresSecureDecoder,
                    requiresTunnelingDecoder
                )
                val sw = all.filter { !it.hardwareAccelerated }
                val hw = all.filter { it.hardwareAccelerated }
                sw + hw
            }
        } else {
            MediaCodecSelector.DEFAULT
        }

        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(codecSelector)

        ExoPlayer.Builder(context, renderersFactory).build().apply {
            setMediaItem(MediaItem.fromUri(activeUrl))
            if (resumePositionMs > 0L) {
                seekTo(resumePositionMs)
            }
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    val quality = when {
                        videoSize.height >= 2160 -> "4K UHD"
                        videoSize.height >= 1080 -> "Full HD"
                        videoSize.height >= 720 -> "HD"
                        else -> "SD"
                    }
                    val fmtTag = detectStreamTag(activeUrl)
                    resolutionText = if (fmtTag != null) {
                        "${videoSize.width}×${videoSize.height} ($quality • $fmtTag)"
                    } else {
                        "${videoSize.width}×${videoSize.height} ($quality)"
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (streamFormatMode == "AUTO" && !triedFormatSwap && canSwapFormat(activeUrl)) {
                    val swapped = swapStreamFormat(activeUrl)
                    triedFormatSwap = true
                    val nextTag = detectStreamTag(swapped) ?: "annat format"
                    resolutionText = "Byter automatiskt till $nextTag..."
                    activeUrl = swapped
                } else if (!preferSoftware && !autoDecoderFallbackTriggered) {
                    autoDecoderFallbackTriggered = true
                    resumePositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                    resolutionText = "Byter till mjukvaruavkodare..."
                    preferSoftware = true
                } else {
                    resolutionText = "Uppspelningsfel (${error.errorCodeName})"
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                currentTracksObj = tracks
                val audios = mutableListOf<TrackOption>()
                val subs = mutableListOf<TrackOption>()
                tracks.groups.forEachIndexed { gIndex, group ->
                    if (group.type == C.TRACK_TYPE_AUDIO) {
                        for (i in 0 until group.length) {
                            val fmt = group.getTrackFormat(i)
                            val lang = fmt.language ?: fmt.label ?: "Spår ${audios.size + 1}"
                            audios.add(TrackOption(lang.uppercase(), gIndex, i))
                        }
                    } else if (group.type == C.TRACK_TYPE_TEXT) {
                        for (i in 0 until group.length) {
                            val fmt = group.getTrackFormat(i)
                            val lang = fmt.language ?: fmt.label ?: "Text ${subs.size + 1}"
                            subs.add(TrackOption(lang.uppercase(), gIndex, i))
                        }
                    }
                }
                audioTracks = audios
                subtitleTracks = subs
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    fun toggleStreamFormat() {
        if (!canSwapFormat(activeUrl)) return
        val swapped = swapStreamFormat(activeUrl)
        val nextTag = detectStreamTag(swapped) ?: ""
        resolutionText = "Växlar till $nextTag..."
        activeUrl = swapped
    }

    fun toggleDecoder() {
        resumePositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
        preferSoftware = !preferSoftware
    }

    fun cycleAudio() {
        val tracks = currentTracksObj ?: return
        if (audioTracks.isEmpty()) return
        val next = (selectedAudioIdx + 1) % audioTracks.size
        selectedAudioIdx = next
        val chosen = audioTracks[next]
        val group = tracks.groups[chosen.groupIndex].mediaTrackGroup
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
            .buildUpon()
            .setOverrideForType(TrackSelectionOverride(group, chosen.trackIndex))
            .build()
    }

    fun cycleSubtitles() {
        val tracks = currentTracksObj ?: return
        if (subtitleTracks.isEmpty()) return
        val next = if (selectedSubIdx + 1 >= subtitleTracks.size) -1 else selectedSubIdx + 1
        selectedSubIdx = next
        val builder = exoPlayer.trackSelectionParameters.buildUpon()
        if (next == -1) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            val chosen = subtitleTracks[next]
            val group = tracks.groups[chosen.groupIndex].mediaTrackGroup
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            builder.setOverrideForType(TrackSelectionOverride(group, chosen.trackIndex))
        }
        exoPlayer.trackSelectionParameters = builder.build()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                }
            },
            update = { view ->
                view.player = exoPlayer
            },
            modifier = Modifier.fillMaxSize()
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .background(Color(0xAA000000))
                .padding(horizontal = 24.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavPill(label = "← Tillbaka", selected = false, onClick = onBack)
                Column {
                    Text(
                        text = item.title,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "$resolutionText • ${item.country} • ${item.genre}",
                        color = Color(0xFF93C5FD),
                        fontSize = 12.sp
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val currentFormatTag = detectStreamTag(activeUrl)
                if (currentFormatTag != null) {
                    NavPill(
                        label = "Format: $currentFormatTag",
                        selected = false,
                        onClick = { toggleStreamFormat() }
                    )
                }
                NavPill(
                    label = if (preferSoftware) "Avkodare: SW" else "Avkodare: Auto",
                    selected = preferSoftware,
                    onClick = { toggleDecoder() }
                )
                val audioLabel = audioTracks.getOrNull(selectedAudioIdx)?.label ?: "Standard"
                NavPill(
                    label = "Ljud: $audioLabel",
                    selected = false,
                    onClick = { cycleAudio() }
                )
                val subLabel = if (selectedSubIdx == -1) "Av" else subtitleTracks.getOrNull(selectedSubIdx)?.label ?: "Av"
                NavPill(
                    label = "Text: $subLabel",
                    selected = false,
                    onClick = { cycleSubtitles() }
                )
            }
        }
    }
}

fun getLocalIpAddress(): String {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "192.168.11.22"
        for (intf in interfaces) {
            val addrs = intf.inetAddresses
            for (addr in addrs) {
                if (!addr.isLoopbackAddress && addr is Inet4Address) {
                    val ip = addr.hostAddress
                    if (!ip.isNullOrBlank()) return ip
                }
            }
        }
    } catch (_: Exception) {
    }
    return "192.168.11.22"
}

suspend fun handleWebClient(
    socket: Socket,
    currentMode: String,
    currentFormat: String,
    currentM3u: String,
    currentServer: String,
    currentUser: String,
    currentPass: String,
    currentAdultLock: Boolean,
    currentAdultPin: String,
    onApplyConfig: suspend (String, String, String, String, String, String, Boolean, String) -> Unit
) {
    try {
        socket.soTimeout = 10000
        val input = socket.getInputStream()
        val headerBytes = ByteArrayOutputStream()
        var prev1 = 0
        var prev2 = 0
        var prev3 = 0
        while (true) {
            val b = input.read()
            if (b == -1) break
            headerBytes.write(b)
            if (prev1 == '\r'.code && prev2 == '\n'.code && prev3 == '\r'.code && b == '\n'.code) {
                break
            }
            prev1 = prev2
            prev2 = prev3
            prev3 = b
        }
        val headerText = headerBytes.toString("UTF-8")
        if (headerText.isBlank()) {
            socket.close()
            return
        }

        val firstLine = headerText.lines().firstOrNull().orEmpty()
        val isPost = firstLine.startsWith("POST", ignoreCase = true)
        val contentLength = headerText.lines()
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(":")
            ?.trim()
            ?.toIntOrNull() ?: 0

        var savedBanner = false
        var formMode = currentMode
        var formFormat = currentFormat
        var formM3u = currentM3u
        var formServer = currentServer
        var formUser = currentUser
        var formPass = currentPass
        var formAdultLock = currentAdultLock
        var formAdultPin = currentAdultPin

        if (isPost && contentLength > 0) {
            val bodyBytes = ByteArray(contentLength)
            var totalRead = 0
            while (totalRead < contentLength) {
                val read = input.read(bodyBytes, totalRead, contentLength - totalRead)
                if (read == -1) break
                totalRead += read
            }
            val bodyText = String(bodyBytes, 0, totalRead, Charsets.UTF_8)
            val params = parseFormBody(bodyText)

            formMode = params["mode"]?.ifBlank { "M3U" } ?: "M3U"
            formFormat = params["format"]?.ifBlank { "AUTO" } ?: "AUTO"
            formM3u = params["m3u"]?.trim().orEmpty()
            formServer = params["server"]?.trim().orEmpty()
            formUser = params["user"]?.trim().orEmpty()
            formPass = params["pass"]?.trim().orEmpty()
            formAdultPin = params["adult_pin"]?.trim().orEmpty()
            formAdultLock = (params["adult_lock"] == "1") && formAdultPin.isNotEmpty()

            onApplyConfig(
                formMode,
                formFormat,
                formM3u,
                formServer,
                formUser,
                formPass,
                formAdultLock,
                formAdultPin
            )
            savedBanner = true
        }

        val html = buildWebConfigHtml(
            saved = savedBanner,
            mode = formMode,
            format = formFormat,
            m3u = formM3u,
            server = formServer,
            user = formUser,
            pass = formPass,
            adultLock = formAdultLock,
            adultPin = formAdultPin
        )
        val htmlBytes = html.toByteArray(Charsets.UTF_8)
        val responseHeader = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/html; charset=UTF-8\r\n" +
            "Content-Length: ${htmlBytes.size}\r\n" +
            "Connection: close\r\n\r\n"

        val out = socket.getOutputStream()
        out.write(responseHeader.toByteArray(Charsets.UTF_8))
        out.write(htmlBytes)
        out.flush()
    } catch (_: Exception) {
    } finally {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }
}

fun parseFormBody(body: String): Map<String, String> {
    val map = mutableMapOf<String, String>()
    if (body.isBlank()) return map
    for (pair in body.split("&")) {
        val idx = pair.indexOf('=')
        if (idx > 0) {
            val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
            val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            map[key] = value
        }
    }
    return map
}

fun escapeHtml(text: String): String {
    return text
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

fun buildWebConfigHtml(
    saved: Boolean,
    mode: String,
    format: String,
    m3u: String,
    server: String,
    user: String,
    pass: String,
    adultLock: Boolean,
    adultPin: String
): String {
    val banner = if (saved) {
        """<div class="ok">Sparat! Kanalerna synkas nu direkt på din TV.</div>"""
    } else {
        ""
    }
    return """
<!DOCTYPE html>
<html lang="sv">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Ulf TV – Lägg till spellista</title>
<style>
  body { font-family: -apple-system, BlinkMacSystemFont, sans-serif; background: #0a0d14; color: #f8fafc; margin: 0; padding: 24px; }
  .box { max-width: 560px; margin: 0 auto; background: #151b28; border: 1px solid #283248; border-radius: 18px; padding: 24px; }
  h1 { margin-top: 0; font-size: 22px; }
  label { display: block; margin-top: 14px; margin-bottom: 6px; font-size: 13px; color: #94a3b8; font-weight: 600; }
  input, select { width: 100%; box-sizing: border-box; padding: 12px; border-radius: 10px; border: 1px solid #334155; background: #0f172a; color: #fff; font-size: 15px; }
  button { margin-top: 22px; width: 100%; padding: 14px; border: none; border-radius: 999px; background: #fff; color: #000; font-size: 16px; font-weight: 700; cursor: pointer; }
  .ok { background: #14532d; border: 1px solid #22c55e; color: #dcfce7; padding: 12px 16px; border-radius: 12px; margin-bottom: 16px; font-weight: 600; }
  .section { border-top: 1px solid #263044; margin-top: 18px; padding-top: 10px; }
</style>
</head>
<body>
<div class="box">
  <h1>Ulf TV – Snabbinställning</h1>
  $banner
  <form method="POST" action="/add">
    <label>Välj inloggningstyp</label>
    <select name="mode">
      <option value="M3U" ${if (mode == "M3U") "selected" else ""}>M3U-länk</option>
      <option value="XTREAM" ${if (mode == "XTREAM") "selected" else ""}>Xtream Codes (Server + Användare + Lösenord)</option>
    </select>

    <div class="section">
      <label>Alternativ 1: M3U-spellista URL</label>
      <input type="text" name="m3u" placeholder="http://..." value="${escapeHtml(m3u)}">
    </div>

    <div class="section">
      <label>Alternativ 2: Xtream Server-URL</label>
      <input type="text" name="server" placeholder="http://server.com:8080" value="${escapeHtml(server)}">
      <label>Xtream Användarnamn</label>
      <input type="text" name="user" value="${escapeHtml(user)}">
      <label>Xtream Lösenord</label>
      <input type="text" name="pass" value="${escapeHtml(pass)}">
    </div>

    <div class="section">
      <label>Strömformat (Live TV)</label>
      <select name="format">
        <option value="AUTO" ${if (format == "AUTO") "selected" else ""}>Auto (Smart växling HLS / MPEG-TS)</option>
        <option value="HLS" ${if (format == "HLS") "selected" else ""}>Alltid HLS (.m3u8)</option>
        <option value="MPEG-TS" ${if (format == "MPEG-TS") "selected" else ""}>Alltid MPEG-TS (.ts)</option>
      </select>
    </div>

    <div class="section">
      <label>Föräldrakontroll (18+ / Vuxenkanaler)</label>
      <select name="adult_lock">
        <option value="0" ${if (!adultLock) "selected" else ""}>Av (Olåst)</option>
        <option value="1" ${if (adultLock) "selected" else ""}>På (Kräv PIN-kod)</option>
      </select>
      <label>PIN-kod för 18+</label>
      <input type="text" name="adult_pin" placeholder="t.ex. 1234" value="${escapeHtml(adultPin)}">
    </div>

    <button type="submit">Spara &amp; skicka till TV</button>
  </form>
</div>
</body>
</html>
""".trimIndent()
}

fun detectStreamTag(url: String): String? {
    val lower = url.lowercase()
    return when {
        lower.endsWith(".m3u8") || lower.contains("output=m3u8") -> "HLS"
        lower.endsWith(".ts") || lower.contains("output=ts") -> "MPEG-TS"
        else -> null
    }
}

fun canSwapFormat(url: String): Boolean {
    return detectStreamTag(url) != null
}

fun swapStreamFormat(url: String): String {
    return when {
        url.endsWith(".m3u8", ignoreCase = true) -> url.dropLast(5) + ".ts"
        url.endsWith(".ts", ignoreCase = true) -> url.dropLast(3) + ".m3u8"
        url.contains("output=m3u8", ignoreCase = true) -> url.replace("output=m3u8", "output=ts", ignoreCase = true)
        url.contains("output=ts", ignoreCase = true) -> url.replace("output=ts", "output=m3u8", ignoreCase = true)
        else -> url
    }
}

fun applyPlaylistFormat(url: String, format: String): String {
    if (format == "AUTO") return url
    return if (format == "MPEG-TS") {
        url.replace("output=m3u8", "output=ts", ignoreCase = true)
    } else {
        url.replace("output=ts", "output=m3u8", ignoreCase = true)
    }
}

fun applyStreamFormat(url: String, format: String): String {
    if (format == "AUTO") return url
    val isXtreamLive = url.contains("/live/", ignoreCase = true)
    if (!isXtreamLive) return url
    return if (format == "MPEG-TS") {
        if (url.endsWith(".m3u8", ignoreCase = true)) {
            url.dropLast(5) + ".ts"
        } else {
            url
        }
    } else {
        if (url.endsWith(".ts", ignoreCase = true)) {
            url.dropLast(3) + ".m3u8"
        } else {
            url
        }
    }
}

fun detectAdultContent(vararg texts: String): Boolean {
    val keywords = listOf(
        "18+", "+18", "xxx", "adult", "vuxen", "vuxna",
        "erotic", "erotik", "porn", "nsfw", "playboy",
        "hustler", "brazzers", "dorcel", "redlight", "penthouse", "vivid"
    )
    for (raw in texts) {
        val lower = raw.lowercase()
        if (keywords.any { lower.contains(it) }) return true
        val tokens = lower.split(" ", "|", "-", ":", "/", "(", ")", "[", "]")
        if (tokens.any { it == "sex" }) return true
    }
    return false
}

fun fetchText(urlString: String): String {
    val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000
        readTimeout = 20000
        setRequestProperty("User-Agent", "UlfTV/2.0")
    }
    return conn.inputStream.bufferedReader().use { it.readText() }
}

fun fetchXtreamCatalog(server: String, user: String, pass: String, format: String): List<MediaEntry> {
    val base = server.trimEnd('/')
    val extLive = if (format == "MPEG-TS") "ts" else "m3u8"
    val result = mutableListOf<MediaEntry>()

    try {
        val liveJson = JSONArray(fetchText("$base/player_api.php?username=$user&password=$pass&action=get_live_streams"))
        for (i in 0 until liveJson.length()) {
            val obj = liveJson.getJSONObject(i)
            val id = obj.optString("stream_id")
            val name = obj.optString("name", "Kanal")
            val isAdultFlag = obj.optString("is_adult", "0") == "1" || detectAdultContent(name)
            val (country, genre) = extractCountryAndGenre(name)
            result.add(
                MediaEntry(
                    id = "live_$id",
                    title = name,
                    country = country,
                    genre = genre,
                    url = "$base/live/$user/$pass/$id.$extLive",
                    type = MediaType.LIVE,
                    isAdult = isAdultFlag
                )
            )
        }
    } catch (_: Exception) {
    }

    try {
        val vodJson = JSONArray(fetchText("$base/player_api.php?username=$user&password=$pass&action=get_vod_streams"))
        for (i in 0 until vodJson.length()) {
            val obj = vodJson.getJSONObject(i)
            val id = obj.optString("stream_id")
            val name = obj.optString("name", "Film")
            val ext = obj.optString("container_extension", "mp4").ifBlank { "mp4" }
            val isAdultFlag = obj.optString("is_adult", "0") == "1" || detectAdultContent(name)
            val (country, genre) = extractCountryAndGenre(name)
            result.add(
                MediaEntry(
                    id = "vod_$id",
                    title = name,
                    country = country,
                    genre = genre,
                    url = "$base/movie/$user/$pass/$id.$ext",
                    type = MediaType.MOVIE,
                    isAdult = isAdultFlag
                )
            )
        }
    } catch (_: Exception) {
    }

    return result
}

fun parseM3u(content: String, format: String): List<MediaEntry> {
    val result = mutableListOf<MediaEntry>()
    val lines = content.lines()
    var currentName = "Okänd"
    var currentGroup = "Övrigt"
    val groupRegex = Regex("""group-title="([^"]+)"""")

    for (rawLine in lines) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue
        if (line.startsWith("#EXTINF", ignoreCase = true)) {
            val groupMatch = groupRegex.find(line)
            currentGroup = groupMatch?.groupValues?.getOrNull(1)?.ifBlank { "Övrigt" } ?: "Övrigt"
            val commaIndex = line.lastIndexOf(',')
            currentName = if (commaIndex != -1 && commaIndex + 1 < line.length) {
                line.substring(commaIndex + 1).trim().ifBlank { "Okänd" }
            } else {
                "Okänd"
            }
        } else if (!line.startsWith("#")) {
            val lowerGroup = currentGroup.lowercase()
            val lowerUrl = line.lowercase()
            val type = when {
                lowerGroup.contains("serie") || lowerUrl.contains("/series/") -> MediaType.SERIES
                lowerGroup.contains("film") || lowerGroup.contains("movie") || lowerGroup.contains("vod") || lowerUrl.contains("/movie/") -> MediaType.MOVIE
                else -> MediaType.LIVE
            }
            val (country, genre) = extractCountryAndGenre(currentGroup)
            val isAdultFlag = detectAdultContent(currentName, currentGroup)
            val finalUrl = if (type == MediaType.LIVE) applyStreamFormat(line, format) else line
            result.add(
                MediaEntry(
                    id = "${result.size}_${currentName.hashCode()}",
                    title = currentName,
                    country = country,
                    genre = genre,
                    url = finalUrl,
                    type = type,
                    isAdult = isAdultFlag
                )
            )
            currentName = "Okänd"
            currentGroup = "Övrigt"
        }
    }
    return result
}

fun extractCountryAndGenre(raw: String): Pair<String, String> {
    val cleaned = raw.trim()
    val parts = cleaned.split("|", ":", "-", "/", limit = 2).map { it.trim() }.filter { it.isNotEmpty() }
    return if (parts.size == 2 && parts[0].length <= 14) {
        parts[0] to parts[1]
    } else {
        val upper = cleaned.uppercase()
        val country = when {
            upper.startsWith("SE") || upper.contains("SWED") || upper.contains("SVERIGE") -> "Sverige"
            upper.startsWith("NO") || upper.contains("NORW") || upper.contains("NORGE") -> "Norge"
            upper.startsWith("DK") || upper.contains("DENM") || upper.contains("DANMARK") -> "Danmark"
            upper.startsWith("UK") || upper.startsWith("GB") -> "UK"
            upper.startsWith("US") -> "USA"
            else -> "Internationellt"
        }
        country to (cleaned.ifBlank { "Allmänt" })
    }
}

fun demoCatalog(): List<MediaEntry> = listOf(
    MediaEntry("1", "SVT / Testström HD", "Sverige", "Nyheter", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", MediaType.LIVE, false),
    MediaEntry("2", "Nordic Sport 4K Demo", "Sverige", "Sport", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4", MediaType.LIVE, false),
    MediaEntry("3", "Big Buck Bunny (Demo)", "Internationellt", "Film", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4", MediaType.MOVIE, false),
    MediaEntry("4", "Sintel (Demo)", "Internationellt", "Serie", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4", MediaType.SERIES, false)
)
