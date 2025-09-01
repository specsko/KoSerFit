
package fit.koser.timer

import android.graphics.*
import android.media.*
import android.view.Surface
import kotlin.math.*

class OfflineRenderer(
  private val width: Int,
  private val height: Int,
  private val fps: Int,
  private val style: String,
  private val skin: String,
  private val digitColor: Int,
  private val bgColor: Int,
  private val strokeColor: Int,
  private val strokeWidth: Float,
  private val brandFx: Boolean,
  private val brandSpeed: Float,
  private val brandAmp: Float,
  private val brandRelX: Float,
  private val brandRelY: Float,
  private val waterInner: Boolean,
  private val waveIntensity: Float,
  private val bubbleSpeed: Float
) {
  private val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
    setInteger(MediaFormat.KEY_BIT_RATE, (width*height*fps*0.12).toInt().coerceAtLeast(3_000_000))
    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
  }
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
  private val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

  data class Bubble(var x:Float,var y:Float,var r:Float,var spd:Float,var drift:Float,var phase:Float)
  private val bubbles = mutableListOf<Bubble>()

  fun renderToMp4(
    fd: java.io.FileDescriptor,
    durationSeconds: Int,
    startCounter: Int,
    forward: Boolean
  ) {
    val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    val inputSurface = codec.createInputSurface()
    val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

    codec.start()

    val w = width.toFloat()
    val h = height.toFloat()

    bubbles.clear()
    val bubbleCount = min(900, (width*height/4500)).toInt()
    repeat(bubbleCount){
      bubbles += Bubble(
        x = (Math.random()*width).toFloat(),
        y = (Math.random()*height).toFloat(),
        r = (2 + Math.random()*7).toFloat(),
        spd = (1.0 + Math.random()*2.5).toFloat(),
        drift = (0.4 + Math.random()*1.6).toFloat(),
        phase = (Math.random()*Math.PI*2).toFloat()
      )
    }

    val sizePx = (h * 0.18f).coerceAtLeast(40f)
    val brandSize = (h * 0.09f)
    val centerX = w/2f
    val baseY = h*0.55f

    var fireT = 0f
    var waterT = 0f
    var brandT = 0f
    val dt = 1f / fps

    var current = startCounter
    val frames = durationSeconds * fps + 1

    val info = MediaCodec.BufferInfo()
    var trackIndex = -1
    var muxerStarted = false

    fun drawFrame(surface: Surface, frameIndex: Int) {
      val c = surface.lockHardwareCanvas()
      try {
        c.drawColor(bgColor)
        drawSkin(c, skin, w, h)

        val bx = w * brandRelX + (if (!brandFx) 0f else brandAmp * min(w,h) * sin(brandT)).toFloat()
        val by = h * brandRelY + (if (!brandFx) 0f else brandAmp * min(w,h) * cos(brandT*1.3f)).toFloat()
        drawBrand(c, "KoSerFit", bx, by, brandSize, brandFx, waterT)

        val str = formatHMS(current)
        when (style) {
          "plain" -> drawPlain(c, str, centerX, baseY, sizePx)
          "neon"  -> drawNeon(c, str, centerX, baseY, sizePx, waterT)
          "fire"  -> drawFire(c, str, centerX, baseY, sizePx, fireT)
          "water" -> drawWater(c, str, centerX, baseY, sizePx, waterT)
          else    -> drawVideoNeon(c, str, centerX, baseY, sizePx, waterT)
        }
      } finally {
        surface.unlockCanvasAndPost(c)
      }

      fireT += dt; waterT += dt; brandT += dt * brandSpeed
      if (frameIndex % fps == 0 && frameIndex>0) {
        current = if (forward) (current + 1) else (current - 1).coerceAtLeast(0)
      }
    }

    for (i in 0 until frames) {
      drawFrame(inputSurface, i)
      val ptsUs = (1_000_000L * i / fps)

      while (true) {
        val infoOut = codec.dequeueOutputBuffer(info, 0)
        if (infoOut == MediaCodec.INFO_TRY_AGAIN_LATER) break
        if (infoOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          if (muxerStarted) error("format changed twice")
          trackIndex = muxer.addTrack(codec.outputFormat)
          muxer.start()
          muxerStarted = true
        } else if (infoOut >= 0) {
          if (!muxerStarted) error("muxer not started")
          info.presentationTimeUs = ptsUs
          val encoded = codec.getOutputBuffer(infoOut)!!
          muxer.writeSampleData(trackIndex, encoded, info)
          codec.releaseOutputBuffer(infoOut, false)
        }
      }
    }

    codec.signalEndOfInputStream()
    while (true) {
      val infoOut = codec.dequeueOutputBuffer(info, 10_000)
      if (infoOut == MediaCodec.INFO_TRY_AGAIN_LATER) break
      if (infoOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
        if (!muxerStarted) {
          trackIndex = muxer.addTrack(codec.outputFormat)
          muxer.start()
          muxerStarted = true
        }
      } else if (infoOut >= 0) {
        val encoded = codec.getOutputBuffer(infoOut)!!
        muxer.writeSampleData(trackIndex, encoded, info)
        codec.releaseOutputBuffer(infoOut, false)
        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
      }
    }

    codec.stop(); codec.release()
    muxer.stop(); muxer.release()
  }

  private fun formatHMS(sec:Int): String {
    val s = max(0, sec)
    val h = s/3600
    val m = (s%3600)/60
    val ss = s%60
    return "%02d:%02d:%02d".format(h,m,ss)
  }

  private fun drawSkin(c: Canvas, skin: String, w: Float, h: Float) {
    if (skin == "none") return
    val pad = min(w,h)*0.04f
    val r = min(w,h)*0.06f
    val rect = RectF(pad, pad, w-pad, h-pad)
    when (skin) {
      "minimal" -> {
        paint.shader = null; paint.color = Color.argb(255, 26, 28, 32); c.drawRoundRect(rect, r, r, paint)
      }
      "lcd" -> {
        paint.color = Color.rgb(43,46,51); c.drawRoundRect(rect, r, r, paint)
        val screen = inset(rect, min(w,h)*0.12f)
        val grad = LinearGradient(0f, screen.top, 0f, screen.bottom, Color.rgb(215,229,210), Color.rgb(183,201,176), Shader.TileMode.CLAMP)
        paint.shader = grad; c.drawRoundRect(screen, r*0.7f, r*0.7f, paint); paint.shader = null
      }
      "glass" -> {
        paint.color = Color.rgb(21,23,28); c.drawRoundRect(rect, r, r, paint)
        val screen = inset(rect, min(w,h)*0.12f)
        val grad = LinearGradient(0f, screen.top, 0f, screen.bottom, Color.rgb(27,32,40), Color.rgb(15,18,23), Shader.TileMode.CLAMP)
        paint.shader = grad; c.drawRoundRect(screen, r*0.7f, r*0.7f, paint); paint.shader = null
      }
      "retro" -> {
        paint.color = Color.rgb(12,13,16); c.drawRoundRect(rect, r, r, paint)
      }
      "flip" -> {
        paint.color = Color.rgb(14,17,22); c.drawRoundRect(rect, r, r, paint)
        val screen = inset(rect, min(w,h)*0.12f)
        paint.color = Color.rgb(18,20,23); c.drawRoundRect(screen, r*0.8f, r*0.8f, paint)
        paint.color = Color.rgb(10,12,16); c.drawRect(screen.left, screen.centerY()-2f, screen.right, screen.centerY()+2f, paint)
      }
    }
  }
  private fun inset(r: RectF, by: Float) = RectF(r.left+by, r.top+by, r.right-by, r.bottom-by)

  private fun drawPlain(c: Canvas, text:String, x:Float, y:Float, size:Float){
    textPaint.textSize = size
    textPaint.style = Paint.Style.FILL
    textPaint.color = digitColor
    if (strokeWidth > 0f){
      textPaint.style = Paint.Style.STROKE
      textPaint.strokeWidth = strokeWidth
      textPaint.color = strokeColor
      c.drawText(text, x, y, textPaint)
      textPaint.style = Paint.Style.FILL
      textPaint.color = digitColor
    }
    c.drawText(text, x, y + size*0.35f/2, textPaint)
  }

  private fun drawNeon(c: Canvas, text:String, x:Float, y:Float, size:Float, t:Float){
    textPaint.textSize = size; textPaint.textAlign = Paint.Align.CENTER
    textPaint.style = Paint.Style.STROKE
    textPaint.strokeWidth = size*0.10f
    textPaint.maskFilter = BlurMaskFilter(size*0.25f, BlurMaskFilter.Blur.NORMAL)
    textPaint.color = digitColor
    c.drawText(text, x, y + size*0.35f/2, textPaint)
    textPaint.strokeWidth = max(2f, size*0.02f)
    textPaint.maskFilter = null
    textPaint.color = Color.WHITE
    c.drawText(text, x, y + size*0.35f/2, textPaint)
  }

  private fun drawFire(c: Canvas, text:String, x:Float, y:Float, size:Float, t:Float){
    textPaint.textSize = size; textPaint.textAlign = Paint.Align.CENTER
    textPaint.style = Paint.Style.STROKE
    textPaint.strokeWidth = size*0.10f
    textPaint.maskFilter = BlurMaskFilter(size*0.18f, BlurMaskFilter.Blur.NORMAL)
    textPaint.color = Color.rgb(255,140,0)
    c.drawText(text, x, y + size*0.35f/2, textPaint)
    textPaint.maskFilter = null
    val grad = LinearGradient(x, y-size*0.6f, x, y+size*0.6f,
      intArrayOf(Color.rgb(255,247,204), Color.rgb(255,212,90), Color.rgb(255,154,31), Color.rgb(255,77,0)),
      floatArrayOf(0f,0.45f,0.8f,1f), Shader.TileMode.CLAMP)
    textPaint.shader = grad
    textPaint.style = Paint.Style.FILL
    c.drawText(text, x, y + size*0.35f/2, textPaint)
    textPaint.shader = null
  }

  private fun drawWater(c: Canvas, text:String, x:Float, y:Float, size:Float, t:Float){
    textPaint.textSize = size
    val path = Path()
    textPaint.getTextPath(text, 0, text.length, x, y + size*0.35f/2, path)

    val rect = RectF()
    path.computeBounds(rect, true)

    val grad = LinearGradient(0f, rect.top, 0f, rect.bottom,
      intArrayOf(Color.rgb(168,227,255), Color.rgb(11,94,168)),
      floatArrayOf(0f,1f), Shader.TileMode.CLAMP)
    paint.shader = grad

    c.save()
    c.clipPath(path)
    c.drawRect(rect, paint)

    if (waterInner){
      paint.shader = null
      paint.color = Color.argb(32, 255,255,255)
      val A = size*0.04f*waveIntensity
      val k1 = (2*Math.PI/(rect.width()*0.9f)).toFloat()
      val baseY = rect.centerY()
      var px = rect.left
      var py = baseY
      var xx = rect.left.toInt()
      while (xx <= rect.right.toInt()){
        val yy = baseY + A * sin(k1*xx + t*1.8f)
        c.drawLine(px, py, xx.toFloat(), yy, paint)
        px = xx.toFloat(); py = yy
        xx += 2
      }
    }

    // bubbles
    bubbles.forEach { b ->
      b.y -= b.spd * bubbleSpeed
      b.x += sin(t*1.6f + b.phase)*b.drift*0.12f * bubbleSpeed
      if (b.y < rect.top - 12) { b.y = rect.bottom + 12; b.x = rect.left + (Math.random()*rect.width()).toFloat() }
      val radial = RadialGradient(b.x - b.r*0.35f, b.y - b.r*0.35f, b.r*3f,
        intArrayOf(Color.WHITE, Color.TRANSPARENT), floatArrayOf(0f,1f), Shader.TileMode.CLAMP)
      paint.shader = radial
      c.drawCircle(b.x, b.y, b.r, paint)
    }

    c.restore()
    paint.shader = null

    textPaint.style = Paint.Style.STROKE
    textPaint.color = Color.argb(190, 0,0,0)
    textPaint.strokeWidth = max(2f, size*0.02f)
    c.drawText(text, x, y + size*0.35f/2, textPaint)
  }

  private fun hsv(h: Float): Int {
    val hh = ((h%1f)+1f)%1f
    return Color.HSVToColor(floatArrayOf(hh*360f, 0.95f, 1f))
  }

  private fun drawVideoNeon(c: Canvas, text:String, x:Float, y:Float, size:Float, t:Float){
    textPaint.textSize = size; textPaint.textAlign = Paint.Align.CENTER
    textPaint.style = Paint.Style.STROKE
    val hue = ((t*40f) % 360f) / 360f
    val colors = intArrayOf(hsv(hue), hsv(hue+0.16f), hsv(hue+0.33f), hsv(hue+0.5f), hsv(hue+0.66f), hsv(hue+0.83f))
    val grad = LinearGradient(x-size*0.9f, y-size*0.5f, x+size*0.9f, y+size*0.5f, colors, null, Shader.TileMode.CLAMP)
    textPaint.strokeWidth = size*0.10f
    textPaint.maskFilter = BlurMaskFilter(size*0.30f, BlurMaskFilter.Blur.NORMAL)
    textPaint.shader = grad
    c.drawText(text, x, y + size*0.35f/2, textPaint)
    textPaint.strokeWidth = max(2f, size*0.02f)
    textPaint.maskFilter = null
    textPaint.shader = null
    textPaint.color = Color.WHITE
    c.drawText(text, x, y + size*0.35f/2, textPaint)
  }

  private fun drawBrand(c:Canvas, label:String, x:Float, y:Float, size:Float, neon:Boolean, t:Float){
    brandPaint.textSize = size; brandPaint.textAlign = Paint.Align.CENTER
    if (!neon){
      brandPaint.style = Paint.Style.FILL
      brandPaint.color = Color.argb(64,255,255,255)
      c.drawText(label, x, y, brandPaint); return
    }
    val grad = LinearGradient(x-size, y-size*0.6f, x+size, y+size*0.6f,
      intArrayOf(hsv((t*0.11f)%1f), hsv((t*0.11f+0.5f)%1f)),
      floatArrayOf(0f,1f), Shader.TileMode.CLAMP)
    brandPaint.style = Paint.Style.STROKE
    brandPaint.strokeWidth = size*0.10f
    brandPaint.maskFilter = BlurMaskFilter(size*0.25f, BlurMaskFilter.Blur.NORMAL)
    brandPaint.shader = grad
    c.drawText(label, x, y, brandPaint)
    brandPaint.maskFilter = null
    brandPaint.shader = null
    brandPaint.color = Color.WHITE
    brandPaint.strokeWidth = max(2f, size*0.02f)
    c.drawText(label, x, y, brandPaint)
  }
}
