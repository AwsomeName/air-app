package com.example.air

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.jiangdg.ausbc.CameraClient
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack
import com.jiangdg.ausbc.camera.CameraUvcStrategy
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.render.env.RotateType
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera

import android.util.Log

import android.view.WindowManager
import android.media.MediaRecorder
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import android.provider.MediaStore
import android.content.ContentValues
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.view.MotionEvent

class MainActivity : AppCompatActivity() {
    private val TAG = "USB_CAMERA_DEBUG"
    private val ACTION_USB_PERMISSION = "com.example.air.USB_PERMISSION"
    private var mCurrentDeviceName: String? = null

    // Auto-retry configuration
    private var mRetryButton: android.widget.Button? = null
    private var retryCount = 0
    private val MAX_RETRIES = 3
    private val autoRetryHandler = Handler(Looper.getMainLooper())
    private val autoRetryRunnable = object : Runnable {
        override fun run() {
            val usbManager = getSystemService(android.content.Context.USB_SERVICE) as android.hardware.usb.UsbManager
            val deviceList = usbManager.deviceList

            if (mUVCCamera != null && mCurrentDeviceName != null) {
                val stillConnected = deviceList.values.any { it.deviceName == mCurrentDeviceName }
                if (!stillConnected) {
                    Log.e(TAG, "Device $mCurrentDeviceName no longer found. Releasing...")
                    release64BitCamera()
                }
            }

            Log.e(TAG, "Auto-retry check: mUVCCamera=${if (mUVCCamera != null) "Connected" else "Disconnected"}")
            if (mUVCCamera != null) {
                // Connected
                if (retryCount != 0) retryCount = 0
                if (mRetryButton?.visibility == android.view.View.VISIBLE) {
                    mRetryButton?.visibility = android.view.View.INVISIBLE
                }
            } else {
                // Disconnected
                if (retryCount < MAX_RETRIES) {
                    Log.e(TAG, "Auto-retry attempt: ${retryCount + 1}")
                    checkAndConnectCamera(false)
                    retryCount++
                } else {
                    // Max retries reached
                    if (mRetryButton?.visibility != android.view.View.VISIBLE) {
                        mRetryButton?.visibility = android.view.View.VISIBLE
                        Toast.makeText(this@MainActivity, "Connection failed. Please retry manually.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            autoRetryHandler.postDelayed(this, 10000)
        }
    }

    private fun checkAndConnectCamera(isManual: Boolean) {
        val textureView = findViewById<android.view.TextureView>(R.id.camera_texture_view)
        if (textureView == null) {
            if (isManual) Toast.makeText(this, "View not ready", Toast.LENGTH_SHORT).show()
            return
        }

        val usbManager = getSystemService(android.content.Context.USB_SERVICE) as android.hardware.usb.UsbManager
        val deviceList = usbManager.deviceList
        
        if (deviceList.values.isNotEmpty()) {
            val device = deviceList.values.first()
            open64BitCamera(device, textureView)
        } else {
            if (isManual) Toast.makeText(this, "No Device Found", Toast.LENGTH_SHORT).show()
        }
    }
    
    private val usbReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            val action = intent.action
            if (android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED == action) {
                val device: UsbDevice? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE)
                }
                Log.e(TAG, "USB Device Detached: ${device?.deviceName}")
                if (device?.deviceName == mCurrentDeviceName) {
                    release64BitCamera()
                }
            } else if (android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED == action) {
                val device: UsbDevice? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE)
                }
                Log.e(TAG, "USB Device Attached: ${device?.deviceName}")
                // Try to connect immediately
                checkAndConnectCamera(false)
            } else if (ACTION_USB_PERMISSION == action) {
                synchronized(this) {
                    val device: UsbDevice? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE)
                    }

