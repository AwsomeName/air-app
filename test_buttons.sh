#!/bin/bash
adb logcat -c
echo "Tapping 160 2346 (Left)..."
adb shell input tap 160 2346
sleep 1
echo "Tapping 480 2346 (Middle)..."
adb shell input tap 480 2346
sleep 1
echo "Tapping 800 2346 (Right)..."
adb shell input tap 800 2346
sleep 1
echo "Checking logs..."
adb logcat -d | grep -E "Touch event|Rotating|Retry clicked|USB_CAMERA_DEBUG"
