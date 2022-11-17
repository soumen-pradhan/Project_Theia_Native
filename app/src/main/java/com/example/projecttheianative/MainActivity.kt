package com.example.projecttheianative

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import com.example.projecttheianative.databinding.ActivityMainBinding
import org.opencv.android.OpenCVLoader

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    // private val cvLoaded = false // OpenCVLoader.initDebug()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Example of a call to a native method
        binding.sampleText.apply {
            text = stringFromJNI()
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
        }

        private const val TAG = "MainActivity"
    }
}
