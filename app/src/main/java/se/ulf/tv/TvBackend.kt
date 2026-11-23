package se.ulf.tv

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.net.*
import kotlin.math.min

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
    val imageUrl: String = "",
    val is4K: Boolean = false,
    val orderIndex: Int = 0
)

data class TrackOption(
    val label: String,
    val groupIndex: Int,
    val trackIndex: Int
)

object CoverImageCache {
    @Volatile
    var epoch: Int = 1
    private val cache = LruCache<String, ImageBitmap>(36)

    fun get(key: String): ImageBitmap? = synchronized(cache) {
        cache.get("${epoch}_$key")
    }

    fun put(key: String, bmp: ImageBitmap) = synchronized(cache) {
        cache.put("${epoch}_$key", bmp)
    }

    fun clearAndBump() = synchronized(cache) {
        epoch += 1
        cache.evictAll()
    }
}

fun loadScaledBitmap(urlString: String, maxDim: Int): ImageBitmap? {
    if (urlString.isBlank()) return null
    val cleanUrl = urlString.trim()
    if (!cleanUrl.startsWith("http://", true) && !cleanUrl.startsWith("https://", true)) return null

    return try {
        val conn = (URL(cleanUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 3500
            readTimeout = 4500
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV) ObsidianTV/2.3")
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            return null
        }
        val bytes = conn.inputStream.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            var total = 0
            while (true) {
                val read = input.read(buf)
                if (read == -1) break
                total += read
                if (total > 1_500_000) return null
                out.write(buf, 0, read)
            }
            out.toByteArray()
        }
        conn.disconnect()
        if (bytes.isEmpty()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) {
            sample *= 2
        }

        BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            }
        )?.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}

