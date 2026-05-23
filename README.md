# Rovin Browser / Rovin Runtime

Rovin Browser is an open-source, Chromium/Wolvic-based XR browser project maintained by Rovin. It serves as the foundation for **Rovin Runtime**, a highly optimized, lightweight runtime layer designed to run WebXR applications as native-style, standalone Meta Quest applications.

---

## Project Status

This repository is **active and under progressive development**. 

* **State of the Code**: The codebase is undergoing an active transition. You may still encounter upstream Wolvic references in package names, resources, and build configuration files during this phase.
* **Continuous Optimization**: The `rovin-runtime-main` branch is being progressively stripped, sanitized, and optimized for WebXR-only deployment, removing standard browser UI features and background services that are unnecessary for standalone app packaging.

---

## Why This Project Exists

* **The WebXR Packaging Problem**: Standard WebXR applications normally run inside traditional XR web browsers, requiring users to open a browser, navigate to a URL, and manually enter immersive mode.
* **Native-Style App Delivery**: For standalone Meta Quest app distribution, app stores (like the Meta Horizon Store or App Lab), and premium game packaging, developers need a native APK that launches directly into the WebXR game.
* **Foundational Strength**: Wolvic provides a robust, production-tested OpenXR and Chromium integration layer for standalone VR devices.
* **The Rovin Solution**: Rovin Runtime adapts and strips the Wolvic foundation to act as a dedicated WebXR app host, launching directly into a specific game or runtime endpoint with minimal browser overhead.

---

## Rovin Browser

The **Rovin Browser** side of the project:
* Relies on the **Wolvic** baseline and **Chromium** engine.
* Maintains full, traditional XR browsing functionality (tabs, URL bar, bookmarks, and settings).
* Serves as our primary platform for testing upstream engine upgrades, custom patches, and experimental browser-level fixes.
* Acts as the broader baseline from which the runtime is derived.

---

## Rovin Runtime

The **Rovin Runtime** side of the project:
* Is a specialized, stripped-down branch derived from the browser baseline.
* Is purpose-built to run a **specific WebXR app/project** (such as *Neon Chuck*).
* Keeps only the bare essentials needed for WebXR execution, input routing, controller/headset tracking, and Quest platform integration.
* Toggles off or deletes unnecessary browser features (such as address bars, traditional search engines, tab managers, first-run privacy dialogs, and background telemetry).
* Enables seamless integration between native Android activities and the in-page WebXR gameplay loop.

---

## Current Focus

Our current engineering efforts are focused on:
* **Upstream Maintenance**: Keeping the core OpenXR integration and Chromium rendering engine up to date and secure.
* **WebXR Handoff Stability**: Improving the reliability of the transition between cold boot, the native Quest landing shell, and active WebXR immersive presentation.
* **Footprint Reduction**: Systematically disabling background browser services to optimize CPU/GPU cycles for standalone gameplay.
* **Packaging Orchestration**: Simplifying the PowerShell and Gradle workflows to bundle game assets into the runtime APK.
* **Performance Tuning**: Minimizing first-frame startup latency and optimizing controller haptic routing.
* **Codebase Cleanup**: Gradually refactoring legacy files and removing dead browser paths.

---

## Architecture Overview

The system is organized into the following logical layers:

```
┌────────────────────────────────────────────────────────┐
│               WebXR Content Layer (HTML5/JS)           │
├────────────────────────────────────────────────────────┤
│          Rovin Runtime Packaging/Config Layer          │
├────────────────────────────────────────────────────────┤
│           Wolvic XR Integration (Java/C++ API)         │
├────────────────────────────────────────────────────────┤
│            Chromium Rendering Engine (M121+)           │
├────────────────────────────────────────────────────────┤
│           Android / Meta Quest Native App OS           │
└────────────────────────────────────────────────────────┘
```

---

## Attribution

