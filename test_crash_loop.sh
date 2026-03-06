#!/bin/bash
adb logcat -c
echo "Starting test loop..."
for i in {1..5}
do
   echo "Iteration $i: Pressing PS button (Start Recording)..."
   # Swipe simulates a long press at x=776, y=2346 for 5000ms
   adb shell input swipe 776 2346 776 2346 5000
   echo "Releasing (Stop Recording)..."
   # Wait for recording to process and stop
   sleep 6
done
echo "Test complete. Checking for crashes..."
adb logcat -d | grep -E "FATAL|AndroidRuntime|crash"
