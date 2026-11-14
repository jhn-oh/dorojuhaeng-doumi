package kr.juhyu.licensenote

/** Three connected Valhalla auto legs, matched to the same-direction ITS standard links. */
internal object ArterialCourses {
    private data class Leg(val points: List<GeoPoint>, val meters: Int, val seconds: Double)
    private val legs = listOf(
        Leg(decode("gnuofAedehqFqAgGgDqPgLqj@sNas@gKkg@eBaJiC}L{Kck@_Oqr@wDuQgOou@u[{~AaBmIsC{MkFcXkF{Wa@oBwBqKoAaG??"), 834, 160.237),
        Leg(decode("_tzofAekvhqFkAsFkGeZkNsp@eMgl@eLwi@{AwHaHa^_Nqk@cSocAk@yCmAeGo@aDa@kBgA}Eo@iD_AyE}@yEuEwS}Qu|@uXapA}Z}`Bka@inBuEmS{Zs{AkT_dAuO_w@uAaHyg@icCoVolAiDyP}AqGkDoQeCqM}CcOkKqf@}Kkg@iGuW_DwMeCwLySkcAwR}nAwKyg@u@yD{@oE}@}EoFsYqDkKyA_FuQou@qEwK_Qgr@yLqi@_AkFgK{w@y@gJyJoeAmMomB"), 3142, 596.03),
        Leg(decode("osmpfAcowjqFKkAs@qH{AuRcBoNuCeU{e@ukF{UecCiDqd@GoMGuHS}TBkI\\aSb@_W~@om@Lmc@nAgl@~BcmA|A}|BH_SV}PTiOfDqrDsResEs@eOi@uIq@_UiIqrBcCuh@eBmXmHqr@qHwg@y_@kgCmF}W{AwH}AmIwAqG_D_L{CoK_DmMyV_cAw^}yA{Q{v@_Jm_@m]sxAuEyRwDqOqOom@yRsx@a@gBcFkTa]yuAaFkS}X_fAsEgQsAyFeCaKkDkC@wA@aG}ByAuGsCiZeP{OgH}MgGg`A{e@iN}GcDyBuFuB_FgDwCwBaCeEyO_J_wAav@sJkF{BeAoR}Jgl@{ZqAw@y}@se@"), 4732, 899.479),
    )

    private val stops = listOf("강남역 동쪽 테헤란로", "역삼역 인근 테헤란로", "잠실종합운동장 인근 올림픽로", "올림픽공원 서쪽 올림픽로")
    val routes = (0..2).flatMap { start -> ((start + 1)..3).map { end ->
        val selected = legs.subList(start, end)
        PracticeRoute("seoul-arterial-$start-$end-v1", "${stops[end]}까지", stops[start], stops[end],
            points = selected.first().points + selected.drop(1).flatMap { it.points.drop(1) },
            turns = emptyList(), routingSeconds = selected.sumOf { it.seconds },
            distance = if (start == 0 && end == 3) 8709 else selected.sumOf { it.meters }, waypoints = selected.dropLast(1).map { it.points.last() },
            verifiedArterial = true)
    } }

    // Valhalla polyline6 stores signed latitude and longitude deltas as variable-width groups.
    private fun decode(shape: String): List<GeoPoint> {
        var position = 0
        fun delta(): Int {
            var shift = 0
            var result = 0
            do {
                val group = shape[position++].code - 63
                result = result or ((group and 31) shl shift)
                shift += 5
            } while (group >= 32)
            return if (result and 1 == 1) (result shr 1).inv() else result shr 1
        }
        var lat = 0
        var lon = 0
        return buildList {
            while (position < shape.length) {
                lat += delta()
                lon += delta()
                add(GeoPoint(lat / 1_000_000.0, lon / 1_000_000.0))
            }
        }
    }
}