This project is based on the exceptional work of:
* **Wolvic** by Igalia ([github.com/Igalia/wolvic](https://github.com/Igalia/wolvic)).
* The **Chromium** Open Source Project.

This repository preserves all upstream licenses and copyright notices. All modifications are made in compliance with the original open-source licenses. Upstream Wolvic and Chromium attribution and licensing apply to all inherited components.

---

## Repository Naming Note

While this repository is named `rovin-browser` to align with the core open-source engine fork, active development is heavily focused on the **Rovin Runtime** branches and packaging configurations (specifically `rovin-runtime-main` and `rovin-runtime-granular`).

---

## What Not To Do

When contributing or working with this codebase:
* **Do not claim** this is a brand-new browser engine. It is a downstream fork of Wolvic/Chromium.
* **Do not remove** or obscure upstream Wolvic/Chromium licensing or attribution.
* **Do not add** marketing fluff or promise production-grade stability on experimental branches.
* **Do not modify** core engine libraries unless validating compile compatibility against the March 30 AAR baseline.

---

## Build Instructions

> [!NOTE]
> The build system and configuration instructions are currently inherited from the Wolvic baseline. They will be progressively refined as the Rovin Browser/Runtime configurations diverge.

### Prerequisites

The command line version of `gradlew` requires **JDK 11** or **JDK 17** depending on your target variant. If you get a Java version compilation error, verify your active JDK version using `java -version`.

### 1. Setup Third-Party SDKs
If you are building for a commercial VR headset (such as Meta Quest, Pico, or VIVE), you must place the required native SDKs in the `third_party/` directory:
* **Oculus/Meta Quest**: Place the OVR Platform SDK in `third_party/OVRPlatformSDK/` (must contain `Android` and `Include` directories).
* **Pico XR**: Place the Pico OpenXR Mobile SDK in `third_party/picoxr`.
* **Snapdragon Spaces**: Place the Spaces OpenXR loader in `third_party/spaces` (must contain `libopenxr_loader.aar`).
* **Huawei Vision Glass**: Place the IMU library in `third_party/aliceimu/`.

### 2. Configure Local Development Options
You can tune the build behavior by adding properties to your local `user.properties` file in the root directory:

* **Simultaneous Installs**: To install both a development build and a production build simultaneously without overwriting:
  ```ini
  simultaneousDevProduction=true
  ```
* **Debug Signing on Release**: To test performance or debug release-only symptoms without managing production keys:
  ```ini
  useDebugSigningOnRelease=true
  ```
* **Static Version Code**: To prevent date-based version regeneration and improve Gradle cache build speed:
  ```ini
  useStaticVersionCode=true
  ```
* **Disable Crash Restart**: To prevent the app from auto-relaunching when a native crash occurs (useful for gathering LLDB crash dumps):
  ```ini
  disableCrashRestart=true
  ```

### 3. Texture Compression
To compress raw graphics assets using etc2comp for standalone VR devices:
```bash
cd tools/compressor
npm install
npm run compress
```

### 4. Native Debugging & Diagnostics
* **Command Line URL Injection**: Load a specific WebXR page directly from the command line:
  ```bash
  adb shell am start -a android.intent.action.VIEW -d "https://example.com" com.igalia.wolvic/com.igalia.wolvic.VRBrowserActivity
  ```
* **Homepage Override**: Load a default homepage directly on launch:
  ```bash
  adb shell am start -a android.intent.action.VIEW -n com.igalia.wolvic/com.igalia.wolvic.VRBrowserActivity -e homepage "https://example.com"
  ```
* **Oculus Video Capture**: Start recording system video on Oculus/Meta devices:
  ```bash
  adb shell setprop debug.oculus.enableVideoCapture 1 # Start recording
  adb shell setprop debug.oculus.enableVideoCapture 0 # Stop recording
  ```

For detailed instructions on building against a development version of the Chromium engine, see [CHROMIUM.md](CHROMIUM.md).

---

## Roadmap

* [ ] **Strict Profile Separation**: Separate browser and runtime targets cleanly at the build variant level rather than using runtime branch drift.
* [ ] **Runtime-Only Build Flavor**: Implement a compiler configuration that fully strips 2D browser code, reducing APK payload size by up to 30%.
* [ ] **App Store Readiness**: Complete the audit and removal of background telemetry and unused Android permissions to satisfy Horizon Store reviews.
* [ ] **Advanced Startup Handshake**: Refine the polling protocol between the native startup activity and in-page WebXR to minimize cold-boot latency.
* [ ] **Modular App Templates**: Provide clean project examples for packaging any WebXR app using the Rovin Runtime baseline.
