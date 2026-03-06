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

class MainActivity : AppCompatActivity() {
    private val TAG = "USB_CAMERA_DEBUG"
    private val ACTION_USB_PERMISSION = "com.example.air.USB_PERMISSION"
    
    private val usbReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
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
                            val surfaceView = findViewById<android.view.SurfaceView>(R.id.camera_surface_view)
                            if (surfaceView != null) {
                                open64BitCamera(this, surfaceView)
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
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (cameraGranted && audioGranted) {
            // initCameraClient()
            Log.e(TAG, "Permissions granted, waiting for surfaceCreated")
            val surfaceView = findViewById<android.view.SurfaceView>(R.id.camera_surface_view)
            if (surfaceView != null) {
                // Force layout pass just in case
                surfaceView.requestLayout()
                surfaceView.invalidate()
            }
        } else {
            Toast.makeText(this, "Permissions required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() {
        super.onPause()
        Log.e(TAG, "onPause called")
    }

    override fun onStop() {
        super.onStop()
        Log.e(TAG, "onStop called")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.e(TAG, "onWindowFocusChanged: $hasFocus")
    }

    override fun onResume() {
        super.onResume()
        Log.e(TAG, "onResume called")
        
        val surfaceView = findViewById<android.view.SurfaceView>(R.id.camera_surface_view)
        if (surfaceView.visibility != android.view.View.VISIBLE) {
            surfaceView.visibility = android.view.View.VISIBLE
        }
        
        val params = surfaceView.layoutParams
        params.width = 640
        params.height = 480
        surfaceView.layoutParams = params
        surfaceView.requestLayout()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val filter = android.content.IntentFilter(ACTION_USB_PERMISSION)
        registerReceiver(usbReceiver, filter, android.content.Context.RECEIVER_EXPORTED)
        
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        
        Log.e(TAG, "onCreate called")
        // enableEdgeToEdge()
        
        setContentView(R.layout.activity_main)

        val surfaceView = findViewById<android.view.SurfaceView>(R.id.camera_surface_view)
        
        surfaceView.holder.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                Log.e(TAG, "surfaceCreated")
                
                // Try to use open64BitCamera immediately if device is available
                val usbManager = this@MainActivity.getSystemService(android.content.Context.USB_SERVICE) as android.hardware.usb.UsbManager
                val deviceList = usbManager.deviceList
                if (deviceList.values.isNotEmpty()) {
                    val device = deviceList.values.first()
                    Log.e(TAG, "Found device: ${device.deviceName}, trying open64BitCamera")
                    this@MainActivity.open64BitCamera(device, surfaceView)
                } else {
                    Log.e(TAG, "No device found")
                }
            }

            override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.e(TAG, "surfaceChanged: $width x $height")
            }

            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                Log.e(TAG, "surfaceDestroyed")
                release64BitCamera()
            }
        })

        val retryButton = findViewById<android.widget.Button>(R.id.retry_button)
        retryButton.setOnClickListener {
            Log.e(TAG, "Retry clicked")
            val usbManager = getSystemService(android.content.Context.USB_SERVICE) as android.hardware.usb.UsbManager
            val deviceList = usbManager.deviceList
            deviceList.values.forEach { device ->
                if (!usbManager.hasPermission(device)) {
                    Log.e(TAG, "No permission for ${device.deviceName}")
                }
            }
            
            if (surfaceView != null) {
                if (deviceList.values.isNotEmpty()) {
                    val device = deviceList.values.first()
                    open64BitCamera(device, surfaceView)
                } else {
                    Toast.makeText(this@MainActivity, "No Device Found", Toast.LENGTH_SHORT).show()
                }
            } else {
                 Log.e(TAG, "SurfaceView not found")
                 Toast.makeText(this@MainActivity, "View not ready", Toast.LENGTH_SHORT).show()
             }
        }


        if (allPermissionsGranted()) {
            Log.e(TAG, "Permissions already granted")
            // initCameraClient() // Commented out to test layout
        } else {
            Log.e(TAG, "Requesting permissions")
            requestPermissions()
        }
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
        try {
            mUVCCamera?.stopPreview()
            mUVCCamera?.destroy()
            mUVCCamera = null
        } catch (e: Exception) {
            Log.e(TAG, "release64BitCamera failed", e)
        }
    }

    private fun open64BitCamera(device: UsbDevice, surfaceView: android.view.SurfaceView) {
        try {
            Log.e(TAG, "Attempting open64BitCamera for ${device.deviceName}")
            
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
            
            // 640x480 is what user suggested
            try {
                mUVCCamera?.setPreviewSize(640, 480, UVCCamera.FRAME_FORMAT_YUYV)
            } catch (e: IllegalArgumentException) {
                try {
                    mUVCCamera?.setPreviewSize(1280, 720, UVCCamera.FRAME_FORMAT_YUYV)
                } catch (e2: IllegalArgumentException) {
                    mUVCCamera?.setPreviewSize(640, 480, UVCCamera.FRAME_FORMAT_YUYV)
                }
            }
            
            mUVCCamera?.setPreviewDisplay(surfaceView.holder)
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
