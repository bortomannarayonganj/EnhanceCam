package com.example.enhancecam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val SHIFTS = intArrayOf(16, 8, 0)
    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var shutter: Button
    private var imageCapture: ImageCapture? = null
    private val worker = Executors.newSingleThreadExecutor()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) startCamera() else status.text = "ক্যামেরার অনুমতি দিন"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.previewView)
        status = findViewById(R.id.status)
        shutter = findViewById(R.id.shutter)
        shutter.setOnClickListener { takePhoto() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setJpegQuality(100)
                .build()
            provider.unbindAll()
            provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        shutter.isEnabled = false
        status.text = "ছবি তোলা হচ্ছে..."
        capture.takePicture(worker, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    runOnUiThread { status.text = "এনহান্স হচ্ছে, অপেক্ষা করুন..." }
                    val rotation = image.imageInfo.rotationDegrees
                    var bmp = image.toBitmap()
                    image.close()
                    if (rotation != 0) {
                        val m = Matrix().apply { postRotate(rotation.toFloat()) }
                        bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                    }
                    val result = sharpen(colorBoost(bmp), 0.6f)
                    save(result)
                    runOnUiThread { status.text = "সেভ হয়েছে: Pictures/EnhanceCam" }
                } catch (e: Exception) {
                    runOnUiThread { status.text = "সমস্যা: ${e.message}" }
                } finally {
                    runOnUiThread { shutter.isEnabled = true }
                }
            }

            override fun onError(e: ImageCaptureException) {
                runOnUiThread {
                    status.text = "ত্রুটি: ${e.message}"
                    shutter.isEnabled = true
                }
            }
        })
    }

    private fun colorBoost(src: Bitmap): Bitmap {
        val sat = ColorMatrix().apply { setSaturation(1.25f) }
        val c = 1.10f
        val t = (-0.5f * c + 0.5f) * 255f
        val contrast = ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )
        )
        sat.postConcat(contrast)
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(sat) }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private fun sharpen(src: Bitmap, amount: Float): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val out = px.copyOf()
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val c = px[i]
                val u = px[i - w]; val d = px[i + w]
                val l = px[i - 1]; val r = px[i + 1]
                var res = c and (0xFF shl 24)
                for (shift in SHIFTS) {
                    val cc = (c shr shift) and 0xFF
                    val sum = ((u shr shift) and 0xFF) + ((d shr shift) and 0xFF) +
                        ((l shr shift) and 0xFF) + ((r shr shift) and 0xFF)
                    val v = (cc + amount * (4 * cc - sum)).toInt().coerceIn(0, 255)
                    res = res or (v shl shift)
                }
                out[i] = res
            }
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun save(bmp: Bitmap) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "EnhanceCam_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/EnhanceCam")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("সেভ করা যায়নি")
        contentResolver.openOutputStream(uri)?.use {
            bmp.compress(Bitmap.CompressFormat.JPEG, 97, it)
        }
    }
}
