# HassOS on Android

This project is an Android application designed to run Home Assistant OS in a virtual machine on an Android device. It uses [QEMU](https://www.qemu.org/) to virtualize the operating system.

**🚨 IMPORTANT: This application requires a rooted Android device to function. 🚨**

It needs root access to execute the QEMU binary from the app's private data directory, bypassing standard Android security restrictions.

## How It Works

The application is designed to be self-contained. On first run, the user must click the "Download Files" button, which performs the following steps:

1.  **Downloads QEMU:** The app fetches the latest package index from the official Termux repository to find the correct URL for the `qemu-system-aarch64` package. It then downloads and validates this package.
2.  **Unpacks QEMU:** Using an embedded Java library, the app unpacks the downloaded `.deb` archive and extracts the QEMU executable.
3.  **Downloads Home Assistant OS:** The app downloads a compatible `.qcow2` disk image for Home Assistant OS.
4.  **Decompresses OS Image:** The `.qcow2.xz` image is decompressed.

Once the setup is complete, the user can click "Start VM". This will use root (`su`) to execute the QEMU binary with the appropriate parameters, including forwarding port 8123 for the web UI and starting a VNC server on port 5900 for display.

## Build Instructions

### Prerequisites
-   [Android SDK](https://developer.android.com/studio) installed.

### Steps
1.  **Configure SDK Location:**
    Create a file named `local.properties` in the `app/` directory with the following content, replacing `/path/to/your/sdk` with the actual path to your Android SDK installation:
    ```
    sdk.dir=/path/to/your/sdk
    ```

2.  **Build the APK:**
    Navigate to the root of the project and run the following command to build the debug APK:
    ```bash
    ./gradlew assembleDebug
    ```
    The built APK will be located at `app/build/outputs/apk/debug/app-debug.apk`.

## Running the App

1.  Install the `app-debug.apk` on your **rooted** Android device.
2.  Launch the "HassOS on Android" app.
3.  Tap the "Download Files" button and wait for the setup process to complete.
4.  When prompted by your root management app (e.g., Magisk), grant the application superuser permissions.
5.  Tap the "Start VM" button.
6.  Once the status shows "VM is running!", you can:
    -   Open a web browser on the device and navigate to `http://localhost:8123` to access the Home Assistant onboarding.
    -   Use a VNC client app (like [VNC Viewer for Android](https://play.google.com/store/apps/details?id=com.realvnc.viewer.android)) to connect to `localhost:5900` to see the VM's console output.