fun getLocalIpAddress(): String {
    try {
        for (intf in NetworkInterface.getNetworkInterfaces() ?: return "192.168.11.22") {
            for (addr in intf.inetAddresses) {
                if (!addr.isLoopbackAddress && addr is Inet4Address && !addr.hostAddress.isNullOrBlank()) {
                    return addr.hostAddress!!
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
    onApply: suspend (String, String, String, String, String, String, Boolean, String) -> Unit
) {
    try {
        socket.soTimeout = 10000
        val input = socket.getInputStream()
        val hb = ByteArrayOutputStream()
        var p1 = 0
        var p2 = 0
        var p3 = 0
        while (true) {
            val b = input.read()
            if (b == -1) break
            hb.write(b)
            if (p1 == '\r'.code && p2 == '\n'.code && p3 == '\r'.code && b == '\n'.code) break
            p1 = p2
            p2 = p3
            p3 = b
        }
        val header = hb.toString("UTF-8")
        if (header.isBlank()) return
        val isPost = header.lines().firstOrNull().orEmpty().startsWith("POST", true)
        val len = header.lines()
            .firstOrNull { it.startsWith("Content-Length:", true) }
            ?.substringAfter(":")
            ?.trim()
            ?.toIntOrNull() ?: 0

        var saved = false
        var fMode = currentMode
        var fFmt = currentFormat
        var fM3u = currentM3u
        var fSrv = currentServer
        var fUsr = currentUser
        var fPwd = currentPass
        var fLock = currentAdultLock
        var fPin = currentAdultPin

        if (isPost && len in 1..65536) {
            val body = ByteArray(len)
            var got = 0
            while (got < len) {
                val r = input.read(body, got, len - got)
                if (r == -1) break
                got += r
            }
            val p = parseFormBody(String(body, 0, got, Charsets.UTF_8))
            fMode = p["mode"]?.ifBlank { "M3U" } ?: "M3U"
            fFmt = p["format"]?.ifBlank { "AUTO" } ?: "AUTO"
            fM3u = p["m3u"]?.trim().orEmpty()
            fSrv = p["server"]?.trim().orEmpty()
            fUsr = p["user"]?.trim().orEmpty()
            fPwd = p["pass"]?.trim().orEmpty()
            fPin = p["adult_pin"]?.trim().orEmpty()
            fLock = (p["adult_lock"] == "1") && fPin.isNotEmpty()
            onApply(fMode, fFmt, fM3u, fSrv, fUsr, fPwd, fLock, fPin)
            saved = true
        }

        val htmlBytes = buildWebHtml(saved, fMode, fFmt, fM3u, fSrv, fUsr, fPwd, fLock, fPin)
            .toByteArray(Charsets.UTF_8)
        val resp = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/html; charset=UTF-8\r\n" +
            "Content-Length: ${htmlBytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(resp.toByteArray(Charsets.UTF_8))
            write(htmlBytes)
            flush()
        }
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
    for (pair in body.split("&")) {
        val i = pair.indexOf('=')
        if (i > 0) {
            map[URLDecoder.decode(pair.substring(0, i), "UTF-8")] =
                URLDecoder.decode(pair.substring(i + 1), "UTF-8")
        }
    }
    return map
}

fun esc(t: String): String = t
    .replace("&", "&amp;")
    .replace("\"", "&quot;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

fun buildWebHtml(
    saved: Boolean,
    mode: String,
    fmt: String,
    m3u: String,
    srv: String,
    usr: String,
    pwd: String,
    lock: Boolean,
    pin: String
): String = """
<!DOCTYPE html><html lang="sv"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Obsidian</title><style>
body{font-family:-apple-system,sans-serif;background:#06070a;color:#f8fafc;margin:0;padding:24px}
.box{max-width:560px;margin:0 auto;background:#12141a;border:1px solid #262a34;border-radius:20px;padding:26px}
label{display:block;margin:14px 0 6px;font-size:13px;color:#9ca3af;font-weight:600}
input,select{width:100%;box-sizing:border-box;padding:12px;border-radius:10px;border:1px solid #2f3542;background:#0b0d12;color:#fff;font-size:15px}
button{margin-top:22px;width:100%;padding:14px;border:none;border-radius:999px;background:#fff;color:#000;font-size:16px;font-weight:700;cursor:pointer}
.ok{background:#14532d;border:1px solid #22c55e;color:#dcfce7;padding:12px;border-radius:12px;margin-bottom:16px;font-weight:600}
.sec{border-top:1px solid #222630;margin-top:18px;padding-top:10px}
</style></head><body><div class="box"><h2>OBSIDIAN – Snabbinställning</h2>
${if (saved) """<div class="ok">Sparat! Kanalerna synkas nu på din TV.</div>""" else ""}
<form method="POST" action="/add">
<label>Inloggningstyp</label><select name="mode">
<option value="M3U" ${if (mode == "M3U") "selected" else ""}>M3U-länk</option>
<option value="XTREAM" ${if (mode == "XTREAM") "selected" else ""}>Xtream Codes</option></select>
<div class="sec"><label>M3U-spellista URL</label><input type="text" name="m3u" placeholder="http://..." value="${esc(m3u)}"></div>
<div class="sec"><label>Xtream Server-URL</label><input type="text" name="server" placeholder="http://server.com:8080" value="${esc(srv)}">
<label>Användarnamn</label><input type="text" name="user" value="${esc(usr)}">
<label>Lösenord</label><input type="password" name="pass" value="${esc(pwd)}"></div>
<div class="sec"><label>Strömformat (Live TV)</label><select name="format">
<option value="AUTO" ${if (fmt == "AUTO") "selected" else ""}>Auto (Smart HLS / MPEG-TS)</option>
<option value="HLS" ${if (fmt == "HLS") "selected" else ""}>Alltid HLS (.m3u8)</option>
<option value="MPEG-TS" ${if (fmt == "MPEG-TS") "selected" else ""}>Alltid MPEG-TS (.ts)</option></select></div>
<div class="sec"><label>Föräldrakontroll (18+)</label><select name="adult_lock">
<option value="0" ${if (!lock) "selected" else ""}>Av (Olåst)</option>
<option value="1" ${if (lock) "selected" else ""}>På (Kräv PIN-kod)</option></select>
<label>PIN-kod för 18+</label><input type="password" name="adult_pin" placeholder="t.ex. 1234" value="${esc(pin)}"></div>
<button type="submit">Spara &amp; skicka till TV</button></form></div></body></html>
""".trimIndent()

fun detectStreamTag(url: String): String? = when {
    url.endsWith(".m3u8", true) || url.contains("output=m3u8", true) -> "HLS"
    url.endsWith(".ts", true) || url.contains("output=ts", true) -> "MPEG-TS"
    else -> null
}

fun canSwapFormat(url: String): Boolean = detectStreamTag(url) != null

fun swapStreamFormat(url: String): String = when {
    url.endsWith(".m3u8", true) -> url.dropLast(5) + ".ts"
    url.endsWith(".ts", true) -> url.dropLast(3) + ".m3u8"
    url.contains("output=m3u8", true) -> url.replace("output=m3u8", "output=ts", true)
    url.contains("output=ts", true) -> url.replace("output=ts", "output=m3u8", true)
    else -> url
}

fun applyPlaylistFormat(url: String, fmt: String): String = when (fmt) {
    "MPEG-TS" -> url.replace("output=m3u8", "output=ts", true)
    "HLS" -> url.replace("output=ts", "output=m3u8", true)
    else -> url
}

fun applyStreamFormat(url: String, fmt: String): String {
    if (fmt == "AUTO" || !url.contains("/live/", true)) return url
    return if (fmt == "MPEG-TS") {
        if (url.endsWith(".m3u8", true)) url.dropLast(5) + ".ts" else url
    } else {
        if (url.endsWith(".ts", true)) url.dropLast(3) + ".m3u8" else url
    }
}

fun detectAdultContent(vararg texts: String): Boolean {
    val keys = listOf(
        "18+", "+18", "xxx", "adult", "vuxen", "vuxna",
        "erotic", "erotik", "porn", "nsfw", "playboy",
        "hustler", "brazzers", "dorcel", "redlight", "penthouse", "vivid"
    )
    for (raw in texts) {
        val low = raw.lowercase()
        if (keys.any { low.contains(it) }) return true
        if (low.split(" ", "|", "-", ":", "/", "(", ")", "[", "]").any { it == "sex" }) return true
    }
    return false
}

fun is4KTitle(vararg texts: String): Boolean {
    val tags = listOf("4k", "uhd", "2160p", "4kuhd")
    for (raw in texts) {
        val low = raw.lowercase()
        if (tags.any { low.contains(it) }) return true
    }
    return false
}

fun fetchText(urlString: String): String {
    val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8000
        readTimeout = 10000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV) ObsidianTV/2.3")
    }
    return try {
        conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            val sb = StringBuilder()
            val buf = CharArray(4096)
            var total = 0
            val maxChars = 2_000_000
            while (true) {
                val r = reader.read(buf)
                if (r == -1) break
                total += r
                if (total > maxChars) break
                sb.append(buf, 0, r)
            }
            sb.toString()
        }
    } finally {
        try {
            conn.disconnect()
        } catch (_: Exception) {
        }
    }
}

fun fetchXtreamCatalog(server: String, user: String, pass: String, format: String): List<MediaEntry> {
    val base = server.trim().trimEnd('/')
    val extLive = if (format == "MPEG-TS") "ts" else "m3u8"
    val result = mutableListOf<MediaEntry>()
    val maxPerType = 150

    try {
        val rawLive = fetchText("$base/player_api.php?username=$user&password=$pass&action=get_live_streams")
        if (rawLive.trimStart().startsWith("[")) {
            val liveJson = JSONArray(rawLive)
            val count = min(liveJson.length(), maxPerType)
            for (i in 0 until count) {
                val o = liveJson.optJSONObject(i) ?: continue
                val id = o.optString("stream_id")
                val name = o.optString("name", "Kanal")
                val (c, g) = extractCountryAndSubcategory(name, name)
                result.add(
                    MediaEntry(
                        id = "live_${i}_$id",
                        title = name,
                        country = c,
                        genre = g,
                        url = "$base/live/$user/$pass/$id.$extLive",
                        type = MediaType.LIVE,
                        isAdult = o.optString("is_adult", "0") == "1" || detectAdultContent(name),
                        imageUrl = o.optString("stream_icon", "").trim(),
                        is4K = is4KTitle(name, g),
                        orderIndex = i
                    )
                )
            }
        }
    } catch (_: Exception) {
    }

    try {
        val rawVod = fetchText("$base/player_api.php?username=$user&password=$pass&action=get_vod_streams")
        if (rawVod.trimStart().startsWith("[")) {
            val vodJson = JSONArray(rawVod)
            val count = min(vodJson.length(), maxPerType)
            for (i in 0 until count) {
                val o = vodJson.optJSONObject(i) ?: continue
                val id = o.optString("stream_id")
                val name = o.optString("name", "Film")
                val ext = o.optString("container_extension", "mp4").ifBlank { "mp4" }
                val cover = o.optString("stream_icon", "").ifBlank { o.optString("cover", "") }.trim()
                val (c, g) = extractCountryAndSubcategory(name, name)
                result.add(
                    MediaEntry(
                        id = "vod_${i}_$id",
                        title = name,
                        country = c,
                        genre = g,
                        url = "$base/movie/$user/$pass/$id.$ext",
                        type = MediaType.MOVIE,
                        isAdult = o.optString("is_adult", "0") == "1" || detectAdultContent(name),
                        imageUrl = cover,
                        is4K = is4KTitle(name, g),
                        orderIndex = i
                    )
                )
            }
        }
    } catch (_: Exception) {
    }

    try {
        val rawSer = fetchText("$base/player_api.php?username=$user&password=$pass&action=get_series")
        if (rawSer.trimStart().startsWith("[")) {
            val serJson = JSONArray(rawSer)
            val count = min(serJson.length(), maxPerType)
            for (i in 0 until count) {
                val o = serJson.optJSONObject(i) ?: continue
                val id = o.optString("series_id")
                val name = o.optString("name", "Serie")
                val cover = o.optString("cover", "").ifBlank { o.optString("stream_icon", "") }.trim()
                val (c, g) = extractCountryAndSubcategory(name, name)
                result.add(
                    MediaEntry(
                        id = "ser_${i}_$id",
                        title = name,
                        country = c,
                        genre = g,
                        url = "$base/series/$user/$pass/$id.mp4",
                        type = MediaType.SERIES,
                        isAdult = o.optString("is_adult", "0") == "1" || detectAdultContent(name),
                        imageUrl = cover,
                        is4K = is4KTitle(name, g),
                        orderIndex = i
                    )
                )
            }
        }
    } catch (_: Exception) {
    }

    return result
}

fun parseM3u(content: String, format: String): List<MediaEntry> {
    val result = mutableListOf<MediaEntry>()
    var name = "Okänd"
    var group = "Övrigt"
    var logo = ""
    val gReg = Regex("""group-title="([^"]+)"""")
    val lReg = Regex("""tvg-logo="([^"]+)"""")

    for (raw in content.lineSequence()) {
        if (result.size >= 450) break
        val line = raw.trim()
        if (line.isEmpty()) continue
        if (line.startsWith("#EXTINF", true)) {
            group = gReg.find(line)?.groupValues?.getOrNull(1)?.ifBlank { "Övrigt" } ?: "Övrigt"
            logo = lReg.find(line)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            val comma = line.lastIndexOf(',')
            name = if (comma != -1 && comma + 1 < line.length) {
                line.substring(comma + 1).trim().ifBlank { "Okänd" }
            } else {
                "Okänd"
            }
        } else if (!line.startsWith("#")) {
            val lg = group.lowercase()
            val lu = line.lowercase()
            val type = when {
                lg.contains("serie") || lu.contains("/series/") -> MediaType.SERIES
                lg.contains("film") || lg.contains("movie") || lg.contains("vod") || lu.contains("/movie/") -> MediaType.MOVIE
                else -> MediaType.LIVE
            }
            val (c, g) = extractCountryAndSubcategory(group, name)
            val idx = result.size
            result.add(
                MediaEntry(
                    id = "${idx}_${name.hashCode()}",
                    title = name,
                    country = c,
                    genre = g,
                    url = if (type == MediaType.LIVE) applyStreamFormat(line, format) else line,
                    type = type,
                    isAdult = detectAdultContent(name, group),
                    imageUrl = logo,
                    is4K = is4KTitle(name, group, g),
                    orderIndex = idx
                )
            )
            name = "Okänd"
            group = "Övrigt"
            logo = ""
        }
    }
    return result
}

fun extractCountryAndSubcategory(rawGroup: String, itemTitle: String): Pair<String, String> {
    val cleaned = rawGroup.trim()
    val u = cleaned.uppercase()
    val titleUpper = itemTitle.uppercase()

    val country = when {
        u.startsWith("SE") || u.contains("SWED") || u.contains("SVERIGE") || titleUpper.startsWith("SE ") -> "Sverige"
        u.startsWith("NO") || u.contains("NORW") || u.contains("NORGE") || titleUpper.startsWith("NO ") -> "Norge"
        u.startsWith("DK") || u.contains("DENM") || u.contains("DANMARK") || titleUpper.startsWith("DK ") -> "Danmark"
        u.startsWith("FI") || u.contains("FINL") || titleUpper.startsWith("FI ") -> "Finland"
        u.startsWith("UK") || u.startsWith("GB") || titleUpper.startsWith("UK ") -> "UK"
        u.startsWith("US") || u.contains("USA") || titleUpper.startsWith("US ") -> "USA"
        u.startsWith("DE") || u.contains("GERM") || u.contains("TYSK") || titleUpper.startsWith("DE ") -> "Tyskland"
        else -> {
            val parts = cleaned.split("|", ":", "-", "/", limit = 2).map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size == 2 && parts[0].length in 2..12) parts[0] else "Internationellt"
        }
    }

    val combined = "$cleaned $itemTitle".lowercase()
    val standardCategory = when {
        combined.contains("sport") || combined.contains("viaplay") || combined.contains("eurosport") || combined.contains("fotboll") || combined.contains("hockey") -> "Sport"
        combined.contains("news") || combined.contains("nyheter") || combined.contains("cnn") || combined.contains("bbc") || combined.contains("svt") -> "Nyheter"
        combined.contains("music") || combined.contains("musik") || combined.contains("mtv") || combined.contains("radio") -> "Musik"
        combined.contains("film") || combined.contains("movie") || combined.contains("cinema") || combined.contains("hbo") -> "Film"
        combined.contains("kids") || combined.contains("barn") || combined.contains("disney") || combined.contains("cartoon") -> "Barn"
        combined.contains("doc") || combined.contains("dokumentär") || combined.contains("natgeo") || combined.contains("discovery") -> "Dokumentär"
        combined.contains("serie") || combined.contains("drama") -> "Serier"
        else -> null
    }

    val subcategory = if (standardCategory != null) {
        standardCategory
    } else {
        val parts = cleaned.split("|", ":", "-", "/", limit = 2).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size == 2 && parts[0].length in 2..12) parts[1] else cleaned.ifBlank { "Allmänt" }
    }

    return country to subcategory
}

fun demoCatalog(): List<MediaEntry> = listOf(
    MediaEntry("1", "Obsidian Originals: Sintel 4K", "Internationellt", "4K • Äventyr", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4", MediaType.MOVIE, false, "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/images/Sintel.jpg", is4K = true, orderIndex = 0),
    MediaEntry("2", "Big Buck Bunny", "Internationellt", "Äventyr", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4", MediaType.MOVIE, false, "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/images/BigBuckBunny.jpg", is4K = false, orderIndex = 1),
    MediaEntry("3", "Tears of Steel 4K", "Internationellt", "Sci-Fi", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4", MediaType.MOVIE, false, "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/images/TearsOfSteel.jpg", is4K = true, orderIndex = 2),
    MediaEntry("4", "Elephants Dream", "Sverige", "Drama", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4", MediaType.SERIES, false, "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/images/ElephantsDream.jpg", is4K = false, orderIndex = 3),
    MediaEntry("5", "Nordic Chronicles", "Sverige", "Thriller", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4", MediaType.SERIES, false, "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/images/ForBiggerBlazes.jpg", is4K = true, orderIndex = 4),
    MediaEntry("6", "Nyheter Live HD", "Sverige", "Nyheter", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", MediaType.LIVE, false, "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/images/ForBiggerEscapes.jpg", is4K = false, orderIndex = 5),
    MediaEntry("7", "Arena Sport 4K", "Sverige", "Sport", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerFun.mp4", MediaType.LIVE, false, "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/images/ForBiggerFun.jpg", is4K = true, orderIndex = 6),
    MediaEntry("8", "Night Session Musik", "UK", "Musik", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerJoy.mp4", MediaType.LIVE, false, "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/images/ForBiggerJoy.jpg", is4K = false, orderIndex = 7)
)
// SLUT PÅ TVBACKEND
