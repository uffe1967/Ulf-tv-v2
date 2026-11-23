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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

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
    val type: MediaType
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

    var mode by remember { mutableStateOf(prefs.getString("mode", "M3U") ?: "M3U") }
    var m3uUrl by remember { mutableStateOf(prefs.getString("m3u_url", "") ?: "") }
    var xtServer by remember { mutableStateOf(prefs.getString("xt_server", "") ?: "") }
    var xtUser by remember { mutableStateOf(prefs.getString("xt_user", "") ?: "") }
    var xtPass by remember { mutableStateOf(prefs.getString("xt_pass", "") ?: "") }

    var catalog by remember { mutableStateOf(demoCatalog()) }
    var currentSection by remember { mutableStateOf(NavSection.HOME) }
    var playingItem by remember { mutableStateOf<MediaEntry?>(null) }
    var statusText by remember { mutableStateOf("Demoläge • Lägg till M3U eller Xtream under Inställningar") }

    fun syncCatalog(
        useMode: String,
        url: String,
        server: String,
        user: String,
        pass: String
    ) {
        scope.launch {
            statusText = "Synkar kanaler, filmer och serier..."
            try {
                val loaded = withContext(Dispatchers.IO) {
                    if (useMode == "XTREAM" && server.isNotBlank() && user.isNotBlank()) {
                        fetchXtreamCatalog(server, user, pass)
                    } else if (url.isNotBlank()) {
                        parseM3u(fetchText(url.trim()))
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
                    statusText = "Inga strömmar hittades – visar demokatalog"
                }
            } catch (e: Exception) {
                statusText = "Kunde inte ladda: ${e.message ?: "Kontrollera adressen"}"
            }
        }
    }

    LaunchedEffect(Unit) {
        if ((mode == "M3U" && m3uUrl.isNotBlank()) || (mode == "XTREAM" && xtServer.isNotBlank())) {
            syncCatalog(mode, m3uUrl, xtServer, xtUser, xtPass)
        }
    }

    if (playingItem != null) {
        BackHandler { playingItem = null }
        PlayerScreen(
            item = playingItem!!,
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
            // Apple TV-inspirerad toppmeny
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
                    Spacer(modifier = Modifier.width(14.dp))
                    NavSection.entries.forEach { section ->
                        NavPill(
                            label = section.label,
                            selected = currentSection == section,
                            onClick = { currentSection = section }
                        )
                    }
                }
                Text(
                    text = statusText,
                    color = Color(0xFF9CA3AF),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(22.dp))

            when (currentSection) {
                NavSection.HOME -> HomeView(
                    catalog = catalog,
                    onPlay = { playingItem = it }
                )
                NavSection.LIVE -> CatalogView(
                    items = catalog.filter { it.type == MediaType.LIVE },
                    emptyLabel = "Inga Live TV-kanaler hittades.",
                    onPlay = { playingItem = it }
                )
                NavSection.MOVIES -> CatalogView(
                    items = catalog.filter { it.type == MediaType.MOVIE },
                    emptyLabel = "Inga filmer hittades i spellistan.",
                    onPlay = { playingItem = it }
                )
                NavSection.SERIES -> CatalogView(
                    items = catalog.filter { it.type == MediaType.SERIES },
                    emptyLabel = "Inga serier hittades i spellistan.",
                    onPlay = { playingItem = it }
                )
                NavSection.SETTINGS -> SettingsView(
                    initialMode = mode,
                    initialM3u = m3uUrl,
                    initialServer = xtServer,
                    initialUser = xtUser,
                    initialPass = xtPass,
                    onSave = { newMode, newM3u, newServer, newUser, newPass ->
                        mode = newMode
                        m3uUrl = newM3u
                        xtServer = newServer
                        xtUser = newUser
                        xtPass = newPass
                        prefs.edit()
                            .putString("mode", newMode)
                            .putString("m3u_url", newM3u)
                            .putString("xt_server", newServer)
                            .putString("xt_user", newUser)
                            .putString("xt_pass", newPass)
                            .apply()
                        currentSection = NavSection.HOME
                        syncCatalog(newMode, newM3u, newServer, newUser, newPass)
                    }
                )
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
    onPlay: (MediaEntry) -> Unit
) {
    val featured = catalog.firstOrNull()
    val live = remember(catalog) { catalog.filter { it.type == MediaType.LIVE }.take(20) }
    val movies = remember(catalog) { catalog.filter { it.type == MediaType.MOVIE }.take(20) }
    val series = remember(catalog) { catalog.filter { it.type == MediaType.SERIES }.take(20) }

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
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
            MediaRow(title = "Live TV", items = live, onPlay = onPlay)
        }
        if (movies.isNotEmpty()) {
            MediaRow(title = "Filmer", items = movies, onPlay = onPlay)
        }
        if (series.isNotEmpty()) {
            MediaRow(title = "Serier", items = series, onPlay = onPlay)
        }
    }
}

