# ❤️ Heart Rate Monitor Overlay

A simple Android app that connects to your Garmin HRM-Pro Plus (or any BLE heart rate strap) and displays your heart rate as a floating overlay on screen.

## Features

- Connects to BLE heart rate straps (Garmin HRM-Pro Plus, Polar, Wahoo, etc.)
- Floating overlay showing real-time BPM on top of any app (YouTube, etc.)
- Runs in background via foreground service
- Draggable overlay — move it wherever you want
- Auto-reconnect on disconnection

## Requirements

- Android 8.0 (API 26) or higher
- BLE-capable device
- Heart rate strap with BLE support

## Build

### Option 1: GitHub Actions (recommended)

1. Fork or clone this repo
2. Push to GitHub
3. Go to **Actions** tab → Download the APK from the latest run

### Option 2: Android Studio

1. Open the project in Android Studio
2. **Build → Build APK(s)**
3. Install on your device

## Usage

1. Install the app
2. Grant Bluetooth and Overlay permissions
3. Wear your heart rate strap
4. Tap **Start Monitoring**
5. Go to YouTube or any other app
6. The heart rate overlay appears in the top-right corner!

## Tips

- Wet the electrodes before wearing for better skin contact
- Make sure no other app is using the heart rate strap (close Garmin Connect, Zwift, etc.)
- The overlay is draggable — touch and hold to move it
- Tap the notification **Stop** button to stop monitoring
