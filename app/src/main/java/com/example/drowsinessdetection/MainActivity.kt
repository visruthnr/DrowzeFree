package com.example.drowsinessdetection

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

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.SEND_SMS,
            Manifest.permission.VIBRATE
        )
        
        // Drowsiness detection constants
        private const val EAR_THRESHOLD = 0.2f  // Eye Aspect Ratio threshold
        private const val DROWSY_TIME_THRESHOLD = 4000  // 4 seconds
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
            // Convert bitmap to MP Image
            val mpImage = com.google.mediapipe.tasks.vision.core.ImageProcessingOptions.builder()
                .build()
            
            // Detect landmarks
            val result = faceLandmarker.detect(bitmap, mpImage)
            
            // Process detection results
            processDetectionResult(result)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame", e)
        }
    }
    
    private fun processDetectionResult(result: FaceLandmarkerResult) {
        if (result.faceLandmarks().isEmpty()) {
            updateStatus("No face detected")
            resetDrowsyState()
            return
        }
        
        val landmarks = result.faceLandmarks()[0]
        
        // Calculate EAR for both eyes
        val leftEyeEAR = calculateEAR(
            landmarks[159], // Left eye landmarks
            landmarks[145],
            landmarks[33],
            landmarks[133]
        )
        
        val rightEyeEAR = calculateEAR(
            landmarks[386], // Right eye landmarks
            landmarks[374],
            landmarks[362],
            landmarks[263]
        )
        
        // Average EAR from both eyes
        val avgEAR = (leftEyeEAR + rightEyeEAR) / 2
        
        // Check for drowsiness
        if (avgEAR < EAR_THRESHOLD) {
            // Eyes are closed or nearly closed
            if (!isDrowsy) {
                isDrowsy = true
                drowsyStartTime = System.currentTimeMillis()
                updateStatus("Eyes closed")
            } else {
                // Check if drowsy for too long
                val drowsyDuration = System.currentTimeMillis() - drowsyStartTime
                updateStatus("Eyes closed for ${drowsyDuration / 1000} seconds")
                
                if (drowsyDuration >= DROWSY_TIME_THRESHOLD) {
                    // Driver is drowsy for too long
                    handleDrowsyState(drowsyDuration)
                }
            }
        } else {
            // Eyes are open
            resetDrowsyState()
            updateStatus("Alert - EAR: ${String.format("%.2f", avgEAR)}")
        }
    }
    
    private fun calculateEAR(p1: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
                           p2: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
                           p3: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
                           p4: com.google.mediapipe.tasks.components.containers.NormalizedLandmark): Float {
        // Calculate Euclidean distance between points
        val vertDistance = euclideanDistance(p1, p3) + euclideanDistance(p2, p4)
        val horzDistance = euclideanDistance(p1, p4)
        
        // EAR formula: height/width ratio
        return vertDistance / (2.0f * horzDistance)
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
        // Check if we should send SOS
        val timeSinceLastSOS = System.currentTimeMillis() - lastSOSSentTime
        
        if (!isSendingMessages && timeSinceLastSOS > SOS_COOLDOWN_TIME) {
            sendSOSMessages(drowsyDuration)
        }
    }
    
    private fun sendSOSMessages(drowsyDuration: Long) {
        // Get emergency contacts
        val contacts = Utils.getContacts(this)
        
        if (contacts.isEmpty()) {
            updateAlertStatus("No emergency contacts found", R.color.error_red)
            return
        }
        
        isSendingMessages = true
        lastSOSSentTime = System.currentTimeMillis()
        
        // Generate SOS message
        val timestamp = Utils.formatTimestamp(System.currentTimeMillis())
        val locationInfo = Utils.getLocationInfo()
        val message = "EMERGENCY ALERT: Driver drowsiness detected for ${drowsyDuration / 1000} seconds at $timestamp. $locationInfo"
        
        // Create sound and vibration alerts
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (vibrator.hasVibrator()) {
            val vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(vibrationPattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(vibrationPattern, -1)
            }
        }
        
        // Play alert sound
        val mediaPlayer = MediaPlayer.create(this, R.raw.alert_sound)
        mediaPlayer.start()
        mediaPlayer.setOnCompletionListener { it.release() }
        
        // Update UI
        updateAlertStatus("Sending SOS messages...", R.color.warning_yellow)
        
        // Send messages in background
        Thread {
            try {
                for (contact in contacts) {
                    Utils.sendSMS(contact.phoneNumber, message)
                    Thread.sleep(500) // Brief pause between messages
                }
                
                // Update UI after all messages sent
                runOnUiThread {
                    updateAlertStatus("SOS messages sent to ${contacts.size} contacts", R.color.success_green)
                    
                    // Reset state after a delay
                    android.os.Handler().postDelayed({
                        if (isSendingMessages) {
                            isSendingMessages = false
                            updateAlertStatus("SOS system ready", android.R.color.darker_gray)
                        }
                    }, 5000)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending SOS messages", e)
                runOnUiThread {
                    updateAlertStatus("Failed to send SOS messages: ${e.message}", R.color.error_red)
                    isSendingMessages = false
                }
            }
        }.start()
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
    
    private fun resetDrowsyState() {
        isDrowsy = false
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