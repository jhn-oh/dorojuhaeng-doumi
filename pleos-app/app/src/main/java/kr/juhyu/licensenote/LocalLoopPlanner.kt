package kr.juhyu.licensenote

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.*

/** Candidate locations are only inputs. A loop exists only after a car-road routing response. */
internal fun loopCandidates(origin: GeoPoint, minutes: Int, bearing: Double, radiusScale: Double = 1.0): List<GeoPoint> {
    require(minutes in 5..45 && origin.lat in -80.0..80.0 && origin.lon in -180.0..180.0)
    require(radiusScale.isFinite() && radiusScale > 0)
    val radius = (minutes * 55.0 * radiusScale).coerceIn(200.0, 5000.0)
    fun at(degrees: Double): GeoPoint {
        val radians = Math.toRadians(degrees)
        return GeoPoint(origin.lat + cos(radians) * radius / 111_195.0,
            origin.lon + sin(radians) * radius / (111_195.0 * cos(Math.toRadians(origin.lat))))
    }
    return listOf(at(bearing), at(bearing + 120))
}

internal fun validLocalLoop(route: PracticeRoute): Boolean {
    val origin = route.origin ?: return false
    return route.points.size in 4..8000 && route.waypoints.size in 1..2 && route.distance in 400..60_000 &&
        route.routingSeconds?.let { it.isFinite() && it > 0 } == true &&
        route.points.all { it.lat.isFinite() && it.lon.isFinite() && it.lat in -90.0..90.0 && it.lon in -180.0..180.0 } &&
        distanceMeters(origin, route.points.first()) <= 150 && distanceMeters(origin, route.points.last()) <= 150 &&
        route.waypoints.all { distanceMeters(origin, it) in 150.0..20_000.0 }
}

internal object LocalLoopPlanner {
    // ponytail: prototype uses the fair-use Valhalla demo; deploy a contracted/self-hosted router before release.
    private const val endpoint = "https://valhalla1.openstreetmap.de/route"

    fun plan(origin: GeoPoint, minutes: Int, via: NativeDestination? = null): PracticeRoute {
        if (via != null) return roadLoop(origin, listOf(via.point), via.name)
        return timedLoop(origin, minutes) { roadLoop(origin, it) }
    }

    internal fun timedLoop(origin: GeoPoint, minutes: Int, find: (List<GeoPoint>) -> PracticeRoute): PracticeRoute {
        require(minutes in 5..45)
        var failure: Exception? = null
        var radiusScale = 1.0
        val deadline = System.nanoTime() + 40_000_000_000L
        for (attempt in 0 until 6) {
            if (System.nanoTime() >= deadline) break
            try {
                val bearing = (attempt / 2) * 90.0
                val route = find(loopCandidates(origin, minutes, bearing, radiusScale))
                if (abs(route.minutes - minutes) <= maxOf(2, minutes / 6)) return route
                val seconds = route.routingSeconds?.takeIf { it.isFinite() && it > 0 }
                    ?: throw IOException("Missing route duration")
                radiusScale = (radiusScale * (minutes * 60.0 / seconds).coerceIn(0.5, 1.8)).coerceIn(0.28, 2.5)
            } catch (error: Exception) {
                failure = error
                radiusScale = (radiusScale * .75).coerceAtLeast(.28)
            }
        }
        throw IOException("약 ${minutes}분에 맞는 주변 코스를 찾지 못했어요. 다른 시간을 선택하거나 다시 시도해 주세요.", failure)
    }

    private fun roadLoop(origin: GeoPoint, candidates: List<GeoPoint>, name: String? = null): PracticeRoute {
        val payload = JSONObject().put("locations", JSONArray((listOf(origin) + candidates + origin).map {
            JSONObject().put("lat", it.lat).put("lon", it.lon).put("radius", 100)
        })).put("costing", "auto").put("units", "kilometers").put("language", "ko-KR")
            .put("costing_options", JSONObject().put("auto", JSONObject().put("exclude_highways", true)
                .put("exclude_tolls", true).put("exclude_ferries", true)))
        return decode(request(payload), origin, name)
    }

