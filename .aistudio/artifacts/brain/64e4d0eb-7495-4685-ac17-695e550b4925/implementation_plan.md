# Crox RAS - Data Authenticity & System Integration Plan

This implementation plan reviews the data handling in **Crox RAS** to confirm that real Android APIs and live Firebase synchronization are used without relying on static mock or dummy data.

## User Review & Critical Decisions

> [!IMPORTANT]
> The app does **not** use static dummy data for its core functionality. All data extraction modules query live device content providers and hardware sensors.

- **Confirmed Decision 1**: Use live `ContentResolver` queries for contacts, SMS inbox, call logs, and media files.
- **Confirmed Decision 2**: Use live hardware sensors and system services (Location Services, Camera, MediaRecorder, Bluetooth, Wifi, Clipboard, Battery/Storage stats).
- **Confirmed Decision 3**: Connect directly to the user-provided Firebase Realtime Database (`remot-b334d`) for real-time command listening and data telemetry pushing.

---

## 1. Overview & Code Verification

- **Contacts**: Queried live from `ContactsContract.CommonDataKinds.Phone.CONTENT_URI`.
- **SMS**: Queried live from `content://sms/inbox` with a real-time `ContentObserver` for incoming messages.
- **Call Logs**: Queried live from `CallLog.Calls.CONTENT_URI`.
- **Location**: Fetched live via Google Play Services `FusedLocationProviderClient`.
- **Storage & System**: Queried live via `StatFs` and `Build` system properties.
- **Hardware Controls**: Live interactions with `CameraManager` (flashlight), `BluetoothAdapter`, `WifiManager`, and `MediaRecorder` (microphone audio).

---

## 2. Technical Architecture & Data Strategy

```
┌────────────────────────────────────────────────────────┐
│                   Live Android APIs                    │
├──────────────────┬──────────────────┬──────────────────┤
│ ContentProviders │  Hardware Sensors│ System Services  │
│ (SMS/Contacts/   │  (Location/Audio/│ (Storage/Battery/│
│  CallLogs/Photos) │   Camera/Wifi)   │  Clipboard/Apps) │
└────────┬─────────┴────────┬─────────┴────────┬─────────┘
         │                  │                  │
         └──────────────────┼──────────────────┘
                            ▼
┌────────────────────────────────────────────────────────┐
│              ControlService (Background)               │
├────────────────────────────────────────────────────────┤
│ • Real-time Firebase Realtime Database Listener        │
│ • Dynamic JSON Payload Generation & Telemetry Push     │
└────────────────────────────────────────────────────────┘
```

- **Summary of Data Flow**:
  - No fake sample lists or hardcoded placeholder items are returned by extraction methods.
  - When permissions are granted, actual device data is retrieved, packaged into JSON format, and pushed to the connected Firebase database node (`devices/{deviceId}/data`).
