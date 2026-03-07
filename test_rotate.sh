#!/bin/bash
adb logcat -c
echo "Tapping Rotate button at 480 2346..."
adb shell input tap 480 2346
sleep 1
echo "Checking logs..."
adb logcat -d | grep -E "Rotating|USB_CAMERA_DEBUG"
