package kr.juhyu.licensenote

import android.content.Context
import android.graphics.BitmapFactory
import android.net.http.HttpResponseCache
import android.util.Log
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import kotlin.math.*

private data class MapTile(val zoom: Int, val x: Int, val y: Int)
private data class MapViewport(val size: IntSize, val zoom: Int, val tilePixels: Double, val left: Double, val top: Double, val tiles: List<MapTile>) {
    fun screen(point: GeoPoint): Offset {
        val (x, y) = mercator(point, zoom)
        return Offset((x * tilePixels - left).toFloat(), (y * tilePixels - top).toFloat())
    }
}
private data class MapLoad(val images: Map<MapTile, ImageBitmap> = emptyMap(), val completed: Int = 0, val failed: Int = 0, val finished: Boolean = false)

@OptIn(ExperimentalTextApi::class)
private val MapAsta = FontFamily(
    Font(R.font.asta_sans, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.asta_sans, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
)

private fun mercator(point: GeoPoint, zoom: Int): Pair<Double, Double> {
    val n = (1 shl zoom).toDouble()
    val sine = sin(Math.toRadians(point.lat.coerceIn(-85.05112878, 85.05112878)))
    return (point.lon + 180.0) / 360.0 * n to (0.5 - ln((1 + sine) / (1 - sine)) / (4 * PI)) * n
}

private fun viewport(size: IntSize, route: PracticeRoute): MapViewport? {
    if (size.width <= 0 || size.height <= 0 || route.points.isEmpty()) return null
    // Scale the native 256px tiles to keep the current viewport within 4 columns × 3 rows.
    val tilePixels = maxOf(256.0, size.width / 3.0, size.height / 2.0)
    val zoom = (19 downTo 1).firstOrNull { z ->
        val points = route.points.map { mercator(it, z) }
        (points.maxOf { it.first } - points.minOf { it.first }) * tilePixels <= (size.width - 80).coerceAtLeast(1) &&
            (points.maxOf { it.second } - points.minOf { it.second }) * tilePixels <= (size.height - 80).coerceAtLeast(1)
    } ?: 1
    val points = route.points.map { mercator(it, zoom) }
    val left = (points.maxOf { it.first } + points.minOf { it.first }) * tilePixels / 2 - size.width / 2.0
    val top = (points.maxOf { it.second } + points.minOf { it.second }) * tilePixels / 2 - size.height / 2.0
    val max = (1 shl zoom) - 1
    val xs = floor(left / tilePixels).toInt().coerceIn(0, max)..floor((left + size.width - 1) / tilePixels).toInt().coerceIn(0, max)
    val ys = floor(top / tilePixels).toInt().coerceIn(0, max)..floor((top + size.height - 1) / tilePixels).toInt().coerceIn(0, max)
    val tiles = ys.flatMap { y -> xs.map { x -> MapTile(zoom, x, y) } }
    check(tiles.size <= 12)
    return MapViewport(size, zoom, tilePixels, left, top, tiles)
}

private object CourseTiles {
    // ponytail: fixed course, at most 12 visible tiles; an interactive map needs a provider SDK.
    private const val tileOrigin = "https://tile.openstreetmap.org"
    private data class Cached(val bitmap: ImageBitmap, val expiresAt: Long)
    private val images = LruCache<MapTile, Cached>(24)
    private val lock = Mutex()

    suspend fun image(context: Context, tile: MapTile): ImageBitmap = withContext(Dispatchers.IO) {
        lock.withLock {
            coroutineContext.ensureActive()
            val now = System.currentTimeMillis()
            images.get(tile)?.takeIf { it.expiresAt > now }?.let { return@withLock it.bitmap }
            // HttpURLConnection honours freshness, validators and conditional requests via this cache.
            val cache = HttpResponseCache.getInstalled() ?: HttpResponseCache.install(File(context.cacheDir, "map-http"), 10L * 1024 * 1024)
            val connection = URL("$tileOrigin/${tile.zoom}/${tile.x}/${tile.y}.png").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 4000
                connection.readTimeout = 6000
                connection.useCaches = true
                connection.setRequestProperty("User-Agent", "LicenseNote/${BuildConfig.VERSION_NAME} (kr.juhyu.licensenote)")
                connection.setRequestProperty("Accept", "image/png")
                if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("Map HTTP ${connection.responseCode}")
                val bytes = connection.inputStream.use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > 512 * 1024) throw IOException("Oversized map tile")
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth != 256 || bounds.outHeight != 256) throw IOException("Invalid map tile size")
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() ?: throw IOException("Invalid map image")
                val control = connection.getHeaderField("Cache-Control").orEmpty()
                val maxAge = Regex("(?:^|,)\\s*max-age=(\\d+)", RegexOption.IGNORE_CASE).find(control)?.groupValues?.get(1)?.toLongOrNull()
                val age = connection.getHeaderField("Age")?.toLongOrNull() ?: 0
                val expiresAt = when {
                    control.contains("no-store", true) || control.contains("no-cache", true) -> now
                    maxAge != null -> now + (maxAge - age).coerceAtLeast(0) * 1000
                    connection.expiration > 0 -> connection.expiration
                    else -> now + 7L * 24 * 60 * 60 * 1000
                }
                images.put(tile, Cached(bitmap, expiresAt))
                cache.flush()
                bitmap
            } finally { connection.disconnect() }
        }
    }
}

