package io.github.antwhale.antwhale_zoomable_camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.antwhale.antwhale_zoomable_camera.utils.OrientationLiveData
import io.github.antwhale.antwhale_zoomable_camera.utils.getPreviewOutputSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.NullPointerException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.sqrt

abstract class AntwhaleZoomableCameraActivity : AppCompatActivity() {
    val TAG = AntwhaleZoomableCameraActivity::class.java.simpleName

    private var lensOrientation = 0
    private var imageFormat = ImageFormat.JPEG
    private lateinit var cameraID: String

    lateinit var antwhaleZoomableCameraCharacteristics: CameraCharacteristics
    private lateinit var antwhaleZoomableCameraView: AntwhaleZoomableCameraView

    private val cameraManager: CameraManager by lazy {
        getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private lateinit var imageReader: ImageReader

    private val cameraThread = HandlerThread("CameraThread").apply { start() }

    private val cameraHandler = Handler(cameraThread.looper)

    private val imageReaderThread = HandlerThread("imageReaderThread").apply { start() }

    private val imageReaderHandler = Handler(imageReaderThread.looper)

    private lateinit var camera: CameraDevice

    private lateinit var session: CameraCaptureSession

    private lateinit var captureRequest: CaptureRequest.Builder
    private lateinit var captureCallback: CameraCaptureSession.CaptureCallback

    /** Live data listener for changes in the device orientation relative to the camera */
    private lateinit var relativeOrientation: OrientationLiveData

    //Zooming
    var fingerSpacing = 0f
    var zoomLevel = 1f
    var maximumZoomLevel = 0f
    var zoomRect: Rect? = null

    private val _cameraSortState = MutableStateFlow(CameraSort.WIDE_ANGLE_CAMERA)
    val cameraSortState: StateFlow<CameraSort> = _cameraSortState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

    }

    fun initializeAntwhaleZoomableCamera() = lifecycleScope.launch(Dispatchers.Main) {
        Log.d(TAG, "initializeAntwhaleZoomableCamera")

        // Open the selected camera
        camera = openCamera(cameraManager, cameraID, cameraHandler)

        // Initialize an image reader which will be used to capture still photos
        val size = antwhaleZoomableCameraCharacteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(imageFormat).maxByOrNull { it.height * it.width }!!

        imageReader = ImageReader.newInstance(
            size.width, size.height, imageFormat, IMAGE_BUFFER_SIZE)

        // Creates list of Surfaces where the camera will output frames
        val targets = listOf(antwhaleZoomableCameraView.holder.surface, imageReader.surface)

        // Start a capture session using our open camera and list of Surfaces where frames will go
        session = createCaptureSession(camera, targets, cameraHandler)

        captureRequest = camera.createCaptureRequest(
            CameraDevice.TEMPLATE_PREVIEW)
            .apply { addTarget(antwhaleZoomableCameraView.holder.surface) }

        // This will keep sending the capture request as frequently as possible until the
        // session is torn down or session.stopRepeating() is called
        session.setRepeatingRequest(captureRequest.build(), null, cameraHandler)

    }

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
        manager: CameraManager,
        cameraId: String,
        handler: Handler? = null,
    ): CameraDevice = suspendCancellableCoroutine { cont ->
        Log.d(TAG, "openCamera")

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) = cont.resume(device)

            override fun onDisconnected(device: CameraDevice) {
                Log.w(TAG, "Camera $cameraId has been disconnected")
                finish()
            }

            override fun onError(device: CameraDevice, error: Int) {
                val msg = when (error) {
                    ERROR_CAMERA_DEVICE -> "Fatal (device)"
                    ERROR_CAMERA_DISABLED -> "Device policy"
                    ERROR_CAMERA_IN_USE -> "Camera in use"
                    ERROR_CAMERA_SERVICE -> "Fatal (service)"
                    ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                    else -> "Unknown"
                }
                val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
                Log.e(TAG, exc.message, exc)
                if (cont.isActive) cont.resumeWithException(exc)
            }
        }, handler)
    }

    /**
     * Starts a [CameraCaptureSession] and returns the configured session (as the result of the
     * suspend coroutine
     */
    private suspend fun createCaptureSession(
        device: CameraDevice,
        targets: List<Surface>,
        handler: Handler? = null,
    ): CameraCaptureSession = suspendCoroutine { cont ->
        Log.d(TAG, "createCaptureSession")

        // Create a capture session using the predefined targets; this also involves defining the
        // session state callback to be notified of when the session is ready
        device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {

            override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)

            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exc = RuntimeException("Camera ${device.id} session configuration failed")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }
        }, handler)
    }

    private fun enumerateCameras(): String {
        Log.d(TAG, "enumerateCameras")

        val cameraIds = cameraManager.cameraIdList.filter {
            val characteristics = cameraManager.getCameraCharacteristics(it)
            characteristics.get(CameraCharacteristics.LENS_FACING) == lensOrientation
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && imageFormat == ImageFormat.DEPTH_JPEG) {

            cameraIds.forEach { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)

                // Query the available capabilities and output formats
                val capabilities = characteristics.get(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)!!
                val outputFormats = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.outputFormats

                // Return cameras that support JPEG DEPTH capability
                if (capabilities.contains(
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT) &&
                    outputFormats.contains(ImageFormat.DEPTH_JPEG)
                ) {

                    Log.d(TAG, "selected cameraID: $id")
                    return id
                }
            }
        }
