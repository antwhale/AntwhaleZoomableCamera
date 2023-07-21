package com.antwhale.sample.camera

import android.Manifest
import android.graphics.ImageFormat
import android.os.Bundle
import android.util.Log
import android.widget.ImageButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import io.github.antwhale.antwhale_zoomable_camera.AntwhaleZoomableCameraActivity
import io.github.antwhale.antwhale_zoomable_camera.AntwhaleZoomableCameraView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


class MainActivity : AntwhaleZoomableCameraActivity() {
    private lateinit var viewFinder: AntwhaleZoomableCameraView
    private lateinit var captureButton : ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.view_finder)
        captureButton = findViewById(R.id.capture_button)

        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)

        captureButton.setOnClickListener {
            // Disable click listener to prevent multiple requests simultaneously in flight
            it.isEnabled = false

            lifecycleScope.launch(Dispatchers.IO) {
                takePhoto().use { image ->
                    val imgFile = saveAntwhaleZoomableCameraImage(image, filesDir.absolutePath)
                    if(imgFile.exists()) {
                        Log.d(TAG, "imgFile exists")
                    } else {
                        Log.d(TAG, "imgFile not exists")
                    }

                }
            }
        }
    }

    var cameraPermissionLauncher = registerForActivityResult<String, Boolean>(ActivityResultContracts.RequestPermission()
        ) { result: Boolean ->
            if (result) {

                //code to run when camera permission granted
                //0 is LENS_FACING_FRONT, 1 is LENS_FACING_BACK
                //You can choose ImageForamt between JPEG and DEPTH_JPEG
                startAntwhaleZoomalbeCamera(viewFinder, 1, ImageFormat.JPEG)
            }
        }


}