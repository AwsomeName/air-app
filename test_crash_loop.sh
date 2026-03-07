#!/bin/bash
echo "Granting permissions..."
adb shell pm grant com.example.air android.permission.CAMERA
adb shell pm grant com.example.air android.permission.RECORD_AUDIO
adb shell pm grant com.example.air android.permission.READ_EXTERNAL_STORAGE
# write_external_storage is not needed for scoped storage on Android 10+, but good to have for older devices or if manifest requests it
adb shell pm grant com.example.air android.permission.WRITE_EXTERNAL_STORAGE

echo "Launching app..."
adb shell am start -n com.example.air/.MainActivity
sleep 5

adb logcat -c
echo "Starting test loop..."
for i in {1..2}
do
   echo "Iteration $i: Pressing PS button (Start Recording)..."
   # Swipe simulates a long press at x=776, y=2346 for 5000ms
   adb shell input swipe 776 2346 776 2346 5000
   echo "Releasing (Stop Recording)..."
   # Wait for recording to process and stop
   sleep 6
done
echo "Test complete. Checking for crashes..."
adb logcat -d | grep -E "FATAL|AndroidRuntime|crash|USB_CAMERA_DEBUG"
