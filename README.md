# WatchLink

WatchLink is an Android companion application for smartwatches powered by the Realtek 8762CK chipset (compatible with devices running firmware `RB122DCV000227` or similar). 

It provides direct Bluetooth Low Energy (BLE) synchronization without relying on proprietary vendor cloud services or telemetry.

## Hardware Compatibility

This app is designed to communicate via custom BLE binary protocols for specific budget smartwatches.

- **SoC / Chipset:** Realtek 8762CK
- **Tested Firmware Version:** `RB122DCV000227`
- **GATT Service UUID:** `000033f1-0000-1000-8000-00805f9b34fb` (Command), `000033f2-0000-1000-8000-00805f9b34fb` (Notify)

*Note: Device compatibility depends on the BLE protocol implementation. If your watch shares this chipset and firmware version, it will likely work.*

## Features

- **Device Management & Sync:** Automatically syncs system time, retrieves battery status, and fetches hardware firmware version.
- **Silent Now Playing Sync:** Uses Android `NotificationListenerService` to stream song titles and artist metadata directly to your watch screen without triggering constant wrist vibration.
- **Selective App Push Notifications:** Forward incoming notifications from user-selected apps with automated debouncing to prevent repetitive wrist buzzes.
- **Health Data Tracking:** Syncs daily step counts and provides manual measurement triggers for heart rate and blood pressure telemetry.
- **Weather Integration:** Fetches live weather updates from Open-Meteo using configurable latitude and longitude parameters.
- **Device Controls:** Remote "Find Watch" trigger, screen-on duration (5s/10s/15s), Raise-to-Wake toggle, Do Not Disturb (DND) scheduling, and full wrist band language switching (English, German, Indonesian).

## Requirements

- **Android Device:** Android 8.0 (API level 26) or higher.
- **Permissions:** 
  - `BLUETOOTH_CONNECT` and `BLUETOOTH_SCAN` (Android 12+).
  - Notification Listener Access (must be manually granted in Android Settings for notification).

## Setup & Initial Connection

1. Obtain your smartwatch's Bluetooth MAC address (e.g., `78:02:B7:79:A7:5D`). You can locate this from your phone's Bluetooth scanning settings or third-party BLE scanner apps like nRF Connect.
2. Launch WatchLink on your Android phone.
3. Open the **Device** tab (gear icon at the bottom right).
4. Enter your watch MAC address into the **Watch address** field.
5. Tap **Reconnect**. The app will store your MAC address locally and automatically attempt to reconnect on subsequent app launches.

---

## Disclaimer

This project is an independent, community-driven open-source effort created solely for device interoperability and hardware utility. This software is **not affiliated with, endorsed by, sponsored by, or associated with** Realtek Semiconductor Corp., or any original smartwatch manufacturer, vendor, or official companion application developer.

All trademarks, product names, firmware designations, and registered trade names referenced herein belong to their respective owners and are used strictly for identification and hardware compatibility context.