/** Real raster map, fixed course extent. The position marker only uses the supplied SDK point. */
@Composable
fun CourseMap(point: GeoPoint?, modifier: Modifier = Modifier, linksEnabled: Boolean = true, route: PracticeRoute = CourseCatalog.full, mapLabel: String = "연습 코스", focusPoint: GeoPoint? = null) {
    val textScale = LocalAppTextScale.current
    val context = LocalContext.current.applicationContext
    val uriHandler = LocalUriHandler.current
    val night = isSystemInDarkTheme()
    val paper = if (night) Color(0xFF11171D) else Color.White
    val ink = if (night) Color(0xFFEDF2F8) else Color(0xFF191F28)
    val second = if (night) Color(0xFFC0CDDA) else Color(0xFF4E5968)
    val light = if (night) Color(0xFF1C2731) else Color(0xFFF2F4F6)
    val line = if (night) Color(0xFF465769) else Color(0xFFDCE2E8)
    val blue = if (night) Color(0xFF8DBDFF) else Color(0xFF185CCB)
    val tileFilter = remember(night) {
        val matrix = if (!night) ColorMatrix().apply { setToSaturation(.18f) } else ColorMatrix(floatArrayOf(
            // Map source luminance between the app's night ink and paper colours.
            -.18342f, -.61713f, -.06219f, 0f, 237f,
            -.18258f, -.61423f, -.06201f, 0f, 242f,
            -.18258f, -.61423f, -.06201f, 0f, 248f,
            0f, 0f, 0f, 1f, 0f,
        ))
        ColorFilter.colorMatrix(matrix)
    }
    var mapSize by remember { mutableStateOf(IntSize.Zero) }
    val view = remember(mapSize, route.id) { viewport(mapSize, route) }
    var load by remember(view) { mutableStateOf(MapLoad()) }
    LaunchedEffect(view) {
        val current = view ?: return@LaunchedEffect
        repeat(2) { attempt ->
            if (attempt > 0) delay(4_000)
            try {
                withTimeout(35_000) {
                    for (tile in current.tiles.filterNot { it in load.images }) {
                        ensureActive()
                        try {
                            val image = CourseTiles.image(context, tile)
                            load = load.copy(images = load.images + (tile to image), completed = load.completed + 1)
                        } catch (error: CancellationException) { throw error }
                        catch (_: Exception) { /* Retain the course overlay when the tile or cache is unavailable. */ }
                    }
                }
            } catch (_: TimeoutCancellationException) { /* Keep successfully decoded tiles. */ }
            load = load.copy(finished = true, failed = current.tiles.size - load.images.size)
            if (load.failed == 0) return@LaunchedEffect
        }
    }
    val status = when {
        view == null -> "지도 준비 중"
        !load.finished -> "지도 준비 ${load.images.size}/${view.tiles.size}"
        load.failed > 0 -> "지도 불러오기 실패 ${load.failed}개 · 표시 ${load.images.size}/${view.tiles.size}"
        else -> "지도 준비 완료 ${load.images.size}/${view.tiles.size}"
    }
    LaunchedEffect(status, view, night) {
        if (BuildConfig.DEBUG) Log.i("LicenseMap", "$status; zoom=${view?.zoom}; size=${mapSize.width}x${mapSize.height}; night=$night")
    }
    val actualPoint = point?.takeIf { it.lat.isFinite() && it.lon.isFinite() && it.lat in -85.05112878..85.05112878 && it.lon in -180.0..180.0 }
    val marker = actualPoint?.let { view?.screen(it) }
    val markerVisible = marker != null && marker.x in 0f..mapSize.width.toFloat() && marker.y in 0f..mapSize.height.toFloat()
    val positionText = when {
        !markerVisible -> "지도 밖 위치"
        else -> "현재 위치"
    }
    val shape = RoundedCornerShape(22.dp)
    Column(modifier.heightIn(min = 280.dp, max = 520.dp).clip(shape).border(1.dp, line, shape).background(paper)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Canvas(Modifier.size(width = 24.dp, height = 16.dp)) { drawLine(blue, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 4.dp.toPx(), StrokeCap.Round) }
                Text(mapLabel, color = ink, fontFamily = MapAsta, fontWeight = FontWeight.Medium, fontSize = (22 * textScale).sp)
            }
            if (actualPoint != null) Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                if (markerVisible) {
                    Canvas(Modifier.size(16.dp)) { drawCircle(blue, 5.dp.toPx()) }
                    Spacer(Modifier.width(8.dp))
                }
                Text(positionText, color = second, fontFamily = MapAsta, fontSize = (20 * textScale).sp)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(line))
        Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
            Canvas(Modifier.fillMaxSize().onSizeChanged { mapSize = it }.background(light).semantics {
                contentDescription = "$status. 실제 OpenStreetMap 지도. 선은 $mapLabel. " + (actualPoint?.let { "수신 위치 ${String.format(Locale.US, "%.5f, %.5f", it.lat, it.lon)}" } ?: "현재 위치 없음")
            }) {
                val current = view ?: return@Canvas
                for ((tile, bitmap) in load.images) {
                    val x = (tile.x * current.tilePixels - current.left).roundToInt()
                    val y = (tile.y * current.tilePixels - current.top).roundToInt()
                    val right = ((tile.x + 1) * current.tilePixels - current.left).roundToInt()
                    val bottom = ((tile.y + 1) * current.tilePixels - current.top).roundToInt()
                    drawImage(bitmap, dstOffset = IntOffset(x, y), dstSize = IntSize(right - x, bottom - y), colorFilter = tileFilter)
                }
                // Keep the route sketch available when background tiles are unavailable.
                run {
                    val path = Path()
                    route.points.forEachIndexed { index, p ->
                        val at = current.screen(p)
                        if (index == 0) path.moveTo(at.x, at.y) else path.lineTo(at.x, at.y)
                    }
                    drawPath(path, paper, style = Stroke(9.dp.toPx(), cap = StrokeCap.Round))
                    drawPath(path, blue, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
                    focusPoint?.let { focus ->
                        val position = current.screen(focus)
                        drawCircle(paper, 12.dp.toPx(), position)
                        drawCircle(blue, 8.dp.toPx(), position)
                    }
                    if (markerVisible) {
                        drawCircle(blue.copy(alpha = .2f), 22.dp.toPx(), marker!!)
                        drawCircle(paper, 14.dp.toPx(), marker)
                        drawCircle(blue, 10.dp.toPx(), marker)
                    }
                }
            }
            if (!load.finished || load.failed > 0) Column(Modifier.align(Alignment.TopStart).padding(12.dp).clip(RoundedCornerShape(12.dp)).background(paper.copy(alpha = .96f)).padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(when {
                    !load.finished -> "지도를 불러오고 있어요"
                    load.images.isEmpty() -> "연습 코스 약도"
                    else -> "일부 지도 영역을 불러오지 못했어요"
                }, color = ink, fontFamily = MapAsta, fontWeight = FontWeight.Medium, fontSize = (22 * textScale).sp)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(line))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = { runCatching { uriHandler.openUri("https://www.openstreetmap.org/copyright") } }, enabled = linksEnabled, modifier = Modifier.heightIn(min = 76.dp)) {
                Text("© OpenStreetMap contributors", color = if (!linksEnabled) second else blue, fontFamily = MapAsta, fontSize = (20 * textScale).sp)
            }
            if (linksEnabled && route.routingSeconds != null) TextButton(onClick = {
                runCatching { uriHandler.openUri("https://www.openstreetmap.org/fixthemap?mlat=${route.finish.lat}&mlon=${route.finish.lon}#map=17/${route.finish.lat}/${route.finish.lon}") }
            }, modifier = Modifier.heightIn(min = 76.dp)) {
                Text("지도 수정", color = blue, fontFamily = MapAsta, fontSize = (20 * textScale).sp)
            }
        }
    }
}
