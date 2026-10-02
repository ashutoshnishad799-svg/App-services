package com.ashuapps.lock

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import java.io.File

/** Transparent activity: silently takes one front-camera photo, saves it privately, closes. Errors are kept for the app. */
class SelfieActivity : ComponentActivity() {
    private fun fail(why: String) { LockStore(this).selfieErr = why.take(160); finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val main = ContextCompat.getMainExecutor(this)
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            runCatching {
                val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
                val provider = future.get()
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, capture)
                Handler(Looper.getMainLooper()).postDelayed({ // short warm-up so exposure settles
                    val dir = File(filesDir, "intruders").apply { mkdirs() }
                    val out = ImageCapture.OutputFileOptions.Builder(File(dir, "${System.currentTimeMillis()}.jpg")).build()
                    capture.takePicture(out, main, object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(r: ImageCapture.OutputFileResults) { LockStore(this@SelfieActivity).selfieErr = ""; finish() }
                        override fun onError(e: ImageCaptureException) { fail("capture: ${e.message}") }
                    })
                }, 900)
            }.onFailure { fail("camera: ${it.message ?: it.javaClass.simpleName}") }
        }, main)
    }
}
