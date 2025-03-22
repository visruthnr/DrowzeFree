package com.example.drowsinessdetection

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mediapipe.solutions.facemesh.FaceMesh
import com.google.mediapipe.solutions.facemesh.FaceMeshOptions
import com.google.mediapipe.solutions.facemesh.FaceMeshResult
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.android.material.button.MaterialButton
import android.view.SurfaceView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.google.mediapipe.framework.AndroidPacketCreator
import com.google.mediapipe.framework.Packet
import com.google.mediapipe.framework.PacketGetter
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import android.hardware.camera2.CameraManager
import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import com.google.common.util.concurrent.ListenableFuture

class MainActivity : AppCompatActivity() {

    private lateinit var cameraPreview: PreviewView
    private lateinit var detectionStatusText: TextView
    private lateinit var drowsinessCounter: TextView
    private lateinit var detectionSwitch: SwitchCompat
    private lateinit var manageContactsButton: MaterialButton
    private lateinit var alertStatusText: TextView
    private lateinit var statusIndicator: ImageView

    private val PERMISSIONS_REQUEST_CODE = 101
    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_CONTACTS
    )

    private var faceMesh: FaceMesh? = null
    private var isDrowsy = false
    private var drowsyStartTime = 0L
    private var isSendingSOS = false
    private var isDrowsinessDetectionRunning = false
    private var drowsySeconds = 0

    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var drowsinessChecker: ScheduledExecutorService? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cameraPreview = findViewById(R.id.camera_preview)
        detectionStatusText = findViewById(R.id.detection_status)
        drowsinessCounter = findViewById(R.id.drowsiness_counter)
        detectionSwitch = findViewById(R.id.detection_switch)
        manageContactsButton = findViewById(R.id.manage_contacts_button)
        alertStatusText = findViewById(R.id.alert_status)
        statusIndicator = findViewById(R.id.status_indicator)

        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSIONS_REQUEST_CODE)
        }

        detectionSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startDrowsinessDetection()
            } else {
                stopDrowsinessDetection()
            }
        }

        manageContactsButton.setOnClickListener {
            startActivity(Intent(this, ContactsActivity::class.java))
        }

        // Initialize drowsiness checker that runs every 500ms
        drowsinessChecker = Executors.newScheduledThreadPool(1)
        drowsinessChecker?.scheduleAtFixedRate({
            if (isDrowsy) {
                if (drowsyStartTime == 0L) {
                    drowsyStartTime = System.currentTimeMillis()
                }
                
                val currentTime = System.currentTimeMillis()
                drowsySeconds = ((currentTime - drowsyStartTime) / 1000).toInt()
                
                runOnUiThread {
                    drowsinessCounter.text = "Drowsy for $drowsySeconds seconds"
                    
                    // Update UI based on drowsiness state
                    if (drowsySeconds >= 4 && !isSendingSOS) {
                        sendSOSMessages()
                        statusIndicator.setBackgroundResource(android.R.color.holo_red_dark)
                    } else if (drowsySeconds >= 2) {
                        statusIndicator.setBackgroundResource(android.R.color.holo_orange_light)
                    } else {
                        statusIndicator.setBackgroundResource(android.R.color.holo_green_light)
                    }
                }
            } else {
                drowsyStartTime = 0L
                drowsySeconds = 0
                runOnUiThread {
                    drowsinessCounter.text = "Awake"
                    statusIndicator.setBackgroundResource(android.R.color.holo_green_light)
                }
            }
        }, 0, 500, TimeUnit.MILLISECONDS)
    }

    private fun startDrowsinessDetection() {
        if (isDrowsinessDetectionRunning) return
        
        if (allPermissionsGranted()) {
            isDrowsinessDetectionRunning = true
            startCamera()
            detectionStatusText.text = "Detection Active"
        } else {
            detectionSwitch.isChecked = false
            Toast.makeText(this, "Permissions are required to use this feature", Toast.LENGTH_LONG).show()
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSIONS_REQUEST_CODE)
        }
    }

    private fun stopDrowsinessDetection() {
        isDrowsinessDetectionRunning = false
        faceMesh?.close()
        detectionStatusText.text = "Detection Inactive"
        drowsinessCounter.text = "Awake"
        statusIndicator.setBackgroundResource(android.R.color.darker_gray)
    }

    private fun startCamera() {
        // First, set up faceMesh before camera setup
        setupFaceMesh()
        
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                
                // Create preview use case
                val preview = Preview.Builder()
                    .setTargetRotation(cameraPreview.display.rotation)
                    .build()
                
                // Set the surface provider for the preview
                preview.setSurfaceProvider(cameraPreview.surfaceProvider)
                
                // Define the image analysis use case to feed frames to MediaPipe
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetRotation(cameraPreview.display.rotation)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                
                // Set up analyzer to process frames
                imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                    try {
                        if (faceMesh != null) {
                            // Get timestamp for the frame
                            val frameTime = System.currentTimeMillis()
                            
                            // Convert ImageProxy to Bitmap for FaceMesh processing
                            val bitmap = imageProxyToBitmap(imageProxy)
                            if (bitmap != null) {
                                // Process the frame with FaceMesh
                                faceMesh?.send(bitmap)
                                Log.d("MainActivity", "Frame processed at $frameTime")
                                bitmap.recycle() // Recycle bitmap to prevent memory leaks
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error processing camera frame", e)
                    } finally {
                        // Always close the imageProxy to release the frame
                        imageProxy.close()
                    }
                }
                
                // Unbind previous use cases before rebinding
                cameraProvider.unbindAll()
                
                // Bind the camera use cases to lifecycle
                val camera = cameraProvider.bindToLifecycle(
                    this, 
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    imageAnalysis
                )
                
                Log.d("MainActivity", "Camera setup completed successfully")
            } catch (e: Exception) {
                Log.e("MainActivity", "Camera binding failed", e)
                runOnUiThread {
                    Toast.makeText(this, "Failed to start camera: ${e.message}", Toast.LENGTH_LONG).show()
                    detectionSwitch.isChecked = false
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }
    
    // Helper function to convert ImageProxy to Bitmap for MediaPipe
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer
        
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        
        val nv21 = ByteArray(ySize + uSize + vSize)
        
        // U and V are swapped
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 100, out)
        val imageBytes = out.toByteArray()
        
        try {
            return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error converting image to bitmap", e)
            return null
        }
    }

    private fun setupFaceMesh() {
        try {
            // Create options for FaceMesh with optimal parameters
            val options = FaceMeshOptions.builder()
                .setStaticImageMode(false)      // We're processing video frames
                .setRefineLandmarks(true)       // Get more precise landmarks
                .setMaxNumFaces(1)              // Focus on just the driver
                .setRunOnGpu(true)              // Use GPU for better performance
                .build()
            
            // Initialize FaceMesh with options
            faceMesh = FaceMesh(this, options)
            
            // Load the model from assets
            // This ensures we're using our custom model
            val assetManager = assets
            val modelPath = "face_landmarker.task"
            
            // Log model loading attempt
            Log.d("MainActivity", "Loading FaceMesh model from assets: $modelPath")
            
            // Point the faceMesh to use our custom model
            if (assetManager.list("")?.contains(modelPath) == true) {
                // Load the model file from assets
                assetManager.open(modelPath).use { inputStream ->
                    val modelFile = File(cacheDir, "face_landmarker.task")
                    FileOutputStream(modelFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                    // Initialize FaceMesh with the model file
                    val baseOptions = BaseOptions.builder()
                        .setModelAssetPath("face_landmarker.task")
                        .build()
                    val options = FaceMeshOptions.builder()
                        .setBaseOptions(baseOptions)
                        .build()
                    faceMesh = FaceMesh.create(this, options)
                    Log.d("MainActivity", "FaceMesh model loaded successfully")
                }
            } else {
                Log.e("MainActivity", "FaceMesh model not found in assets")
            }
            
            // Set up result listener with enhanced error handling
            faceMesh?.setResultListener { faceMeshResult ->
                if (faceMeshResult != null) {
                    val faceCount = faceMeshResult.multiFaceLandmarks().size
                    Log.d("MainActivity", "Received face mesh result: $faceCount faces")
                    processFaceMeshResult(faceMeshResult)
                } else {
                    Log.e("MainActivity", "Received null FaceMesh result")
                    runOnUiThread {
                        detectionStatusText.text = "Detection error"
                    }
                }
            }
            
            // Set error listener with descriptive logging
            faceMesh?.setErrorListener { message, e ->
                Log.e("MainActivity", "MediaPipe Face Mesh error: $message", e)
                runOnUiThread {
                    detectionStatusText.text = "Detection error: ${e?.message ?: message}"
                }
            }
            
            Log.d("MainActivity", "FaceMesh setup completed")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to setup FaceMesh", e)
            runOnUiThread {
                Toast.makeText(this, "Failed to initialize drowsiness detection: ${e.message}", Toast.LENGTH_LONG).show()
                detectionSwitch.isChecked = false
            }
        }
    }

    private fun processFaceMeshResult(result: FaceMeshResult) {
        try {
            if (result.multiFaceLandmarks().isEmpty()) {
                // No face detected
                Log.d("MainActivity", "No face detected in FaceMesh result")
                runOnUiThread {
                    detectionStatusText.text = "No face detected"
                    isDrowsy = false
                }
                return
            }
            
            // Get landmarks for the first face
            val landmarks = result.multiFaceLandmarks()[0].landmarkList
            Log.d("MainActivity", "Processing ${landmarks.size} face landmarks")
            
            // Using MediaPipe FaceMesh landmark indices
            // Left eye points (landmarks 362, 385, 387, 373, 380, 374)
            // Right eye points (landmarks 33, 160, 158, 133, 153, 144)
            // These points form the eye contour for calculating EAR
            
            try {
                // Calculate Eye Aspect Ratio (EAR)
                val leftEyeAspectRatio = calculateEyeAspectRatio(landmarks, 362, 385, 387, 373, 380, 374)
                val rightEyeAspectRatio = calculateEyeAspectRatio(landmarks, 33, 160, 158, 133, 153, 144)
                
                // The EAR threshold based on research papers is typically around 0.2-0.25
                // Lower values mean eyes are more closed
                val earThreshold = 0.22 // Slightly adjusted for better sensitivity
                val avgEAR = (leftEyeAspectRatio + rightEyeAspectRatio) / 2
                
                Log.d("MainActivity", "Left EAR: $leftEyeAspectRatio, Right EAR: $rightEyeAspectRatio, Avg: $avgEAR")
                
                // Determine drowsiness state
                isDrowsy = avgEAR < earThreshold
                
                runOnUiThread {
                    if (isDrowsy) {
                        detectionStatusText.text = "Drowsiness detected! EAR: ${String.format("%.2f", avgEAR)}"
                    } else {
                        detectionStatusText.text = "Alert (EAR: ${String.format("%.2f", avgEAR)})"
                    }
                }
            } catch (e: IndexOutOfBoundsException) {
                Log.e("MainActivity", "Error accessing face landmarks", e)
                runOnUiThread {
                    detectionStatusText.text = "Detection error"
                    isDrowsy = false
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error processing face mesh result", e)
            runOnUiThread {
                detectionStatusText.text = "Processing error"
                isDrowsy = false
            }
        }
    }

    private fun calculateEyeAspectRatio(
        landmarks: List<com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmark>,
        p1: Int, p2: Int, p3: Int, p4: Int, p5: Int, p6: Int
    ): Float {
        try {
            // Extract eye corner and eyelid points
            val point1 = landmarks[p1] // Eye corner
            val point2 = landmarks[p2] // Upper eyelid
            val point3 = landmarks[p3] // Upper eyelid
            val point4 = landmarks[p4] // Eye corner
            val point5 = landmarks[p5] // Lower eyelid
            val point6 = landmarks[p6] // Lower eyelid
            
            // Calculate vertical distances (from upper to lower eyelid)
            val verticalDist1 = distance(point2, point6)
            val verticalDist2 = distance(point3, point5)
            
            // Calculate horizontal distance (eye width)
            val horizontalDist = distance(point1, point4)
            
            // Eye Aspect Ratio formula: average of vertical distances divided by horizontal distance
            // When eyes are closed, EAR approaches 0
            // Regular open eyes typically have EAR around 0.25-0.3
            if (horizontalDist < 0.001f) {
                // Avoid division by zero
                return 0.3f // Default "open eye" value
            }
            
            return (verticalDist1 + verticalDist2) / (2 * horizontalDist)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error calculating EAR", e)
            return 0.3f // Default to "open" if calculation fails
        }
    }

    private fun distance(p1: com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmark, 
                         p2: com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmark): Float {
        try {
            // Euclidean distance formula in 2D space
            return Math.sqrt(
                Math.pow((p1.x - p2.x).toDouble(), 2.0) +
                Math.pow((p1.y - p2.y).toDouble(), 2.0)
            ).toFloat()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error calculating distance", e)
            return 0.1f // Default small distance
        }
    }

    private fun sendSOSMessages() {
        if (isSendingSOS) return
        
        isSendingSOS = true
        runOnUiThread {
            alertStatusText.text = "Sending SOS messages!"
        }
        
        val contacts = Utils.getContacts(this)
        if (contacts.isEmpty()) {
            runOnUiThread {
                Toast.makeText(this, "No emergency contacts saved", Toast.LENGTH_LONG).show()
                alertStatusText.text = "No contacts to send SOS"
            }
            isSendingSOS = false
            return
        }
        
        val message = "DRIVER DROWSINESS ALERT! The driver appears to be falling asleep while driving. This is an automated emergency message."
        
        try {
            for (contact in contacts) {
                Utils.sendSMS(contact.phoneNumber, message)
            }
            
            runOnUiThread {
                Toast.makeText(this, "SOS messages sent to ${contacts.size} contacts", Toast.LENGTH_LONG).show()
                alertStatusText.text = "SOS messages sent!"
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, "Failed to send SOS: ${e.message}", Toast.LENGTH_LONG).show()
                alertStatusText.text = "Failed to send SOS"
            }
            Log.e("MainActivity", "Error sending SOS", e)
        }
        
        // Reset SOS flag after 30 seconds to allow sending again if needed
        executor.schedule({
            isSendingSOS = false
            runOnUiThread {
                alertStatusText.text = ""
            }
        }, 30, TimeUnit.SECONDS)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if (allPermissionsGranted()) {
                if (detectionSwitch.isChecked) {
                    startDrowsinessDetection()
                }
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Resume detection if it was active
        if (detectionSwitch.isChecked && !isDrowsinessDetectionRunning) {
            startDrowsinessDetection()
        }
    }
    
    override fun onPause() {
        // Pause detection but don't reset the switch
        if (isDrowsinessDetectionRunning) {
            faceMesh?.close()
            isDrowsinessDetectionRunning = false
        }
        super.onPause()
    }

    override fun onDestroy() {
        // Clean up all resources
        faceMesh?.close()
        drowsinessChecker?.shutdown()
        executor.shutdown()
        super.onDestroy()
    }
}
