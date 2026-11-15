package kr.juhyu.licensenote

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Navigation is a separate Pleos app window; no tile provider or embedded map is used. */
@Composable
fun CourseMap(point: GeoPoint?, modifier: Modifier = Modifier, linksEnabled: Boolean = true,
              route: PracticeRoute = CourseCatalog.full, mapLabel: String = "기본 내비 연동", focusPoint: GeoPoint? = null) {
    val p = LocalPalette.current
    val context = LocalContext.current
    Surface(modifier = modifier.heightIn(min = 240.dp), color = p.light,
        shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, p.line)) {
        Column(Modifier.fillMaxWidth().padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Copy("길 안내는 기본 내비에서", 34, p.ink, FontWeight.SemiBold)
            Copy(if (route.origin != null) "현재 위치 → 연습 경유지 ${route.waypoints.size}곳 → 출발 위치" else "${route.departure} → ${route.destination}", 28, p.second)
            Copy("차량 화면 · 기본 내비 · 도로주행도우미를 함께 놓고 사용하세요.", 26, p.second)
            Copy("연습을 시작하면 기본 내비에 경유지와 복귀 지점을 전달해요. 실제 길과 교통 상황은 내비에서 확인하세요.", 24, p.second)
            if (linksEnabled) Link("기본 내비 열기", modifier = Modifier.fillMaxWidth()) {
                context.startService(Intent(context, TelemetryService::class.java).setAction("OPEN_NAVIGATION").putExtra("withCourseRoute", false))
            }
        }
    }
}
