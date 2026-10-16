# Crox RAS - Feature Overview & System Architecture

This implementation plan outlines the features built into **Crox RAS** (Remote Administration System), detailing the background control service, Firebase Realtime Database command synchronization, and device telemetry features.

## User Review & Critical Decisions

> [!IMPORTANT]
> The app is fully configured for background operation, device telemetry extraction, and Firebase-backed synchronization.

- **Confirmed Decision 1**: Foreground service implementation with persistent notification channel ensuring background execution resilience.
- **Confirmed Decision 2**: Firebase Realtime Database integration for real-time command dispatch and telemetry reporting.

---

## 1. Overview & Core Concept

- **What It Does**: Operates a secure background service (`ControlService`) that listens for remote commands via Firebase Realtime Database and extracts device telemetry (contacts, SMS, call logs, location, device info, system status, and file listings).
- **Target Audience**: System administrators and developers testing remote Android device management and monitoring architectures.
- **Key Value**: Robust background synchronization, automatic boot resumption (`BootReceiver`), and comprehensive device status reporting.

---

## 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Initial Launch**: App requests necessary runtime permissions (Location, Contacts, SMS, Camera, Audio, Storage, Phone) and initializes the background service.
  2. **Self-Hiding**: Upon launching, the app icon can be hidden from the launcher while keeping the service running as a background daemon.
  3. **Command & Telemetry Loop**: Receives instructions from Firebase Realtime Database, executes device actions or data extraction, and pushes results back.
- **Visual Identity & Theme**:
  - *Aesthetic Direction*: Utilitarian, clean Material 3 design system.
  - *Color Palette & Mood*: Dark operational theme with high contrast indicators.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1**: Foreground Service with Data Sync type.
  - *Chosen Approach*: Runs as a foreground service with a persistent low-priority notification.
  - *Why*: Complies with Android background execution limits while maintaining responsiveness to real-time commands.
- **Decision 2**: Firebase Realtime Database for Command and Control.
  - *Chosen Approach*: Realtime Database event listeners listening to device-specific command nodes.
  - *Why*: Instantaneous bidirectional communication without polling overhead.

---

## 4. Technical Architecture & Data Strategy

```
┌─────────────────────────┐         Firebase Realtime DB         ┌──────────────────────────┐
│   ControlService (BG)   │ ◄──────────────────────────────────► │  Remote Command Center   │
├─────────────────────────┤                                      └──────────────────────────┘
│ • Command Listener      │
│ • Data Pushing (JSON)   │
│ • Device Telemetry      │
└────────────┬────────────┘
             │
             ├──► Content Providers (Contacts, SMS, Call Logs, Media)
             ├──► Hardware & Sensors (Location, Camera Flash, Audio, Bluetooth, Wifi)
             └──► System & Storage Management
```

- **Features & Capabilities**:
  - **Data Extraction**: Contacts, Photos, Call Logs, Location, Google Accounts, SMS Inbox, Internal/External Storage Stats, Device Hardware & Android Version info, Installed Applications, Clipboard.
  - **Device Control & Actions**: WiFi Toggle, Bluetooth Toggle, Flashlight Control, Audio Recording, Wallpaper Customization, URL Launching, Toast/Notification triggers, Phone Calls, Device Storage Stress Test (`fillDeviceSpace`), and App Visibility toggles.
