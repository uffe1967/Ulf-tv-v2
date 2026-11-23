package se.ulf.tv

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

data class Channel(
    val name: String,
    val group: String,
    val url: String
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
    val prefs = remember { context.getSharedPreferences("ulf_tv_prefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var m3uUrl by remember { mutableStateOf(prefs.getString("m3u_url", "") ?: "") }
    var channels by remember { mutableStateOf(demoChannels()) }
    var selectedGroup by remember { mutableStateOf("Alla") }
    var playingChannel by remember { mutableStateOf<Channel?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Visar demokanaler. Lägg till din M3U-länk i Inställningar.") }

    fun loadPlaylist(url: String) {
        if (url.isBlank()) {
            channels = demoChannels()
            statusMessage = "Visar demokanaler."
            return
        }
        statusMessage = "Laddar spellista..."
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    URL(url.trim()).readText()
                }
                val parsed = parseM3u(text)
                if (parsed.isNotEmpty()) {
                    channels = parsed
                    selectedGroup = "Alla"
                    statusMessage = "Laddade ${parsed.size} kanaler."
                } else {
                    statusMessage = "Hittade inga kanaler i länken. Visar demokanaler."
                    channels = demoChannels()
                }
            } catch (e: Exception) {
                statusMessage = "Kunde inte ladda länken: ${e.message ?: "Okänt fel"}"
            }
        }
    }

    LaunchedEffect(Unit) {
        if (m3uUrl.isNotBlank()) {
            loadPlaylist(m3uUrl)
        }
    }

    if (playingChannel != null) {
        BackHandler { playingChannel = null }
        PlayerScreen(
            channel = playingChannel!!,
            onBack = { playingChannel = null }
        )
        return
    }

    val groups = remember(channels) {
        listOf("Alla") + channels.map { it.group }.distinct().sorted()
    }
    val filteredChannels = remember(channels, selectedGroup) {
        if (selectedGroup == "Alla") channels else channels.filter { it.group == selectedGroup }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
            .padding(28.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Ulf TV",
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = statusMessage,
                    color = Color(0xFF9CA3AF),
                    fontSize = 13.sp
                )
            }
            Button(
                onClick = { showSettings = !showSettings },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (showSettings) Color.White else Color(0xFF1F2937),
                    contentColor = if (showSettings) Color.Black else Color.White
                )
            ) {
                Text(if (showSettings) "Stäng inställningar" else "M3U-länk / Inställningar")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (showSettings) {
            SettingsPanel(
                currentUrl = m3uUrl,
                onSave = { newUrl ->
                    m3uUrl = newUrl
                    prefs.edit().putString("m3u_url", newUrl).apply()
                    showSettings = false
                    loadPlaylist(newUrl)
                }
            )
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(groups) { group ->
                    val active = group == selectedGroup
                    Button(
                        onClick = { selectedGroup = group },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (active) Color.White else Color(0xFF1F2937),
                            contentColor = if (active) Color.Black else Color.White
                        )
                    ) {
                        Text(group, maxLines = 1)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filteredChannels) { channel ->
                    Surface(
                        color = Color(0xFF161B26),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = channel.name,
                                    color = Color.White,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = channel.group,
                                    color = Color(0xFF9CA3AF),
                                    fontSize = 13.sp,
                                    maxLines = 1
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Button(
                                onClick = { playingChannel = channel },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF2563EB),
                                    contentColor = Color.White
                                )
                            ) {
                                Text("Spela")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsPanel(
    currentUrl: String,
    onSave: (String) -> Unit
) {
    var inputUrl by remember { mutableStateOf(currentUrl) }

    Surface(
        color = Color(0xFF161B26),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "Lägg in din M3U-spellista",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Klistra in din M3U-URL nedan och klicka på Spara & ladda.",
                color = Color(0xFF9CA3AF),
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(14.dp))
            OutlinedTextField(
                value = inputUrl,
                onValueChange = { inputUrl = it },
                label = { Text("M3U URL (http://...)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(14.dp))
            Button(
                onClick = { onSave(inputUrl) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2563EB),
                    contentColor = Color.White
                )
            ) {
                Text("Spara & ladda kanaler")
            }
        }
    }
}

@Composable
fun PlayerScreen(
    channel: Channel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val exoPlayer = remember(channel.url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(channel.url))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
        }
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
        Button(
            onClick = onBack,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xAA000000),
                contentColor = Color.White
            ),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        ) {
            Text("← Tillbaka (${channel.name})")
        }
    }
}

fun demoChannels(): List<Channel> = listOf(
    Channel(
        name = "SE | Testström HD",
        group = "Sverige",
        url = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
    ),
    Channel(
        name = "SE | Sport 4K Demo",
        group = "Sport",
        url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4"
    )
)

fun parseM3u(content: String): List<Channel> {
    val result = mutableListOf<Channel>()
    val lines = content.lines()
    var currentName = "Okänd kanal"
    var currentGroup = "Övrigt"

    for (rawLine in lines) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue
        if (line.startsWith("#EXTINF", ignoreCase = true)) {
            val groupMatch = Regex("group-title="([^"]+)"").find(line)
            currentGroup = groupMatch?.groupValues?.getOrNull(1)?.ifBlank { "Övrigt" } ?: "Övrigt"
            val commaIndex = line.lastIndexOf(',')
            currentName = if (commaIndex != -1 && commaIndex + 1 < line.length) {
                line.substring(commaIndex + 1).trim().ifBlank { "Okänd kanal" }
            } else {
                "Okänd kanal"
            }
        } else if (!line.startsWith("#")) {
            result.add(Channel(name = currentName, group = currentGroup, url = line))
            currentName = "Okänd kanal"
            currentGroup = "Övrigt"
        }
    }
    return result
}
