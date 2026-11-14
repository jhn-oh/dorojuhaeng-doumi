package kr.juhyu.licensenote

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.floor
import kotlin.math.min

private val cancelWorker = Executors.newSingleThreadExecutor()

@Composable private fun ReportQr(payload: String) {
    val qr = remember(payload) { runCatching { encodeReportQr(payload) }.getOrNull() }
    if (qr == null) { Copy("이 결과의 QR을 만들 수 없어요.", 30); return }
    Canvas(Modifier.size(440.dp).semantics { contentDescription = "휴대폰 카메라로 읽는 QR 코드" }) {
        drawRect(Color.White)
        val unit = floor(min(size.width, size.height) / qr.width)
        val x = floor((size.width - qr.width * unit) / 2)
        val y = floor((size.height - qr.height * unit) / 2)
        for (row in 0 until qr.height) for (col in 0 until qr.width) {
            if (qr[col, row]) drawRect(Color.Black, Offset(x + col * unit, y + row * unit), Size(unit, unit))
        }
    }
}

@Composable internal fun ReceiverScreen(session: PracticeSession, close: () -> Unit) {
    val prefs = LocalContext.current.getSharedPreferences("receiver", Context.MODE_PRIVATE)
    val scope = rememberCoroutineScope()
    val summary = remember(session.id, session.revision) { reportText(session) }
    var address by remember { mutableStateOf(prefs.getString("address", "") ?: "") }
    val api = remember(address) { runCatching { ReceiverApi(address, BuildConfig.DEBUG) }.getOrNull() }
    var mode by remember { mutableStateOf("choose") }
    var pair by remember { mutableStateOf<JSONObject?>(null) }
    var state by remember { mutableStateOf("waiting") }
    var alias by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val p = LocalPalette.current
    // Leaving the parked screen invalidates the link on the relay. A link also expires if the vehicle disconnects.
    DisposableEffect(api, pair) {
        val current = pair
        onDispose { if (api != null && current != null) cancelWorker.execute { runCatching { api.action(current, "cancel", JSONObject()) } } }
    }
    LaunchedEffect(pair, api) {
        val current = pair ?: return@LaunchedEffect
        if (api == null) return@LaunchedEffect
        while (true) {
            try {
                val response = withContext(Dispatchers.IO) { api.action(current, "status") }
                state = response.getString("state")
                alias = response.optString("alias")
                if (state == "received" && response.optInt("revision") != session.revision) state = "invalid"
                if (state in setOf("received","cancelled","invalid")) break
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = if (e.message == "HTTP 410") "연결 시간이 지났어요. 새 QR로 연결해 주세요." else "휴대폰의 수신 여부를 확인할 수 없어요."
                break
            }
            delay(1200)
        }
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            when (mode) {
                "offline" -> {
                    Copy("휴대폰 카메라로 찍어 주세요", 52, weight = FontWeight.SemiBold)
                    Copy("인터넷 연결 없이 이번 연습 결과를 글로 열 수 있어요.", 30, p.second)
                    ReportQr(summary)
                }
                "setup" -> {
                    Copy("휴대폰 연결", 50, weight = FontWeight.SemiBold)
                    if (pair == null) {
                        Copy("연결 서버 주소를 입력하면 휴대폰으로 찍을 QR이 열려요.", 30, p.second)
                        OutlinedTextField(value = address, onValueChange = { address = it; error = "" }, singleLine = true,
                            enabled = !busy, label = { Copy("연결 서버 주소", 24) }, modifier = Modifier.fillMaxWidth())
                    } else when (state) {
                        "waiting" -> { Copy("휴대폰으로 QR을 찍고 기기 이름을 확인해 주세요.", 30); ReportQr(pair!!.getString("url")) }
                        "paired" -> { Copy("받을 기기 · $alias", 34, p.blue); Copy(summary, 25, p.second) }
                        "sent" -> Copy("${alias}에서 기록을 열고 수신을 확인해 주세요.", 34)
                        "received" -> Copy("${alias}에서 이번 기록을 받았어요.", 40, p.blue, FontWeight.SemiBold)
                        else -> Copy("연결이 끝났어요.", 36)
                    }
                }
                else -> {
                    FigmaIcon(R.drawable.figma_back, 195, p.blue, assetOverride = "phone")
                    Copy("연습 기록을 보낼까요?", 58, weight = FontWeight.SemiBold, align = TextAlign.Center)
                    Copy("점수와 확인한 항목, 오답을 함께 보내요.", 34, p.second, align = TextAlign.Center)
                }
            }
            if (error.isNotEmpty()) Copy(error, 28, p.blue)
        }
        val button = Modifier.widthIn(max = 784.dp).fillMaxWidth()
        when {
            mode == "choose" -> {
                Action("휴대폰 연결하기", modifier = button) { mode = "setup" }
                Link("QR로 받기", modifier = button) { mode = "offline" }
            }
            mode == "setup" && pair == null -> Action("연결 QR 열기", api != null && !busy, modifier = button) {
                val client = api ?: return@Action
                busy = true; error = ""
                scope.launch {
                    try {
                        val opened = withContext(Dispatchers.IO) { client.pair() }
                        prefs.edit().putString("address", address.trim()).apply()
                        state = "waiting"; pair = opened
                    } catch (e: Exception) { if (e is CancellationException) throw e; error = "서버에 연결할 수 없어요. 주소를 확인하거나 결과 QR을 사용해 주세요." }
                    finally { busy = false }
                }
            }
            mode == "setup" && state == "paired" && error.isEmpty() -> Action("이 휴대폰으로 보내기", !busy, modifier = button) {
                val current = pair ?: return@Action
                val client = api ?: return@Action
                busy = true
                scope.launch {
                    try {
                        val receipt = withContext(Dispatchers.IO) {
                            client.action(current, "send", JSONObject().put("summary", summary).put("revision", session.revision))
                        }
                        state = receipt.getString("state")
                    } catch (e: Exception) { if (e is CancellationException) throw e; error = "전송을 확인하지 못했어요. 연결을 다시 시도해 주세요." }
                    finally { busy = false }
                }
            }
        }
        if (mode == "setup" && error.isNotEmpty() && pair != null) Link("다시 연결", modifier = button) { pair = null; state = "waiting"; error = "" }
        if (mode == "setup" && state != "received") Link("QR로 받기", !busy, button) { pair = null; mode = "offline"; error = "" }
        if (mode != "choose") Link(if (pair != null && state != "received") "전송 취소" else "결과로 돌아가기", modifier = button, onClick = close)
    }
}
