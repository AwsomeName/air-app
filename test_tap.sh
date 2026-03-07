#!/bin/bash
adb logcat -c
echo "Tapping at 776 2346 (Override coords)..."
adb shell input tap 776 2346
sleep 1
echo "Tapping at 660 1994 (Physical scaled coords)..."
adb shell input tap 660 1994
sleep 1
echo "Checking logs..."
adb logcat -d | grep -E "Touch event|USB_CAMERA_DEBUG"
