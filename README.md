# HassOS on Android

This project is an Android application designed to run Home Assistant OS in a virtual machine on an Android device. It uses [QEMU](https://www.qemu.org/) to virtualize the operating system.

## Project Structure

- `/app`: The main Android application module.
- `/app/src/main/java`: Contains the Java source code for the main activity, which includes the logic for managing and running the VM.
- `/app/src/main/assets`: This directory is intended to hold the required binary dependencies:
    - `qemu-system-aarch64`: The QEMU binary for ARM64 systems.
    - `haos.qcow2.xz`: The compressed Home Assistant OS disk image for the `aarch64` architecture.
    *Note: These files are included as placeholders. You must obtain the actual files and place them here before building.*

## How It Works

When the user clicks the "Start VM" button, the application performs the following steps:
1.  Copies the QEMU binary and the Home Assistant OS image from the app's assets to its private internal storage.
2.  Makes the QEMU binary executable.
3.  Decompresses the Home Assistant OS image (`.qcow2.xz` -> `.qcow2`) using the `XZ for Java` library.
4.  Launches the QEMU process in the background with the following configuration:
    - 2GB of RAM
    - A standard ARM64 virtual machine (`virt`)
    - Networking enabled via user-mode, with port forwarding:
        - Guest port 8123 (HA Web UI) is forwarded to `localhost:8123` on the Android device.
    - A VNC server is started for display, accessible at `localhost:5900` on the device.

## Build Instructions

### Prerequisites
-   [Android SDK](https://developer.android.com/studio) installed.
-   An Android device or emulator.
-   The required dependencies (QEMU and Home Assistant OS image) downloaded and placed in the `app/src/main/assets` directory.
    1.  **Home Assistant OS**: Download the `haos_generic-aarch64-*.qcow2.xz` from the [official releases page](https://github.com/home-assistant/operating-system/releases) and rename it to `haos.qcow2.xz`.
    2.  **QEMU**: Obtain an `aarch64` build of `qemu-system-aarch64` that is compatible with Android. The packages from [Termux](https://github.com/termux/termux-packages) are a good source.

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

1.  Install the `app-debug.apk` on your Android device.
2.  Launch the "HassOS on Android" app.
3.  Tap the "Start VM" button. The status text will update as it prepares the files and launches the VM. This may take some time.
4.  Once the status shows "VM is running!", you can:
    -   Open a web browser on the device and navigate to `http://localhost:8123` to access the Home Assistant onboarding.
    -   Use a VNC client app (like [VNC Viewer for Android](https://play.google.com/store/apps/details?id=com.realvnc.viewer.android)) to connect to `localhost:5900` to see the VM's console output.