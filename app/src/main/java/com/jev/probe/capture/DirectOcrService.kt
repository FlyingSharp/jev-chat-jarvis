package com.jev.probe.capture

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.WindowManager
import com.jev.probe.capture.ocr.MlKitOcr
import com.jev.probe.overlay.OverlayController
import java.nio.ByteOrder

/** OCR-only capture path. It deliberately has no AccessibilityService base class. */
class DirectOcrService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var projection: MediaProjection
    private lateinit var reader: ImageReader
    private var display: android.hardware.display.VirtualDisplay? = null
    private lateinit var overlay: OverlayController
    private val ocr = MlKitOcr()
    private var busy = false
    private val shot = Runnable { capture() }

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL, "Jev 屏幕 OCR", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Jev 正在使用屏幕 OCR")
            .setContentText("截图只在本机识别，不保存、不上传")
            .setOngoing(true).build()
        startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        overlay = OverlayController(this)
        overlay.showIdle("屏幕 OCR")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = intent?.getIntExtra(EXTRA_RESULT, Activity.RESULT_CANCELED) ?: return START_NOT_STICKY
        val data = intent.parcelable<Intent>(EXTRA_DATA) ?: return START_NOT_STICKY
        val pm = getSystemService(MediaProjectionManager::class.java)
        projection = pm.getMediaProjection(result, data) ?: return START_NOT_STICKY
        val dm = resources.displayMetrics
        reader = ImageReader.newInstance(dm.widthPixels, dm.heightPixels, PixelFormat.RGBA_8888, 2)
        display = projection.createVirtualDisplay("jev-ocr", dm.widthPixels, dm.heightPixels,
            dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, main)
        reader.setOnImageAvailableListener({ capture() }, main)
        main.removeCallbacks(shot); main.post(shot)
        return START_STICKY
    }

    private fun capture() {
        if (busy || !::reader.isInitialized) return
        // Do not feed our own floating panel back into OCR.
        overlay.setHiddenForShot(true)
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: run {
            overlay.setHiddenForShot(false)
            main.postDelayed(shot, 1200); return
        }
        busy = true
        val bmp = imageToBitmap(image); image.close()
        overlay.setHiddenForShot(false)
        val crop = Rect(0, (bmp.height * .12f).toInt(), bmp.width, (bmp.height * .88f).toInt())
        ocr.recognize(bmp, crop) { lines ->
            runCatching { bmp.recycle() }
            val text = lines.joinToString("\n") { it.text }.trim()
            if (text.isNotEmpty()) overlay.showOcrText(text) else overlay.showError("这一屏没有识别到文字")
            busy = false
            main.postDelayed(shot, 1500)
        }
    }

    private fun imageToBitmap(image: android.media.Image): Bitmap {
        val plane = image.planes[0]
        val w = image.width; val h = image.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * w
        val padded = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        return if (padded.width == w) padded else Bitmap.createBitmap(padded, 0, 0, w, h).also { padded.recycle() }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        runCatching { display?.release() }; runCatching { reader.close() }
        runCatching { projection.stop() }
        runCatching { overlay.hide() }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?) = null

    companion object {
        const val EXTRA_RESULT = "result_code"; const val EXTRA_DATA = "result_data"
        private const val CHANNEL = "jev_direct_ocr"; private const val ID = 7
        inline fun <reified T : android.os.Parcelable> Intent.parcelable(key: String): T? =
            if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, T::class.java) else @Suppress("DEPRECATION") getParcelableExtra(key)
    }
}