                    if (intent.getBooleanExtra(android.hardware.usb.UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.apply {
                            Log.e(TAG, "Permission granted for device $deviceName")
                            val textureView = findViewById<android.view.TextureView>(R.id.camera_texture_view)
                            if (textureView != null) {
                                open64BitCamera(this, textureView)
                            }
                        }
                    } else {
                        Log.e(TAG, "Permission denied for device $device")
                        Toast.makeText(context, "Permission denied", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private var mCameraClient: CameraClient? = null
    private var mCameraUvcStrategy: CameraUvcStrategy? = null
    private var mUVCCamera: UVCCamera? = null
    
    private var mVideoWidth = 0
    private var mVideoHeight = 0
    private var mRotationAngle = 90f // Default rotation
    
    private var mMediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var mCurrentVideoFile: File? = null
    private val recordingHandler = Handler(Looper.getMainLooper())
    private var recordingRunnable: Runnable? = null

    private var mRecordingIndicator: android.view.View? = null
    private val mBlinkingHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val mBlinkingRunnable = object : Runnable {
        override fun run() {
            val indicator = mRecordingIndicator ?: return
            if (!isRecording) {
                indicator.visibility = android.view.View.GONE
                return
            }
            if (indicator.visibility == android.view.View.VISIBLE) {
                indicator.visibility = android.view.View.INVISIBLE
            } else {
                indicator.visibility = android.view.View.VISIBLE
            }
            mBlinkingHandler.postDelayed(this, 500)
        }
    }
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (cameraGranted && audioGranted) {
            // initCameraClient()
            Log.e(TAG, "Permissions granted, waiting for surfaceTextureAvailable")
            val textureView = findViewById<android.view.TextureView>(R.id.camera_texture_view)
            if (textureView != null) {
                // Force layout pass just in case
                textureView.requestLayout()
                textureView.invalidate()
            }
        } else {
            Toast.makeText(this, "Permissions required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() {
        super.onPause()
        Log.e(TAG, "onPause called")
        autoRetryHandler.removeCallbacks(autoRetryRunnable)
    }

    override fun onStop() {
        super.onStop()
        Log.e(TAG, "onStop called")
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.e(TAG, "onConfigurationChanged")
        
        if (mVideoWidth > 0 && mVideoHeight > 0) {
            findViewById<android.view.TextureView>(R.id.camera_texture_view)?.post {
                updateTextureViewAspectRatio(mVideoWidth, mVideoHeight)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.e(TAG, "onWindowFocusChanged: $hasFocus")
    }

    override fun onResume() {
        super.onResume()
        Log.e(TAG, "onResume called")
        
        // Start auto-retry loop
        autoRetryHandler.post(autoRetryRunnable)

        val textureView = findViewById<android.view.TextureView>(R.id.camera_texture_view)
        if (textureView.visibility != android.view.View.VISIBLE) {
            textureView.visibility = android.view.View.VISIBLE
        }
        
        if (textureView.isAvailable) {
            // If already available, maybe retry opening camera?
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val filter = android.content.IntentFilter(ACTION_USB_PERMISSION)
        filter.addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED)
        filter.addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED)
        registerReceiver(usbReceiver, filter, android.content.Context.RECEIVER_EXPORTED)
        
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        
        Log.e(TAG, "onCreate called")
        // enableEdgeToEdge()
        
        setContentView(R.layout.activity_main)
        
        mRecordingIndicator = findViewById(R.id.recording_indicator)

        val textureView = findViewById<android.view.TextureView>(R.id.camera_texture_view)
        
        textureView.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                Log.e(TAG, "onSurfaceTextureAvailable")
                
                // Try to use open64BitCamera immediately if device is available
                val usbManager = this@MainActivity.getSystemService(android.content.Context.USB_SERVICE) as android.hardware.usb.UsbManager
                val deviceList = usbManager.deviceList
                if (deviceList.values.isNotEmpty()) {
                    val device = deviceList.values.first()
                    Log.e(TAG, "Found device: ${device.deviceName}, trying open64BitCamera")
                    this@MainActivity.open64BitCamera(device, textureView)
                } else {
                    Log.e(TAG, "No device found")
                }
            }

            override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                Log.e(TAG, "onSurfaceTextureSizeChanged: $width x $height")
            }

            override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
                Log.e(TAG, "onSurfaceTextureDestroyed")
                release64BitCamera()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {
                // Log.e(TAG, "onSurfaceTextureUpdated")
            }
        }

        mRetryButton = findViewById<android.widget.Button>(R.id.retry_button)
        mRetryButton?.setOnClickListener {
            Log.e(TAG, "Retry clicked")
            retryCount = 0
            mRetryButton?.visibility = android.view.View.INVISIBLE
            checkAndConnectCamera(true)
        }

        val rotateButton = findViewById<android.widget.Button>(R.id.rotate_button)
        rotateButton.setOnClickListener {
            mRotationAngle = (mRotationAngle + 90f) % 360f
            Log.e(TAG, "Rotating to $mRotationAngle")
            if (mVideoWidth > 0 && mVideoHeight > 0) {
                updateTextureViewAspectRatio(mVideoWidth, mVideoHeight)
            }
        }
        
        setupCaptureButton()

        if (allPermissionsGranted()) {
            Log.e(TAG, "Permissions already granted")
            // initCameraClient() // Commented out to test layout
        } else {
            Log.e(TAG, "Requesting permissions")
            requestPermissions()
        }
    }

    private fun setupCaptureButton() {
        val captureButton = findViewById<android.widget.Button>(R.id.capture_button)
        if (captureButton == null) {
            Log.e(TAG, "setupCaptureButton: Button not found!")
            return
        }
        Log.e(TAG, "setupCaptureButton: Button found, setting listener")
        
        recordingRunnable = Runnable {
            if (!isRecording) {
                startRecording()
            }
        }
        
        captureButton.setOnTouchListener { v, event ->
            Log.e(TAG, "Touch event: ${event.action} at ${event.x}, ${event.y}")
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Start timer for long press (recording)
                    recordingHandler.postDelayed(recordingRunnable!!, 500) // 500ms threshold
                    v.isPressed = true
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    recordingHandler.removeCallbacks(recordingRunnable!!)
                    v.isPressed = false
                    
                    if (isRecording) {
                        stopRecording()
                    } else {
                        // Short press: Take Photo
                        takePhoto()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun takePhoto() {
        val textureView = findViewById<android.view.TextureView>(R.id.camera_texture_view)
        if (textureView == null || !textureView.isAvailable) {
            Toast.makeText(this, "Camera preview not ready", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // Get bitmap from TextureView
            val bitmap = textureView.getBitmap()
            if (bitmap != null) {
                // Rotate bitmap if needed based on mRotationAngle
                // TextureView.getBitmap() returns the un-rotated content usually, 
                // but we want to save what the user sees (rotated).
                val matrix = android.graphics.Matrix()
                matrix.postRotate(mRotationAngle)
                val rotatedBitmap = android.graphics.Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                )
                
                val filename = "AIR_Photo_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
                
                // Save to scoped storage (MediaStore)
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AIR")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                
                if (uri != null) {
                    resolver.openOutputStream(uri).use { out ->
                        if (out != null) {
                            rotatedBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, out)
                        }
                    }
                    
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        contentValues.clear()
                        contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                        resolver.update(uri, contentValues, null, null)
                    }
                    
                    Toast.makeText(this, "Photo saved to Pictures/AIR", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Failed to create MediaStore entry", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to take photo", e)
            Toast.makeText(this, "Photo error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startRecording() {
        if (isRecording) {
            Log.e(TAG, "Already recording, ignore start request")
            return
        }
        if (mUVCCamera == null) {
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Audio permission missing", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val filename = "AIR_Video_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
            
            val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            if (dir != null && !dir.exists()) {
                dir.mkdirs()
            }
            mCurrentVideoFile = File(dir, filename)
            
            mMediaRecorder = MediaRecorder()
            mMediaRecorder?.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            mMediaRecorder?.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mMediaRecorder?.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            
            // Adjust bitrate and encoding based on resolution
            mMediaRecorder?.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            mMediaRecorder?.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mMediaRecorder?.setVideoEncodingBitRate(2000000) // 2Mbps - Lowered to prevent bandwidth starvation
            mMediaRecorder?.setVideoFrameRate(15) // 15fps - Explicitly set for stability
            
            // Handle rotation logic for video size
            mMediaRecorder?.setVideoSize(mVideoWidth, mVideoHeight)
            
            // Orientation hint for playback
            mMediaRecorder?.setOrientationHint(mRotationAngle.toInt())
            
            mMediaRecorder?.setOutputFile(mCurrentVideoFile!!.absolutePath)
            
            try {
                mMediaRecorder?.prepare()
            } catch (e: Exception) {
                Log.e(TAG, "MediaRecorder prepare failed", e)
                Toast.makeText(this, "Recorder init failed", Toast.LENGTH_SHORT).show()
                releaseMediaRecorder()
                return
            }
            
            val surface = mMediaRecorder?.surface
            if (surface != null) {
                // Swap preview to MediaRecorder surface to avoid multi-surface crash
                if (mUVCCamera != null) {
                    try {
                        // Stop current preview
                        mUVCCamera!!.stopPreview()
                        // Small delay to ensure preview stopped
                        Thread.sleep(100)

                        // Set preview to MediaRecorder surface
                        val setPreviewDisplayMethod = mUVCCamera!!.javaClass.getMethod("setPreviewDisplay", android.view.Surface::class.java)
                        setPreviewDisplayMethod.invoke(mUVCCamera, surface)
                        Log.e(TAG, "Switched preview to MediaRecorder surface")

                        // Start MediaRecorder
                    mMediaRecorder?.start()
                    isRecording = true
                    
                    // Start blinking indicator
                    runOnUiThread {
                        mRecordingIndicator?.visibility = android.view.View.VISIBLE
                        mBlinkingHandler.post(mBlinkingRunnable)
                        Toast.makeText(this, "Recording Started...", Toast.LENGTH_SHORT).show()
                    }
                        Log.e(TAG, "MediaRecorder started successfully")

                        // Start preview (now feeding recorder)
                        mUVCCamera!!.startPreview()
                        Log.e(TAG, "UVCCamera preview restarted on recorder surface")

                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start recording with preview swap", e)
                        Toast.makeText(this, "Recording failed", Toast.LENGTH_SHORT).show()
                        
                        // Try to restore preview to TextureView
                        try {
                             val textureView = findViewById<android.view.TextureView>(R.id.camera_texture_view)
                             if (textureView != null) {
                                 mUVCCamera!!.stopPreview()
                                 val setPreviewTextureMethod = mUVCCamera!!.javaClass.getMethod("setPreviewTexture", android.graphics.SurfaceTexture::class.java)
                                 setPreviewTextureMethod.invoke(mUVCCamera, textureView.surfaceTexture)
                                 mUVCCamera!!.startPreview()
                             }
                        } catch (ignore: Exception) {}

                        releaseMediaRecorder()
                        return
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup recording", e)
            Toast.makeText(this, "Setup failed: ${e.message}", Toast.LENGTH_LONG).show()
            releaseMediaRecorder()
        }
    }

    private fun stopRecording() {
        if (!isRecording) return

        mBlinkingHandler.removeCallbacksAndMessages(null)
        runOnUiThread {
            mRecordingIndicator?.visibility = android.view.View.GONE
        }

        try {
             // Stop preview (which is feeding recorder)
             try {
                 if (mUVCCamera != null) {
                     mUVCCamera!!.stopPreview()
                 }
             } catch (e: Exception) {
                 Log.e(TAG, "Failed to stop preview feeding recorder", e)
             }
            
            try {
                mMediaRecorder?.stop()
            } catch (e: RuntimeException) {
                Log.e(TAG, "MediaRecorder stop failed (too short?)", e)
            }
            
            try {
                mMediaRecorder?.reset()
            } catch (e: Exception) {
                Log.e(TAG, "MediaRecorder reset failed", e)
            }
            
            // Restore preview to TextureView
            try {
                val textureView = findViewById<android.view.TextureView>(R.id.camera_texture_view)
                if (textureView != null && mUVCCamera != null) {
                     val setPreviewTextureMethod = mUVCCamera!!.javaClass.getMethod("setPreviewTexture", android.graphics.SurfaceTexture::class.java)
                     setPreviewTextureMethod.invoke(mUVCCamera, textureView.surfaceTexture)
                     mUVCCamera!!.startPreview()
                     Log.e(TAG, "Restored preview to TextureView")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore preview", e)
            }

            runOnUiThread {
                Toast.makeText(this, "Video saved to Movies/AIR", Toast.LENGTH_SHORT).show()
            }
            
            // Save to Gallery
            if (mCurrentVideoFile != null && mCurrentVideoFile!!.exists()) {
                saveVideoToGallery(mCurrentVideoFile!!)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recording", e)
            Toast.makeText(this, "Stop error: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            releaseMediaRecorder()
            isRecording = false
            mCurrentVideoFile = null
        }
    }

    private fun saveVideoToGallery(videoFile: File) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, videoFile.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/AIR")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)

            if (uri != null) {
                val outputStream = resolver.openOutputStream(uri)
                if (outputStream != null) {
                     outputStream.use { output ->
                        videoFile.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    }

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        values.clear()
                        values.put(MediaStore.Video.Media.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                    }
                    
                    runOnUiThread {
                        Toast.makeText(this, "Video saved to Gallery", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save video to gallery", e)
        }
    }

    private fun releaseMediaRecorder() {
        mMediaRecorder?.release()
        mMediaRecorder = null
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun initCameraClient() {
        Log.e(TAG, "initCameraClient called")
        try {
            val cameraRequest = CameraRequest.Builder()
                .setPreviewWidth(1280)
                .setPreviewHeight(720)
                .create()

            Log.e(TAG, "Creating CameraUvcStrategy")
            val strategy = CameraUvcStrategy(this)
            strategy.setDeviceConnectStatusListener(object : IDeviceConnectCallBack {
                override fun onAttachDev(device: UsbDevice?) {
                    Log.e(TAG, "Device Attached: $device")
                    runOnUiThread { Toast.makeText(this@MainActivity, "Device Attached", Toast.LENGTH_SHORT).show() }
                }

                override fun onDetachDec(device: UsbDevice?) {
                    Log.e(TAG, "Device Detached: $device")
                    runOnUiThread { Toast.makeText(this@MainActivity, "Device Detached", Toast.LENGTH_SHORT).show() }
                }

                override fun onConnectDev(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                    Log.e(TAG, "Device Connected: $device")
                    runOnUiThread { Toast.makeText(this@MainActivity, "Device Connected", Toast.LENGTH_SHORT).show() }
                }

                override fun onDisConnectDec(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                    Log.e(TAG, "Device Disconnected: $device")
                    runOnUiThread { Toast.makeText(this@MainActivity, "Device Disconnected", Toast.LENGTH_SHORT).show() }
                }

                override fun onCancelDev(device: UsbDevice?) {
                    Log.e(TAG, "Permission Cancelled: $device")
                    runOnUiThread { Toast.makeText(this@MainActivity, "Permission Cancelled", Toast.LENGTH_SHORT).show() }
                }
            })
            
            Log.e(TAG, "Registering strategy")
            strategy.register()
            mCameraUvcStrategy = strategy

            Log.e(TAG, "Building CameraClient")
            mCameraClient = CameraClient.Builder(this)
                .setCameraStrategy(strategy)
                .setCameraRequest(cameraRequest)
                .setEnableGLES(true)
                .setDefaultRotateType(RotateType.ANGLE_0)
                .openDebug(true)
                .build()
                
            Log.e(TAG, "CameraClient built successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error in initCameraClient", e)
            Toast.makeText(this, "Init Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun release64BitCamera() {
        if (isRecording) {
            stopRecording()
        }
        try {
            mUVCCamera?.stopPreview()
            mUVCCamera?.destroy()
            mUVCCamera = null
            mCurrentDeviceName = null
        } catch (e: Exception) {
            Log.e(TAG, "release64BitCamera failed", e)
        }
    }

    private fun open64BitCamera(device: UsbDevice, textureView: android.view.TextureView) {
        try {
            Log.e(TAG, "Attempting open64BitCamera for ${device.deviceName}")
            mCurrentDeviceName = device.deviceName
            
            val usbManager = getSystemService(android.content.Context.USB_SERVICE) as android.hardware.usb.UsbManager
            val usbManagerClass = Class.forName("android.hardware.usb.UsbManager")
            
            // User's sequence: getDeviceList then openDevice
            val getDeviceListMethod = usbManagerClass.getMethod("getDeviceList")
            val deviceList = getDeviceListMethod.invoke(usbManager) as HashMap<String, UsbDevice>
            
            // Find the matching device in the list (in case the passed instance is stale)
            val targetDevice = deviceList.values.find { it.deviceName == device.deviceName } ?: device
            
            val openDeviceMethod = usbManagerClass.getMethod("openDevice", UsbDevice::class.java)
            var connection = openDeviceMethod.invoke(usbManager, targetDevice) as? android.hardware.usb.UsbDeviceConnection
            
            if (connection == null) {
                Log.e(TAG, "openDevice returned null. Permission might be missing.")
                if (!usbManager.hasPermission(targetDevice)) {
                    Log.e(TAG, "Requesting permission for ${targetDevice.deviceName}")
                    val permissionIntent = android.app.PendingIntent.getBroadcast(
                        this, 0, android.content.Intent(ACTION_USB_PERMISSION), 
                        android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                    usbManager.requestPermission(targetDevice, permissionIntent)
                    runOnUiThread {
                        Toast.makeText(this, "Please accept USB permission", Toast.LENGTH_LONG).show()
                    }
                    return
                }
            } else {
                Log.e(TAG, "openDevice success! Connection: $connection")
            }
            
            if (connection == null) return
            
            if (mUVCCamera != null) {
                release64BitCamera()
            }

            // Create UsbControlBlock via reflection
            val monitor = USBMonitor(this, object : USBMonitor.OnDeviceConnectListener {
                override fun onAttach(device: UsbDevice?) {}
                override fun onDetach(device: UsbDevice?) {}
                override fun onConnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?, createNew: Boolean) {}
                override fun onDisconnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {}
                override fun onCancel(device: UsbDevice?) {}
            })
            
            mUVCCamera = UVCCamera()
            
            val ctrlBlockClass = Class.forName("com.serenegiant.usb.USBMonitor\$UsbControlBlock")

            // Try to find a constructor that takes UsbDevice (and implicit USBMonitor)
            val constructor = ctrlBlockClass.declaredConstructors.firstOrNull { c ->
                val params = c.parameterTypes
                // Inner class constructor: (Outer, Arg1, Arg2...)
                // So (USBMonitor, UsbDevice) means 2 params.
                params.size == 2 && 
                params[0] == USBMonitor::class.java && 
                params[1] == UsbDevice::class.java
            }
            
            var ctrlBlock: Any? = null
            
            if (constructor != null) {
                constructor.isAccessible = true
                try {
                    // Try to instantiate UsbControlBlock
                    // This MIGHT fail if the constructor checks permissions via Monitor
                    // But we hope it doesn't check immediately or checks differently
                    ctrlBlock = constructor.newInstance(monitor, device)
                    
                    // Inject connection
                    val connectionField = ctrlBlockClass.declaredFields.firstOrNull { f ->
                        f.type == android.hardware.usb.UsbDeviceConnection::class.java
                    }
                    
                    if (connectionField != null) {
                        connectionField.isAccessible = true
                        connectionField.set(ctrlBlock, connection)
                    }
                    
                    // Open camera using reflection (because open(UsbControlBlock) is not accessible with Any?)
                        val methods = UVCCamera::class.java.declaredMethods
                        for (m in methods) {
                            Log.e(TAG, "UVCCamera Method: ${m.name} params: ${m.parameterTypes.joinToString()}")
                        }

                        val openMethod = UVCCamera::class.java.getDeclaredMethod("open", ctrlBlockClass)
                    openMethod.invoke(mUVCCamera, ctrlBlock)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to use UsbControlBlock: ${e.message}")
                    ctrlBlock = null
                }
            }
            
            if (ctrlBlock == null) {
                 // Fallback: Check if UVCCamera has an open method taking UsbDeviceConnection directly
                 val openConnectionMethod = UVCCamera::class.java.declaredMethods.firstOrNull { m ->
                    m.name == "open" && m.parameterTypes.size == 1 && 
                    m.parameterTypes[0] == android.hardware.usb.UsbDeviceConnection::class.java
                 }
                 
                 if (openConnectionMethod != null) {
                     openConnectionMethod.invoke(mUVCCamera, connection)
                 } else {
                     Log.e(TAG, "No suitable open method found in UVCCamera")
                 }
            }
            
            // Try higher resolutions first for better quality
                // Note: Prioritize 1280x720 YUYV for better color accuracy, fallback to MJPEG
                val resolutions = listOf(
                    Triple(1280, 720, UVCCamera.FRAME_FORMAT_YUYV),
                    Triple(1280, 720, UVCCamera.FRAME_FORMAT_MJPEG),
                    Triple(640, 480, UVCCamera.FRAME_FORMAT_YUYV),
                    Triple(640, 480, UVCCamera.FRAME_FORMAT_MJPEG),
                    Triple(1920, 1080, UVCCamera.FRAME_FORMAT_YUYV),
                    Triple(1920, 1080, UVCCamera.FRAME_FORMAT_MJPEG)
                )

            var success = false
            for ((width, height, format) in resolutions) {
                try {
                    Log.e(TAG, "Trying resolution: ${width}x${height} format:$format")
                    mUVCCamera?.setPreviewSize(width, height, format)
                    success = true
                    Log.e(TAG, "Resolution accepted: ${width}x${height} format:$format")
                    
                    mVideoWidth = width
                    mVideoHeight = height
                    
                    // Adjust TextureView aspect ratio
                    runOnUiThread {
                        updateTextureViewAspectRatio(width, height)
                    }
                    
                    break
                } catch (e: IllegalArgumentException) {
                    Log.e(TAG, "Resolution failed: ${width}x${height} format:$format")
                    continue
                }
            }

            if (!success) {
                // Last resort fallback
                try {
                    Log.e(TAG, "Falling back to default 640x480 YUYV")
                    mUVCCamera?.setPreviewSize(640, 480, UVCCamera.FRAME_FORMAT_YUYV)
                    mVideoWidth = 640
                    mVideoHeight = 480
                    runOnUiThread {
                        updateTextureViewAspectRatio(640, 480)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "All resolutions failed", e)
                }
            }
            
            mUVCCamera?.setPreviewTexture(textureView.surfaceTexture)
            mUVCCamera?.startPreview()
            runOnUiThread {
                Toast.makeText(this, "Samsung/64bit Camera Started", Toast.LENGTH_SHORT).show()
            }
            Log.e(TAG, "Samsung/64bit Camera Started successfully")

        } catch (e: Exception) {
            Log.e(TAG, "open64BitCamera failed", e)
            runOnUiThread {
                Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            release64BitCamera()
        }
    }

    private fun updateTextureViewAspectRatio(videoWidth: Int, videoHeight: Int) {
        val textureView = findViewById<android.view.TextureView>(R.id.camera_texture_view) ?: return
        val parent = textureView.parent as? android.view.View ?: return
        
        val screenWidth = parent.width
        val screenHeight = parent.height
        
        if (screenWidth == 0 || screenHeight == 0) return
        
        // 1. Rotate the View
        textureView.rotation = mRotationAngle
        
        // 2. Check if the rotation swaps width and height visually (90 or 270 degrees)
        // Normalize rotation to 0-360 range for check
        val normalizedRotation = (mRotationAngle % 360 + 360) % 360
        val isRotated = normalizedRotation == 90f || normalizedRotation == 270f
        
        val visualVideoWidth = if (isRotated) videoHeight else videoWidth
        val visualVideoHeight = if (isRotated) videoWidth else videoHeight
        
        val videoRatio = visualVideoWidth.toFloat() / visualVideoHeight
        val screenRatio = screenWidth.toFloat() / screenHeight
        
        var targetVisualWidth = 0
        var targetVisualHeight = 0
        
        // 3. Calculate target visual dimensions to fit inside the screen while maintaining aspect ratio
        if (videoRatio > screenRatio) {
            // Video is wider than screen
            // Fit to width
            targetVisualWidth = screenWidth
            targetVisualHeight = (screenWidth / videoRatio).toInt()
        } else {
            // Video is taller than screen
            // Fit to height
            targetVisualHeight = screenHeight
            targetVisualWidth = (screenHeight * videoRatio).toInt()
        }
        
        // 4. Map visual dimensions back to View layout params
        val params = textureView.layoutParams
        
        if (isRotated) {
             // If rotated 90/270:
             // View Width controls Visual Height
             // View Height controls Visual Width
             params.width = targetVisualHeight
             params.height = targetVisualWidth
        } else {
             // If rotated 0/180:
             // View Width controls Visual Width
             // View Height controls Visual Height
             params.width = targetVisualWidth
             params.height = targetVisualHeight
        }
        
        textureView.layoutParams = params
        Log.e(TAG, "Adjusted TextureView (Rotated $mRotationAngle): LayoutParams[${params.width}x${params.height}] Visual[${targetVisualWidth}x${targetVisualHeight}] for Video[${videoWidth}x${videoHeight}]")
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        mCameraClient?.closeCamera()
        mCameraUvcStrategy?.unRegister()
        release64BitCamera()
    }

    companion object {
        private val REQUIRED_PERMISSIONS = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }
    }
}