    private fun request(payload: JSONObject): JSONObject {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("User-Agent", "RoadDrivingHelper/${BuildConfig.VERSION_NAME} (prototype)")
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode != 200) throw IOException("Route HTTP ${connection.responseCode}")
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 2_000_000) throw IOException("Oversized route response")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return JSONObject(String(bytes, Charsets.UTF_8))
        } finally { connection.disconnect() }
    }

    internal fun decode(response: JSONObject, origin: GeoPoint, viaName: String? = null): PracticeRoute {
        val trip = response.getJSONObject("trip")
        require(trip.getInt("status") == 0) { "No car route" }
        val summary = trip.getJSONObject("summary")
        require(listOf("has_highway", "has_toll", "has_ferry").all { !summary.optBoolean(it, true) }) { "Excluded road type" }
        val legs = trip.getJSONArray("legs")
        require(legs.length() in 2..3) { "Incomplete round trip" }
        val shapes = (0 until legs.length()).map { decodePolyline(legs.getJSONObject(it).getString("shape")) }
        val points = shapes.first() + shapes.drop(1).flatMap { it.drop(1) }
        val route = PracticeRoute("local-loop-${System.currentTimeMillis()}", viaName?.let { "$it 다녀오기" } ?: "내 주변 한 바퀴",
            "현재 출발 위치", "출발 위치로 복귀", points, emptyList(),
            routingSeconds = summary.getDouble("time"), waypoints = shapes.dropLast(1).map { it.last() },
            distance = (summary.getDouble("length") * 1000).roundToInt(), origin = origin,
            routingSource = "Valhalla auto · OpenStreetMap road graph")
        require(validLocalLoop(route)) { "Route does not return to the captured origin" }
        return route
    }

    private fun decodePolyline(shape: String): List<GeoPoint> {
        var position = 0
        fun delta(): Int {
            var shift = 0
            var result = 0
            do {
                require(position < shape.length && shift <= 30) { "Invalid polyline" }
                val group = shape[position++].code - 63
                require(group in 0..63)
                result = result or ((group and 31) shl shift)
                shift += 5
            } while (group >= 32)
            return if (result and 1 == 1) (result shr 1).inv() else result shr 1
        }
        var lat = 0
        var lon = 0
        return buildList {
            while (position < shape.length) {
                require(size < 30_000) { "Oversized route shape" }
                lat += delta(); lon += delta()
                add(GeoPoint(lat / 1_000_000.0, lon / 1_000_000.0))
            }
        }
    }
}

internal fun PracticeRoute.json(): JSONObject = JSONObject().put("id", id).put("name", name)
    .put("origin", origin?.let { JSONArray(listOf(it.lat, it.lon)) })
    .put("points", JSONArray(points.map { JSONArray(listOf(it.lat, it.lon)) }))
    .put("waypoints", JSONArray(waypoints.map { JSONArray(listOf(it.lat, it.lon)) }))
    .put("distance", distance).put("seconds", routingSeconds).put("source", routingSource)

internal fun parsePlannedRoute(data: JSONObject): PracticeRoute? = runCatching {
    fun points(array: JSONArray) = (0 until array.length()).map {
        val pair = array.getJSONArray(it); GeoPoint(pair.getDouble(0), pair.getDouble(1))
    }
    val origin = data.getJSONArray("origin").let { GeoPoint(it.getDouble(0), it.getDouble(1)) }
    PracticeRoute(data.getString("id"), data.getString("name"), "현재 출발 위치", "출발 위치로 복귀",
        points(data.getJSONArray("points")), emptyList(), routingSeconds = data.getDouble("seconds"),
        waypoints = points(data.getJSONArray("waypoints")), distance = data.getInt("distance"), origin = origin,
        routingSource = data.optString("source")).takeIf(::validLocalLoop)
}.getOrNull()
