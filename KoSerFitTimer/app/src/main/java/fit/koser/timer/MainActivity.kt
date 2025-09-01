
package fit.koser.timer

import android.content.ContentValues
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { AppUI() }
  }
}

@Composable
fun AppUI() {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()

  var type by remember { mutableStateOf("timer") } // timer | stopwatch
  var hours by remember { mutableStateOf(0) }
  var minutes by remember { mutableStateOf(0) }
  var seconds by remember { mutableStateOf(10) }
  var mode by remember { mutableStateOf("reverse") } // forward | reverse

  var fps by remember { mutableStateOf(60) }
  var width by remember { mutableStateOf(1920) }
  var height by remember { mutableStateOf(1080) }

  var style by remember { mutableStateOf("video") } // plain|neon|fire|water|video
  var skin by remember { mutableStateOf("minimal") } // none|minimal|lcd|glass|retro|flip

  var digitColor by remember { mutableStateOf(Color(0xFF4EF3FF)) }
  var bgColor by remember { mutableStateOf(Color(0xFF000000)) }
  var strokeColor by remember { mutableStateOf(Color(0xFF000000)) }
  var strokeWidth by remember { mutableStateOf(6f) }

  var waveIntensity by remember { mutableStateOf(1.2f) }
  var bubbleSpeed by remember { mutableStateOf(1.5f) }
  var innerWaves by remember { mutableStateOf(true) }

  var brandFx by remember { mutableStateOf(true) }
  var brandPos by remember { mutableStateOf(Offset(0.5f, 0.17f)) }
  var brandMove by remember { mutableStateOf(false) }
  var brandSpeed by remember { mutableStateOf(1.4f) }
  var brandAmp by remember { mutableStateOf(0.02f) }

  var swFastDur by remember { mutableStateOf(30) }

  val onFastRender = {
    scope.launch(Dispatchers.Default) {
      try {
        val totalSec = if (type == "timer") hours * 3600 + minutes * 60 + seconds else swFastDur.coerceAtLeast(1)
        if (type == "timer" && totalSec <= 0) {
          launch(Dispatchers.Main) { Toast.makeText(ctx, "Задай время таймера больше нуля", Toast.LENGTH_SHORT).show() }
          return@launch
        }

        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val name = if (type=="timer") "timer_${sdf.format(Date())}.mp4" else "stopwatch_${sdf.format(Date())}.mp4"

        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
          put(MediaStore.Video.Media.DISPLAY_NAME, name)
          put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
          put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/KoSerFit")
          put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("Не удалось создать файл")
        resolver.openFileDescriptor(uri, "rw").use { pfd ->
          val fd = pfd?.fileDescriptor ?: throw IllegalStateException("Нет FD")
          val renderer = OfflineRenderer(
            width = width, height = height, fps = fps,
            style = style, skin = skin,
            digitColor = digitColor.hashCode(), bgColor = bgColor.hashCode(),
            strokeColor = strokeColor.hashCode(), strokeWidth = strokeWidth,
            brandFx = brandFx, brandSpeed = brandSpeed, brandAmp = brandAmp,
            brandRelX = brandPos.x, brandRelY = brandPos.y,
            waterInner = innerWaves, waveIntensity = waveIntensity, bubbleSpeed = bubbleSpeed
          )
          val forward = (mode == "forward")
          val startVal = when {
            type == "stopwatch" -> 0
            forward -> 0
            else -> totalSec
          }
          val durationSec = if (type == "stopwatch") swFastDur else totalSec
          renderer.renderToMp4(
            fd = fd,
            durationSeconds = durationSec,
            startCounter = startVal,
            forward = (type == "stopwatch" || forward)
          )
        }
        launch(Dispatchers.Main) { Toast.makeText(ctx, "Готово — Movies/KoSerFit", Toast.LENGTH_LONG).show() }
      } catch (t: Throwable) {
        t.printStackTrace()
        launch(Dispatchers.Main) { Toast.makeText(ctx, "Ошибка: ${t.message}", Toast.LENGTH_LONG).show() }
      }
    }
  }

  MaterialTheme {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .background(Color(0xFF0B0B0D))
        .padding(12.dp)
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Text("KoSerFit: оффлайн-рендер MP4 (MediaCodec)", fontWeight = FontWeight.Bold)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onFastRender) { Text("⚡ Быстрый рендер → MP4") }
      }
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Тип:")
        Segmented(items = listOf("Таймер" to "timer", "Секундомер" to "stopwatch"), selected = type) { type = it }
      }
      if (type == "timer") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          NumberPicker("Часы", hours) { hours = it.coerceIn(0, 99) }
          NumberPicker("Минуты", minutes) { minutes = it.coerceIn(0, 59) }
          NumberPicker("Секунды", seconds) { seconds = it.coerceIn(0, 59) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Text("Режим:")
          Segmented(items = listOf("↑ Прямой" to "forward", "↓ Обратный" to "reverse"), selected = mode) { mode = it }
        }
      } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Длительность секундомера, сек:")
          NumberPicker("", swFastDur) { swFastDur = it.coerceAtLeast(1) }
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberPicker("FPS", fps) { fps = it.coerceIn(30, 120) }
        NumberPicker("Ширина", width) { width = it.coerceIn(640, 3840) }
        NumberPicker("Высота", height) { height = it.coerceIn(360, 2160) }
      }
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Стиль:")
        Segmented(listOf("Обычный" to "plain","Неон" to "neon","Огонь" to "fire","Вода" to "water","Видео" to "video"), style){ style = it }
      }
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Скин:")
        Segmented(listOf("Нет" to "none","Минимал" to "minimal","LCD" to "lcd","Стекло" to "glass","Retro" to "retro","Flip" to "flip"), skin){ skin = it }
      }

      // Простейший "холст" для демонстрации перетаскивания KoSerFit
      var dragging by remember { mutableStateOf(false) }
      Box(
        modifier = Modifier
          .fillMaxWidth().height(80.dp)
          .background(Color(0xFF111418))
          .pointerInput(brandMove) {
            if (!brandMove) return@pointerInput
            detectDragGestures(
              onDragStart = { dragging = true },
              onDragEnd = { dragging = false },
              onDragCancel = { dragging = false },
              onDrag = { change, drag ->
                change.consume()
                brandPos = Offset(
                  (brandPos.x + drag.x / width.toFloat()).coerceIn(0.02f, 0.98f),
                  (brandPos.y + drag.y / height.toFloat()).coerceIn(0.05f, 0.95f)
                )
              }
            )
          },
        contentAlignment = Alignment.Center
      ) {
        Text("Перемещение KoSerFit: включи «Двигать надпись» и тащи тут", color = Color(0xFF7A8B97))
      }
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AssistChip(checked = brandMove, onClick = { brandMove = !brandMove }, label = "Двигать надпись")
        AssistChip(checked = brandFx, onClick = { brandFx = !brandFx }, label = "Неон KoSerFit")
      }
    }
  }
}

@Composable
fun NumberPicker(label: String, value: Int, onChange: (Int) -> Unit) {
  Column {
    if (label.isNotEmpty()) Text(label)
    OutlinedTextField(
      value = value.toString(),
      onValueChange = { onChange(it.toIntOrNull() ?: value) },
      singleLine = true,
      modifier = Modifier.width(120.dp)
    )
  }
}

@Composable
fun AssistChip(checked:Boolean, onClick:()->Unit, label:String){
  FilterChip(checked=checked, onClick=onClick, label={ Text(label) })
}

@Composable
fun Segmented(items: List<Pair<String,String>>, selected:String, onSelect:(String)->Unit){
  Row(horizontalArrangement = Arrangement.spacedBy(6.dp)){
    items.forEach { (title, key) ->
      FilterChip(checked = selected==key, onClick={ onSelect(key) }, label={ Text(title) })
    }
  }
}
