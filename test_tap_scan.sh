#!/bin/bash
adb logcat -c
echo "Scanning taps..."
for y in 2250 2300 2346 2400 2450 2500
do
  echo "Tapping 776 $y"
  adb shell input tap 776 $y
  sleep 0.5
done

for x in 650 700 776 850 900
do
  echo "Tapping $x 2346"
  adb shell input tap $x 2346
  sleep 0.5
done

echo "Checking logs..."
adb logcat -d | grep "Touch event"
