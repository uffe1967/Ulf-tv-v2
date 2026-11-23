package se.ulf.tv

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.LruCache
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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
import kotlin.math.abs

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
    val isAdult: Boolean = false,
    val imageUrl: String = ""
)

data class TrackOption(
    val label: String,
    val groupIndex: Int,
    val trackIndex: Int
)

object CoverImageCache {
    private val cache = LruCache<String, ImageBitmap>(80)

    fun get(key: String): ImageBitmap? = synchronized(cache) { cache.get(key) }

    fun put(key: String, bitmap: ImageBitmap) {
        synchronized(cache) { cache.put(key, bitmap) }
    }
}

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

    var catalog by remember { mutableStateOf(demoCatalog()) }
    var currentSection by remember { mutableStateOf(NavSection.HOME) }
    var playingItem by remember { mutableStateOf<MediaEntry?>(null) }
    var statusText by remember { mutableStateOf("http://$localIp:8099/add") }

    val isAdultRestricted = adultLockEnabled && adultPin.isNotBlank() && !adultUnlocked
    val visibleCatalog = remember(catalog, isAdultRestricted) {
        if (isAdultRestricted) catalog.filterNot { it.isAdult } else catalog
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
            statusText = "Synkar ($useFormat)..."
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
                    statusText = "$liveCount Live • $movieCount Filmer • $seriesCount Serier"
                } else {
                    catalog = demoCatalog()
                    statusText = "Demoläge • http://$localIp:8099/add"
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
                    colors = listOf(Color(0xFF05070C), Color(0xFF0B101B), Color(0xFF040609))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 40.dp, vertical = 20.dp)
        ) {
            // Apple TV-inspirerad svävande toppmeny (helt ren från 18+-knappar)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ULF TV",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.5.sp
                )

                Surface(
                    color = Color(0x99161C29),
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, Color(0x28FFFFFF))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        NavSection.entries.forEach { section ->
                            NavPill(
                                label = section.label,
                                selected = currentSection == section,
                                onClick = { currentSection = section }
                            )
                        }
                    }
                }

                Text(
                    text = statusText,
                    color = Color(0xFF8E99AD),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            when (currentSection) {
                NavSection.HOME -> HomeView(
                    catalog = visibleCatalog,
                    onPlay = { playingItem = it }
                )
                NavSection.LIVE -> CatalogView(
                    items = visibleCatalog.filter { it.type == MediaType.LIVE },
                    mediaType = MediaType.LIVE,
                    emptyLabel = "Inga Live TV-kanaler hittades.",
                    onPlay = { playingItem = it }
                )
                NavSection.MOVIES -> CatalogView(
                    items = visibleCatalog.filter { it.type == MediaType.MOVIE },
                    mediaType = MediaType.MOVIE,
                    emptyLabel = "Inga filmer hittades i spellistan.",
                    onPlay = { playingItem = it }
                )
                NavSection.SERIES -> CatalogView(
                    items = visibleCatalog.filter { it.type == MediaType.SERIES },
                    mediaType = MediaType.SERIES,
                    emptyLabel = "Inga serier hittades i spellistan.",
                    onPlay = { playingItem = it }
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
                    adultUnlocked = adultUnlocked,
                    onRequestUnlockAdult = { showUnlockDialog = true },
                    onLockAdultNow = { adultUnlocked = false },
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
                        if (newAdultLock && !adultUnlocked) {
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
            BackHandler { showUnlockDialog = false }
            PinUnlockOverlay(
                expectedPin = adultPin,
                onUnlocked = {
                    adultUnlocked = true
                    showUnlockDialog = false
                },
                onDismiss = { showUnlockDialog = false }
            )
        }
    }
}

@Composable
fun AsyncCoverImage(
    url: String,
    title: String,
    contentScale: ContentScale,
    maxDimension: Int,
    modifier: Modifier = Modifier,
    fallbackBackdrop: Boolean = true
) {
    val cacheKey = "${url}_$maxDimension"
    var bitmap by remember(cacheKey) { mutableStateOf(CoverImageCache.get(cacheKey)) }

    LaunchedEffect(cacheKey) {
        if (bitmap == null && url.isNotBlank() && (url.startsWith("http://", true) || url.startsWith("https://", true))) {
            val loaded = withContext(Dispatchers.IO) {
                loadScaledBitmap(url, maxDimension)
            }
            if (loaded != null) {
                CoverImageCache.put(cacheKey, loaded)
                bitmap = loaded
            }
        }
    }

    val fallbackBrush = remember(title) {
        val palettes = listOf(
            listOf(Color(0xFF1A2540), Color(0xFF0D1322)),
            listOf(Color(0xFF2B1C34), Color(0xFF120C18)),
            listOf(Color(0xFF142F2E), Color(0xFF0A1718)),
            listOf(Color(0xFF33201E), Color(0xFF160E0D)),
            listOf(Color(0xFF1E293B), Color(0xFF0F172A))
        )
        val idx = abs(title.hashCode()) % palettes.size
        Brush.linearGradient(palettes[idx])
    }

    Box(
        modifier = modifier.then(
            if (fallbackBackdrop) Modifier.background(fallbackBrush) else Modifier
        ),
        contentAlignment = Alignment.Center
    ) {
        val currentBitmap = bitmap
        if (currentBitmap != null) {
            Image(
                bitmap = currentBitmap,
                contentDescription = title,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize()
            )
        } else if (fallbackBackdrop) {
            val initials = title
                .split(" ", "-", "|")
                .filter { it.isNotBlank() }
                .take(2)
                .joinToString("") { it.take(1).uppercase() }
                .ifBlank { "TV" }
            Text(
                text = initials,
                color = Color(0x26FFFFFF),
                fontSize = 44.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp
            )
        }
    }
}

fun loadScaledBitmap(urlString: String, maxDim: Int): ImageBitmap? {
    return try {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 7000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "UlfTV/2.0")
        }
        if (conn.responseCode !in 200..299) {
            conn.disconnect()
            return null
        }
        val bytes = conn.inputStream.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            val maxBytes = 5 * 1024 * 1024
            while (true) {
                val read = input.read(buf)
                if (read == -1) break
                total += read
                if (total > maxBytes) return null
                out.write(buf, 0, read)
            }
            out.toByteArray()
        }
        conn.disconnect()
        if (bytes.isEmpty()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxDim || bounds.outHeight / sampleSize > maxDim) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)?.asImageBitmap()
    } catch (_: Exception) {
        null
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
            .background(Color(0xD9000000))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = Color(0xFF141B29),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.5.dp, Color(0x44FFFFFF)),
            modifier = Modifier.width(420.dp)
        ) {
            Column(
                modifier = Modifier.padding(26.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Lås upp 18+ / Vuxenkanaler",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Ange din PIN-kod för att visa vuxeninnehåll och ändra föräldrakontrollen.",
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
    val scale by animateFloatAsState(targetValue = if (focused) 1.05f else 1f, label = "pillScale")
    val bg = when {
        focused -> Color.White
        selected -> Color(0x38FFFFFF)
        else -> Color.Transparent
    }
    val textColor = if (focused) Color.Black else Color.White

    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
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
    onPlay: (MediaEntry) -> Unit
) {
    val movies = remember(catalog) { catalog.filter { it.type == MediaType.MOVIE }.take(24) }
    val series = remember(catalog) { catalog.filter { it.type == MediaType.SERIES }.take(24) }
    val live = remember(catalog) { catalog.filter { it.type == MediaType.LIVE }.take(24) }

    val defaultFeatured = remember(catalog) {
        catalog.firstOrNull { it.imageUrl.isNotBlank() && it.type != MediaType.LIVE }
            ?: catalog.firstOrNull { it.imageUrl.isNotBlank() }
            ?: catalog.firstOrNull()
    }
    var heroItem by remember(defaultFeatured) { mutableStateOf(defaultFeatured) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(26.dp)
    ) {
        if (heroItem != null) {
            AppleTvHeroBanner(
                item = heroItem!!,
                onPlay = { onPlay(heroItem!!) }
            )
        }

        if (movies.isNotEmpty()) {
            MediaRow(
                title = "Filmer",
                items = movies,
                isPosterStyle = true,
                onFocusItem = { heroItem = it },
                onPlay = onPlay
            )
        }
        if (series.isNotEmpty()) {
            MediaRow(
                title = "Serier",
                items = series,
                isPosterStyle = true,
                onFocusItem = { heroItem = it },
                onPlay = onPlay
            )
        }
        if (live.isNotEmpty()) {
            MediaRow(
                title = "Live TV",
                items = live,
                isPosterStyle = false,
                onFocusItem = { heroItem = it },
                onPlay = onPlay
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun AppleTvHeroBanner(
    item: MediaEntry,
    onPlay: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (focused) 1.015f else 1f, label = "heroScale")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(290.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(elevation = if (focused) 24.dp else 10.dp, shape = RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .border(
                width = if (focused) 2.5.dp else 1.dp,
                color = if (focused) Color.White else Color(0x28FFFFFF),
                shape = RoundedCornerShape(24.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable { onPlay() }
    ) {
        AsyncCoverImage(
            url = item.imageUrl,
            title = item.title,
            contentScale = ContentScale.Crop,
            maxDimension = 960,
            modifier = Modifier.fillMaxSize()
        )

        // Biotonad mörk vinjett för hög kontrast (Apple TV+-stil)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(Color(0xE605070C), Color(0x9905070C), Color(0x2205070C))
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color(0x5505070C), Color(0xE605070C))
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 32.dp, vertical = 26.dp)
                .fillMaxWidth(0.72f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                color = Color(0x38FFFFFF),
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, Color(0x40FFFFFF))
            ) {
                Text(
                    text = "${item.type.label.uppercase()}  •  ${item.country}  •${item.genre}",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                )
            }

            Text(
                text = item.title,
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(50)
                ) {