//        val id = cameraIds.first()
        val id = cameraIds.get(1)
        Log.d(TAG, "selected cameraID: $id")
        return id
    }

    @SuppressLint("ClickableViewAccessibility")
    fun startAntwhaleZoomalbeCamera(
        viewFinder: AntwhaleZoomableCameraView,
        orientation: Int,
        format: Int,
    ) {
        Log.d(TAG, "setAntwhaleZoomableCameraCondition")

        antwhaleZoomableCameraView = viewFinder
        antwhaleZoomableCameraView.visibility = View.GONE
        lensOrientation = orientation
        imageFormat = format

        cameraID = enumerateCameras()
        antwhaleZoomableCameraCharacteristics = cameraManager.getCameraCharacteristics(cameraID)

        antwhaleZoomableCameraView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.d(TAG, "surfaceCreated")

                val previewSize = getPreviewOutputSize(
                    antwhaleZoomableCameraView.display,
                    antwhaleZoomableCameraCharacteristics,
                    SurfaceHolder::class.java
                )

                Log.d(TAG,
                    "View finder size: ${antwhaleZoomableCameraView.width} x ${antwhaleZoomableCameraView.height}")
                Log.d(TAG, "Selected preview size: $previewSize")

                antwhaleZoomableCameraView.setAspectRatio(previewSize.width, previewSize.height)

                antwhaleZoomableCameraView.post { initializeAntwhaleZoomableCamera() }
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int,
            ) {
                Log.d(TAG, "surfaceChanged")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.d(TAG, "surfaceDestroyed")
            }
        })
        antwhaleZoomableCameraView.visibility = View.VISIBLE

        antwhaleZoomableCameraView.setOnTouchListener { view, motionEvent ->
            pinchZoom(motionEvent)
        }
    }

    private fun pinchZoom(event: MotionEvent): Boolean {
        try {
            Log.d(TAG, "pinchZoom")

            val characteristics = cameraManager.getCameraCharacteristics(cameraID)
            val maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)!! * 7

            val m = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
//            m?.let { return false }

            var current_finger_spacing: Float

            if (event.pointerCount > 1) {
                Log.d(TAG, "pointerCount : 2")
                // Multi touch logic
                current_finger_spacing = getFingerSpacing(event)
                if(fingerSpacing != 0f) {
                    if(current_finger_spacing > fingerSpacing + 3f && maxZoom > zoomLevel){
                        zoomLevel += 0.5f
                    } else if (current_finger_spacing < fingerSpacing - 3f && zoomLevel > 1) {
                        zoomLevel -= 0.5f
                    }
                    Log.d(TAG, "zoomLevel: $zoomLevel")

                    val minW: Int = (m!!.width() / maxZoom).toInt()
                    val minH: Int = (m.height() / maxZoom).toInt()
                    val difW = m.width() - minW
                    val difH = m.height() - minH
                    var cropW: Int = difW / 100 * zoomLevel.toInt()
                    var cropH: Int = difH / 100 * zoomLevel.toInt()
                    cropW -= cropW and 3
                    cropH -= cropH and 3

                    zoomRect = Rect(cropW, cropH, m.width() - cropW, m.height() - cropH)
                    captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        .apply { addTarget(antwhaleZoomableCameraView.holder.surface) }
                    captureRequest.set(CaptureRequest.SCALER_CROP_REGION, zoomRect)
                }
                fingerSpacing = current_finger_spacing
            } else {
                return true
            }

            try {
                captureRequest.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                session.setRepeatingRequest(captureRequest.build(),
                    null,
                    cameraHandler)
                return true
            } catch (e: CameraAccessException) {
                e.printStackTrace()
                return true
            } catch (ex: NullPointerException) {
                ex.printStackTrace()
                return true
            }
        } catch (e: CameraAccessException) {
            throw RuntimeException("can not access camera.", e)
        }

    }

    private fun getFingerSpacing(event: MotionEvent): Float {
        val x = event.getX(0) - event.getX(1);
        val y = event.getY(0) - event.getY(1);
        return sqrt(x * x + y * y)
    }

    suspend fun takePhoto(): Image = suspendCoroutine { cont ->
        @Suppress("ControlFlowWithEmptyBody")
        while (imageReader.acquireNextImage() != null) {
        }

        // Start a new image queue
        val imageQueue = ArrayBlockingQueue<Image>(IMAGE_BUFFER_SIZE)
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage()
            Log.d(TAG, "Image available in queue: ${image.timestamp}")
            imageQueue.add(image)
        }, imageReaderHandler)

        val captureRequest = session.device.createCaptureRequest(
            CameraDevice.TEMPLATE_STILL_CAPTURE).apply { addTarget(imageReader.surface) }

        //프리뷰 확대 축소한 화면을 촬영
        zoomRect?.let {
            captureRequest.set(CaptureRequest.SCALER_CROP_REGION, it)
        }

        captureCallback = object : CameraCaptureSession.CaptureCallback() {

            override fun onCaptureStarted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                timestamp: Long,
                frameNumber: Long,
            ) {
                super.onCaptureStarted(session, request, timestamp, frameNumber)
                Log.d(TAG, "onCaptureStarted")
//                fragmentCameraBinding.viewFinder.post(animationTask)
            }

            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                super.onCaptureCompleted(session, request, result)
                val resultTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                Log.d(TAG, "Capture result received: $resultTimestamp")

                // Set a timeout in case image captured is dropped from the pipeline
                val exc = TimeoutException("Image dequeuing took too long")
                val timeoutRunnable = Runnable { cont.resumeWithException(exc) }
                imageReaderHandler.postDelayed(timeoutRunnable, IMAGE_CAPTURE_TIMEOUT_MILLIS)

                // Loop in the coroutine's context until an image with matching timestamp comes
                // We need to launch the coroutine context again because the callback is done in
                //  the handler provided to the `capture` method, not in our coroutine context
                @Suppress("BlockingMethodInNonBlockingContext")
                lifecycleScope.launch(cont.context) {
                    while (true) {

                        // Dequeue images while timestamps don't match
                        val image = imageQueue.take()

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            image.format != ImageFormat.DEPTH_JPEG &&
                            image.timestamp != resultTimestamp
                        ) continue
                        Log.d(TAG, "Matching image dequeued: ${image.timestamp}")

                        // Unset the image reader listener
                        imageReaderHandler.removeCallbacks(timeoutRunnable)
                        imageReader.setOnImageAvailableListener(null, null)

                        // Clear the queue of images, if there are left
                        while (imageQueue.size > 0) {
                            imageQueue.take().close()
                        }

                        // Compute EXIF orientation metadata
                        /*val rotation = relativeOrientation.value ?: 0
                        val mirrored = antwhaleZoomableCameraCharacteristics.get(CameraCharacteristics.LENS_FACING) ==
                                CameraCharacteristics.LENS_FACING_FRONT
                        val exifOrientation = computeExifOrientation(rotation, mirrored)*/

                        // Build the result and resume progress
                        cont.resume(image)

                        // There is no need to break out of the loop, this coroutine will suspend
                    }
                }
            }
        }

        session.capture(captureRequest.build(), captureCallback, cameraHandler)
    }

    suspend fun saveAntwhaleZoomableCameraImage(image: Image, path: String): File =
        suspendCoroutine { cont ->
            when (image.format) {

                // When the format is JPEG or DEPTH JPEG we can simply save the bytes as-is
                ImageFormat.JPEG, ImageFormat.DEPTH_JPEG -> {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining()).apply { buffer.get(this) }
                    try {
                        val output = createFile(this, path, "jpg")
                        FileOutputStream(output).use { it.write(bytes) }
                        cont.resume(output)
                    } catch (exc: IOException) {
                        Log.e(TAG, "Unable to write JPEG image to file", exc)
                        cont.resumeWithException(exc)
                    }
                }

                // When the format is RAW we use the DngCreator utility library
                /*ImageFormat.RAW_SENSOR -> {
                    val dngCreator = DngCreator(characteristics, result.metadata)
                    try {
                        val output = createFile(requireContext(), "dng")
                        FileOutputStream(output).use { dngCreator.writeImage(it, result.image) }
                        cont.resume(output)
                    } catch (exc: IOException) {
                        Log.e(TAG, "Unable to write DNG image to file", exc)
                        cont.resumeWithException(exc)
                    }
                }*/

                // No other formats are supported by this sample
                else -> {
                    val exc = RuntimeException("Unknown image format: ${image.format}")
                    Log.e(TAG, exc.message, exc)
                    cont.resumeWithException(exc)
                }
            }
        }

    enum class CameraSort {
        WIDE_ANGLE_CAMERA, DEFAULT_CAMERA
    }

    companion object {

        private const val IMAGE_BUFFER_SIZE = 1
        private const val IMAGE_CAPTURE_TIMEOUT_MILLIS: Long = 5000


        protected fun createFile(context: Context, path: String, extension: String): File {
            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.getDefault())
            return File(File(path), "IMG_${sdf.format(Date())}.$extension")
        }
    }
}