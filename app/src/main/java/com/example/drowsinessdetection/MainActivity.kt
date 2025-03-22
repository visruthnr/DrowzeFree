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
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat.getSystemService
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
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(cameraPreview.surfaceProvider)
            
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview)
                
                // Initialize FaceMesh
                setupFaceMesh()
            } catch (e: Exception) {
                Log.e("MainActivity", "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupFaceMesh() {
        faceMesh = FaceMesh(
            this,
            FaceMeshOptions.builder()
                .setStaticImageMode(false)
                .setRefineLandmarks(true)
                .setRunOnGpu(true)
                .build()
        )
        
        faceMesh?.setResultListener { faceMeshResult ->
            processFaceMeshResult(faceMeshResult)
        }
        
        // Set error listener
        faceMesh?.setErrorListener { message, e ->
            Log.e("MainActivity", "MediaPipe Face Mesh error: $message", e)
        }
    }

    private fun processFaceMeshResult(result: FaceMeshResult) {
        if (result.multiFaceLandmarks().isEmpty()) {
            // No face detected
            runOnUiThread {
                detectionStatusText.text = "No face detected"
            }
            return
        }
        
        // Get landmarks for the first face
        val landmarks = result.multiFaceLandmarks()[0].landmarkList
        
        // Analyze eye state to detect drowsiness
        // Here we would use specific landmarks for left and right eyes
        // This is a simplified implementation - in a real app, you'd need more sophisticated analysis
        
        // For example, checking eye aspect ratio (EAR)
        val leftEyeAspectRatio = calculateEyeAspectRatio(landmarks, 362, 385, 387, 373, 380, 374)
        val rightEyeAspectRatio = calculateEyeAspectRatio(landmarks, 33, 160, 158, 133, 153, 144)
        
        // The threshold could be adjusted based on calibration
        val earThreshold = 0.2
        val avgEAR = (leftEyeAspectRatio + rightEyeAspectRatio) / 2
        
        isDrowsy = avgEAR < earThreshold
        
        runOnUiThread {
            if (isDrowsy) {
                detectionStatusText.text = "Drowsiness detected!"
            } else {
                detectionStatusText.text = "Alert"
            }
        }
    }

    private fun calculateEyeAspectRatio(
        landmarks: List<com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmark>,
        p1: Int, p2: Int, p3: Int, p4: Int, p5: Int, p6: Int
    ): Float {
        val point1 = landmarks[p1]
        val point2 = landmarks[p2]
        val point3 = landmarks[p3]
        val point4 = landmarks[p4]
        val point5 = landmarks[p5]
        val point6 = landmarks[p6]
        
        val verticalDist1 = distance(point2, point6)
        val verticalDist2 = distance(point3, point5)
        val horizontalDist = distance(point1, point4)
        
        return (verticalDist1 + verticalDist2) / (2 * horizontalDist)
    }

    private fun distance(p1: com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmark, 
                         p2: com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmark): Float {
        return Math.sqrt(
            Math.pow((p1.x - p2.x).toDouble(), 2.0) +
            Math.pow((p1.y - p2.y).toDouble(), 2.0)
        ).toFloat()
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

    override fun onDestroy() {
        super.onDestroy()
        faceMesh?.close()
        drowsinessChecker?.shutdown()
        executor.shutdown()
    }
}