@Composable
fun MediaRow(
    title: String,
    items: List<MediaEntry>,
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
                text = "${item.country} • ${item.genre}",
                color = if (focused) Color(0xFF93C5FD) else Color(0xFF9CA3AF),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.title,
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
    initialMode: String,
    initialM3u: String,
    initialServer: String,
    initialUser: String,
    initialPass: String,
    onSave: (String, String, String, String, String) -> Unit
) {
    var selectedMode by remember { mutableStateOf(initialMode) }
    var m3u by remember { mutableStateOf(initialM3u) }
    var server by remember { mutableStateOf(initialServer) }
    var user by remember { mutableStateOf(initialUser) }
    var pass by remember { mutableStateOf(initialPass) }

    Surface(
        color = Color(0xFF151B28),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color(0x22FFFFFF)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "IPTV-källa",
                color = Color.White,
                fontSize = 22.sp,
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

            Button(
                onClick = { onSave(selectedMode, m3u.trim(), server.trim(), user.trim(), pass.trim()) },
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

@Composable
fun PlayerScreen(
    item: MediaEntry,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var resolutionText by remember { mutableStateOf("Laddar ström...") }
    var audioTracks by remember { mutableStateOf<List<TrackOption>>(emptyList()) }
    var subtitleTracks by remember { mutableStateOf<List<TrackOption>>(emptyList()) }
    var selectedAudioIdx by remember { mutableStateOf(0) }
    var selectedSubIdx by remember { mutableStateOf(-1) }
    var currentTracksObj by remember { mutableStateOf<Tracks?>(null) }

    val exoPlayer = remember(item.url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(item.url))
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
                    resolutionText = "${videoSize.width}×${videoSize.height} ($quality)"
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
            modifier = Modifier.fillMaxSize()
        )

        // Övre informationsrad med upplösning, ljud och undertexter
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .background(Color(0x99000000))
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
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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

fun fetchText(urlString: String): String {
    val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000
        readTimeout = 20000
        setRequestProperty("User-Agent", "UlfTV/2.0")
    }
    return conn.inputStream.bufferedReader().use { it.readText() }
}

fun fetchXtreamCatalog(server: String, user: String, pass: String): List<MediaEntry> {
    val base = server.trimEnd('/')
    val result = mutableListOf<MediaEntry>()

    // Live TV
    try {
        val liveJson = JSONArray(fetchText("$base/player_api.php?username=$user&password=$pass&action=get_live_streams"))
        for (i in 0 until liveJson.length()) {
            val obj = liveJson.getJSONObject(i)
            val id = obj.optString("stream_id")
            val name = obj.optString("name", "Kanal")
            val (country, genre) = extractCountryAndGenre(name)
            result.add(
                MediaEntry(
                    id = "live_$id",
                    title = name,
                    country = country,
                    genre = genre,
                    url = "$base/live/$user/$pass/$id.m3u8",
                    type = MediaType.LIVE
                )
            )
        }
    } catch (_: Exception) { }

    // Filmer (VOD)
    try {
        val vodJson = JSONArray(fetchText("$base/player_api.php?username=$user&password=$pass&action=get_vod_streams"))
        for (i in 0 until vodJson.length()) {
            val obj = vodJson.getJSONObject(i)
            val id = obj.optString("stream_id")
            val name = obj.optString("name", "Film")
            val ext = obj.optString("container_extension", "mp4").ifBlank { "mp4" }
            val (country, genre) = extractCountryAndGenre(name)
            result.add(
                MediaEntry(
                    id = "vod_$id",
                    title = name,
                    country = country,
                    genre = genre,
                    url = "$base/movie/$user/$pass/$id.$ext",
                    type = MediaType.MOVIE
                )
            )
        }
    } catch (_: Exception) { }

    return result
}

fun parseM3u(content: String): List<MediaEntry> {
    val result = mutableListOf<MediaEntry>()
    val lines = content.lines()
    var currentName = "Okänd"
    var currentGroup = "Övrigt"

    for (rawLine in lines) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue
        if (line.startsWith("#EXTINF", ignoreCase = true)) {
            val groupMatch = Regex("group-title=\"([^\"]+)\"").find(line)
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
            result.add(
                MediaEntry(
                    id = "${result.size}_${currentName.hashCode()}",
                    title = currentName,
                    country = country,
                    genre = genre,
                    url = line,
                    type = type
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
    MediaEntry("1", "SVT / Testström HD", "Sverige", "Nyheter", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", MediaType.LIVE),
    MediaEntry("2", "Nordic Sport 4K Demo", "Sverige", "Sport", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4", MediaType.LIVE),
    MediaEntry("3", "Big Buck Bunny (Demo)", "Internationellt", "Film", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4", MediaType.MOVIE),
    MediaEntry("4", "Sintel (Demo)", "Internationellt", "Serie", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4", MediaType.SERIES)
)
