package com.example.projecttheianative

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.projecttheianative.databinding.ActivityMainBinding
import org.opencv.android.OpenCVLoader

class MainActivity : AppCompatActivity() {

    private lateinit var id: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        id = ActivityMainBinding.inflate(layoutInflater)
        setContentView(id.root)

        // Check Permissions
        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERM, REQUEST_CODE_PERM
            )
        }

        // Example of a call to a native method
        id.cameraView.apply {
            maxPreviewSize = Size(800, 500)
            fpsMeasure = FpsMeasure()
            // frameListener = { darkChannel(it) }
        }.also {
            lifecycle.addObserver(it)
        }
    }

    override fun onStart() {
        super.onStart()
        hideSystemBar(id.cameraView)
    }

    private fun allPermissionsGranted() = REQUIRED_PERM.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun hideSystemBar(view: View) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, view).run {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * A native method that is implemented by the 'projecttheianative' native library,
     * which is packaged with this application.
     */
    private external fun stringFromJNI(): String

    companion object {
        // Used to load the 'projecttheianative' library on application startup.
        init {
            System.loadLibrary("projecttheianative")
            OpenCVLoader.initDebug().trueOrNull() ?: throw CvCamera2Exception("OpenCV not loaded")
        }

        private const val TAG = "MainActivity"

        const val REQUEST_CODE_PERM = 10
        val REQUIRED_PERM = listOf(Manifest.permission.CAMERA).toTypedArray()
    }
}
