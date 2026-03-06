# USB Camera Implementation

This project implements a USB Camera preview using the `AndroidUSBCamera` library (version `3.2.0`).

## Key Components

1.  **Dependencies**:
    - `com.github.jiangdongguo.AndroidUSBCamera:libausbc:3.2.0` (via JitPack)
    - `com.github.jiangdongguo.AndroidUSBCamera:libuvc:3.2.0` (explicitly added to fix runtime scope issue)
    - Added `maven { url 'https://jitpack.io' }` and `maven { url 'https://raw.github.com/saki4510t/libcommon/master/repository/' }` to `settings.gradle`.

2.  **Permissions**:
    - `android.permission.CAMERA`
    - `android.permission.RECORD_AUDIO`
    - `android.permission.WRITE_EXTERNAL_STORAGE`
    - `android.hardware.usb.host` feature (essential for USB devices).

3.  **Implementation**:
    - `MainActivity.kt` handles the camera logic using `CameraClient`.
    - It uses `CameraUvcStrategy` to manage USB camera connection.
    - Uses `AndroidView` in Jetpack Compose to embed the `AspectRatioTextureView`.
    - Automatically requests permissions and starts preview upon device connection.
    - Implements `IDeviceConnectCallBack` for USB events.

## How to Run

1.  Connect an Android device that supports USB OTG.
2.  Connect a UVC-compliant USB Camera via an OTG adapter.
3.  Run the app.
4.  Grant the camera/storage permissions when prompted.
5.  Grant the USB device permission when the dialog appears.
6.  The camera preview should appear on the screen.

## Troubleshooting

-   **No Preview**: Ensure the USB camera is UVC standard. Some proprietary cameras are not supported.
-   **Permission Denied**: If the USB permission dialog doesn't appear, try reconnecting the camera.
-   **Crash**: Check Logcat for `CameraClient` or `libausbc` errors. Ensure the device supports USB Host mode.
