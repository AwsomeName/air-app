#!/bin/bash
adb logcat -c
echo "Starting Feature Test..."

# 1. Check Auto-retry loop (wait 11s to ensure at least one check)
echo "Waiting for Auto-retry check..."
sleep 11

# 2. Test PS Button (Long Press for Recording)
echo "Simulating Long Press on PS Button (800 2346)..."
adb shell input swipe 800 2346 800 2346 1000
echo "Waiting for recording start..."
sleep 5

# 3. Test PS Button (Release/Stop Recording)
# Note: Swipe 1000ms automatically releases. 
# But logic says stopRecording is called on ACTION_UP. 
# input swipe triggers DOWN, moves (if any), then UP.
# So after 1000ms, it should trigger UP.
echo "Recording should have stopped (or stopping now)..."
sleep 5

# 4. Check Logs
echo "Checking Logs..."
adb logcat -d | grep -E "Auto-retry check|Touch event|Recording Started|MediaRecorder started|switched preview|Blinking|Retry clicked"
