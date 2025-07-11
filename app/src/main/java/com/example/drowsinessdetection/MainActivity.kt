package com.example.drowsinessdetection

import android.os.Looper

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaPlayer
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.pow
import kotlin.math.sqrt

import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage

import android.os.Build


class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.SEND_SMS,
            Manifest.permission.VIBRATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        // Drowsiness detection constants
        private const val EAR_THRESHOLD = 0.2f  // Eye Aspect Ratio threshold
        private const val DROWSY_TIME_THRESHOLD = 5000  // 5 seconds
        private const val SOS_COOLDOWN_TIME = 30000  // 30 seconds
    }

    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var alertStatusText: TextView
    private lateinit var manageContactsButton: Button
    private var camera: Camera? = null
    private lateinit var cameraExecutor: ExecutorService

    // Face detection
    private var faceLandmarker: FaceLandmarker? = null

    // State tracking
    private var isDrowsy = false
    private var drowsyStartTime: Long = 0
    private var lastSOSSentTime: Long = 0
    private var isSendingMessages = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.preview_view)
        statusText = findViewById(R.id.status_text)
        alertStatusText = findViewById(R.id.alert_status_text)
        manageContactsButton = findViewById(R.id.manage_contacts_button)

        manageContactsButton.setOnClickListener {
            startActivity(Intent(this, ContactsActivity::class.java))
        }

        // Initialize the FaceLandmarker
        setupFaceLandmarker()

        Utils.initContext(this)

        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        } else {
            Utils.initContext(this)
        }


        // Check and request permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Start detection service
        val serviceIntent = Intent(this, DetectionService::class.java).apply {
            action = DetectionService.ACTION_START_DETECTION
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun setupFaceLandmarker() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("face_landmarker.task")
                .build()

            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumFaces(1)
                .build()

            faceLandmarker = FaceLandmarker.createFromOptions(this, options)
            Log.d(TAG, "FaceLandmarker initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up FaceLandmarker", e)
            Toast.makeText(this, "Failed to setup face detection", Toast.LENGTH_LONG).show()
        }
    }

    private fun startCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this as LifecycleOwner, cameraSelector, preview
                )

                // Set up frame capture
                setupFrameCapture()
            } catch (e: Exception) {
                Log.e(TAG, "Use case binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupFrameCapture() {
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

        val handler = android.os.Handler()
        val frameCapture = object : Runnable {
            override fun run() {
                captureCameraFrame()
                handler.postDelayed(this, 200) // Process at 5 fps to reduce CPU usage
            }
        }

        handler.post(frameCapture)
    }

    private fun captureCameraFrame() {
        val bitmap = previewView.bitmap ?: return

        // Rotate bitmap if needed for landscape mode
        val rotatedBitmap = rotateBitmap(bitmap, 0f) // Adjust rotation angle if needed

        // Process the captured frame
        processFrame(rotatedBitmap)
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degrees)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }



    private fun processFrame(bitmap: Bitmap) {
        val faceLandmarker = faceLandmarker ?: return

        try {
            // Convert Bitmap to MPImage
            val mpImage: MPImage = BitmapImageBuilder(bitmap).build()

            // Detect landmarks
            val result = faceLandmarker.detect(mpImage)

            // Process detection results
            processDetectionResult(result)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame", e)
        }
    }

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    private fun playAlarm() {
        if (mediaPlayer == null) {
            mediaPlayer = MediaPlayer.create(this, R.raw.alarm_sound)
            mediaPlayer?.isLooping = true
        }
        mediaPlayer?.start()
    }

    private fun stopAlarm() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun vibratePhone() {
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 500), 0))
        } else {
            vibrator?.vibrate(1000)
        }
    }

    private fun stopVibration() {
        vibrator?.cancel()
    }



    private fun processDetectionResult(result: FaceLandmarkerResult) {
        if (result.faceLandmarks().isEmpty()) {
            updateStatus("No face detected")
            resetDrowsyState()
            return
        }

        val landmarks = result.faceLandmarks()[0]

        // Calculate Eye Aspect Ratio (EAR)
        val leftEyeEAR = calculateEAR(
            landmarks[33], landmarks[159], landmarks[160],
            landmarks[133], landmarks[144], landmarks[145]
        )

        val rightEyeEAR = calculateEAR(
            landmarks[362], landmarks[386], landmarks[387],
            landmarks[263], landmarks[373], landmarks[374]
        )

        val avgEAR = (leftEyeEAR + rightEyeEAR) / 2

        // Calculate Mouth Aspect Ratio (MAR)
        val mouthMAR = calculateMAR(
            landmarks[13], landmarks[14], landmarks[78],
            landmarks[308], landmarks[61], landmarks[291] // Left and right corners
        )

        // **Drowsiness Detection (Eyes Closed)**
        if (avgEAR < EAR_THRESHOLD) {
            if (!isDrowsy) {
                isDrowsy = true
                drowsyStartTime = System.currentTimeMillis()
                updateStatus("Eyes closed")
            } else {
                val drowsyDuration = System.currentTimeMillis() - drowsyStartTime
                if (drowsyDuration >= 2000) {
                    updateStatus("Drowsy for ${drowsyDuration / 1000} sec")

                    // **Trigger alarm when drowsiness is confirmed**
                    if (mediaPlayer?.isPlaying != true) {
                        playAlarm()
                        vibratePhone()
                    }

                    if (drowsyDuration >= DROWSY_TIME_THRESHOLD) {
                        handleDrowsyState(drowsyDuration)
                    }
                }
            }
        } else {
            resetDrowsyState()
            updateStatus("Alert - EAR: ${String.format("%.2f", avgEAR)}")
        }

        // **Yawning Detection (Mouth Open)**
        if (mouthMAR > 0.5) {  // Adjust threshold for better detection
            if (!isDrowsy) {
                isDrowsy = true
                drowsyStartTime = System.currentTimeMillis()
                updateStatus("Yawning detected")

                // **Trigger alarm immediately when yawning is detected**
                playAlarm()
                vibratePhone()
            } else {
                val yawnDuration = System.currentTimeMillis() - drowsyStartTime
                if (yawnDuration >= 2000) {  // Ignore short yawns
                    updateStatus("Yawning for ${yawnDuration / 1000} sec")

                    if (mediaPlayer?.isPlaying != true) {
                        playAlarm()
                        vibratePhone()
                    }

                    if (yawnDuration >= DROWSY_TIME_THRESHOLD) {
                        handleDrowsyState(yawnDuration)
                    }
                }
            }
        }
    }




    private fun resetDrowsyState() {
        isDrowsy = false
        stopAlarm()
        stopVibration()
    }

    private fun calculateMAR(
        p1: com.google.mediapipe.tasks.components.containers.NormalizedLandmark, // Upper lip top
        p2: com.google.mediapipe.tasks.components.containers.NormalizedLandmark, // Lower lip bottom
        p3: com.google.mediapipe.tasks.components.containers.NormalizedLandmark, // Upper lip bottom
        p4: com.google.mediapipe.tasks.components.containers.NormalizedLandmark, // Lower lip top
        p5: com.google.mediapipe.tasks.components.containers.NormalizedLandmark, // Left mouth corner
        p6: com.google.mediapipe.tasks.components.containers.NormalizedLandmark  // Right mouth corner
    ): Float {
        // Calculate vertical mouth opening
        val vertDistance1 = euclideanDistance(p1, p2)
        val vertDistance2 = euclideanDistance(p3, p4)

        // Calculate horizontal mouth width
        val horzDistance = euclideanDistance(p5, p6)

        // MAR formula
        return (vertDistance1 + vertDistance2) / (2.0f * horzDistance)
    }



    private fun calculateEAR(
        p1: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        p2: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        p3: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        p4: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        p5: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        p6: com.google.mediapipe.tasks.components.containers.NormalizedLandmark
    ): Float {
        // Calculate vertical distances
        val vertDistance1 = euclideanDistance(p2, p6)
        val vertDistance2 = euclideanDistance(p3, p5)

        // Calculate horizontal distance
        val horzDistance = euclideanDistance(p1, p4)

        // EAR formula
        return (vertDistance1 + vertDistance2) / (2.0f * horzDistance)
    }


    private fun euclideanDistance(p1: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
                                  p2: com.google.mediapipe.tasks.components.containers.NormalizedLandmark): Float {
        // Calculate distance between two points
        return sqrt(
            (p1.x() - p2.x()).pow(2) +
                    (p1.y() - p2.y()).pow(2)
        )
    }

    private fun handleDrowsyState(drowsyDuration: Long) {
        val timeSinceLastSOS = System.currentTimeMillis() - lastSOSSentTime

        if (!isSendingMessages && timeSinceLastSOS > SOS_COOLDOWN_TIME) {
            sendSOSMessages(drowsyDuration)
            updateAlertStatus("Sending SOS...", R.color.warning_yellow)
        } else if (timeSinceLastSOS <= SOS_COOLDOWN_TIME) {
            updateAlertStatus("SOS cooldown: ${(SOS_COOLDOWN_TIME - timeSinceLastSOS) / 1000}s", R.color.warning_yellow)
        }
    }


    private fun sendSOSMessages(drowsyDuration: Long) {
        val contacts = Utils.getContacts(this)
        if (contacts.isEmpty()) {
            updateAlertStatus("No emergency contacts found", R.color.error_red)
            return
        }

        isSendingMessages = true
        lastSOSSentTime = System.currentTimeMillis()

        val timestamp = Utils.formatTimestamp(System.currentTimeMillis())

        // 🔹 Get the latest location before sending SOS
        Utils.getLocationInfo { locationInfo ->

            val message = "EMERGENCY ALERT: Driver drowsiness detected for ${drowsyDuration / 1000} seconds at $timestamp. $locationInfo"

            // Vibration and Alert Sound
            vibratePhone()
            playAlarm()

            // Update UI
            updateStatus("DROWSY ALERT! WAKE UP!\nSOS Sent")
            updateAlertStatus("Sending SOS messages...", R.color.warning_yellow)

            Thread {
                try {
                    for (contact in contacts) {
                        Utils.sendSMS(contact.phoneNumber, message)
                        Thread.sleep(500)
                    }

                    runOnUiThread {
                        updateAlertStatus("SOS Sent Successfully", R.color.success_green)
                        updateStatus("DROWSY ALERT! WAKE UP!\nSOS Sent")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending SOS messages", e)
                    runOnUiThread {
                        updateAlertStatus("Failed to send SOS messages", R.color.error_red)
                    }
                } finally {
                    isSendingMessages = false
                }
            }.start()
        }
    }




    private fun updateStatus(message: String) {
        runOnUiThread {
            statusText.text = message
        }
    }

    private fun updateAlertStatus(message: String, colorResId: Int) {
        runOnUiThread {
            alertStatusText.text = message
            alertStatusText.setTextColor(ContextCompat.getColor(this, colorResId))
        }
    }


    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        faceLandmarker?.close()

        // Stop detection service
        val serviceIntent = Intent(this, DetectionService::class.java).apply {
            action = DetectionService.ACTION_STOP_DETECTION
        }
        startService(serviceIntent)
    }
}