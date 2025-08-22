package com.example.hassosonandroid;

import androidx.appcompat.app.AppCompatActivity;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import org.apache.commons.compress.archivers.ar.ArArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "HassOS";
    private static final String QEMU_DEB_NAME = "qemu.deb";
    private static final String QEMU_BINARY_NAME = "qemu-system-aarch64";
    private static final String OS_IMAGE_NAME_XZ = "haos.qcow2.xz";
    private static final String OS_IMAGE_NAME = "haos.qcow2";

    private static final String HAOS_URL = "https://github.com/home-assistant/operating-system/releases/download/12.3/haos_generic-aarch64-12.3.qcow2.xz";
    private static final String TERMUX_REPO_URL = "https://packages.termux.dev/apt/termux-main/";
    private static final String TERMUX_PACKAGES_FILE_URL = TERMUX_REPO_URL + "dists/stable/main/binary-aarch64/Packages";

    private static final String PREF_QEMU_VERSION = "qemu_version";
    private static final String PREF_QEMU_SHA256 = "qemu_sha256";

    private static class PackageInfo {
        String filename;
        String version;
        String sha256;
    }

    private TextView statusTextView;
    private Button downloadButton;
    private Button startButton;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusTextView = findViewById(R.id.textView);
        downloadButton = findViewById(R.id.download_button);
        startButton = findViewById(R.id.start_button);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        downloadButton.setOnClickListener(v -> downloadFiles());
        startButton.setOnClickListener(v -> startVm());

        checkFilesExist();
    }

    private void checkFilesExist() {
        File qemuBinary = new File(getFilesDir(), QEMU_BINARY_NAME);
        File osImage = new File(getFilesDir(), OS_IMAGE_NAME);

        if (qemuBinary.exists() && osImage.exists()) {
            startButton.setEnabled(true);
            downloadButton.setEnabled(true);
            updateStatus("Ready. Check for updates or start VM.");
        } else {
            startButton.setEnabled(false);
            downloadButton.setEnabled(true);
            updateStatus("Please download required files.");
        }
    }

    private void downloadFiles() {
        downloadButton.setEnabled(false);
        updateStatus("Checking for new versions...");

        new Thread(() -> {
            try {
                // 1. Get latest QEMU package info from server
                PackageInfo serverInfo = getQemuPackageInfo();
                File qemuDeb = new File(getFilesDir(), QEMU_DEB_NAME);
                File qemuBinary = new File(getFilesDir(), QEMU_BINARY_NAME);

                // 2. Get local QEMU package info from prefs
                String localVersion = prefs.getString(PREF_QEMU_VERSION, null);

                // 3. Decision logic for QEMU
                boolean needsDownload = false;
                if (!qemuBinary.exists()) {
                    updateStatus("QEMU binary not found. Downloading...");
                    needsDownload = true;
                } else if (!serverInfo.version.equals(localVersion)) {
                    updateStatus("New version of QEMU found. Downloading...");
                    needsDownload = true;
                } else {
                    // We could validate the hash of the binary here, but skipping for simplicity.
                    updateStatus("QEMU is up-to-date.");
                }

                if (needsDownload) {
                    String qemuUrl = TERMUX_REPO_URL + serverInfo.filename;
                    downloadUrlToFile(qemuUrl, qemuDeb, "QEMU");

                    // Verify downloaded .deb file
                    String downloadedSha = calculateSHA256(qemuDeb);
                    if (!serverInfo.sha256.equalsIgnoreCase(downloadedSha)) {
                        throw new IOException("Downloaded QEMU .deb file is corrupt.");
                    }

                    // Unpack
                    updateStatus("Unpacking QEMU...");
                    unpackDeb(qemuDeb, qemuBinary);
                    qemuDeb.delete(); // Clean up

                    // Save new metadata
                    prefs.edit()
                        .putString(PREF_QEMU_VERSION, serverInfo.version)
                        .putString(PREF_QEMU_SHA256, serverInfo.sha256) // Note: this is hash of .deb, not binary
                        .apply();
                }

                // 4. Handle HAOS image
                File osImage = new File(getFilesDir(), OS_IMAGE_NAME);
                if (!osImage.exists()) {
                    File osImageXz = new File(getFilesDir(), OS_IMAGE_NAME_XZ);
                    downloadUrlToFile(HAOS_URL, osImageXz, "Home Assistant OS");
                    updateStatus("Decompressing OS image...");
                    decompressXz(osImageXz, osImage);
                    osImageXz.delete();
                }

                updateStatus("Setup complete. Ready to start VM.");
                runOnUiThread(this::checkFilesExist);

            } catch (Exception e) {
                updateStatus("Error during setup: " + e.getMessage());
                Log.e(TAG, "Error in download/setup thread", e);
                runOnUiThread(() -> downloadButton.setEnabled(true));
            }
        }).start();
    }

    private void unpackDeb(File debFile, File destinationBinary) throws IOException {
        try (ArArchiveInputStream arInput = new ArArchiveInputStream(new BufferedInputStream(new FileInputStream(debFile)))) {
            org.apache.commons.compress.archivers.ArchiveEntry entry;
            while ((entry = arInput.getNextEntry()) != null) {
                if (entry.getName().equals("data.tar.xz")) {
                    // We've found the data payload. Now we need to untar and un-xz it.
                    // The libraries are nested. XZ -> TAR -> Files
                    XZInputStream xzInput = new XZInputStream(arInput);
                    TarArchiveInputStream tarInput = new TarArchiveInputStream(xzInput);

                    org.apache.commons.compress.archivers.ArchiveEntry tarEntry;
                    while ((tarEntry = tarInput.getNextEntry()) != null) {
                        // The binary is at ./data/data/com.termux/files/usr/bin/qemu-system-aarch64
                        if (tarEntry.getName().endsWith("/" + QEMU_BINARY_NAME)) {
                            updateStatus("Extracting " + QEMU_BINARY_NAME + "...");
                            try (OutputStream out = new FileOutputStream(destinationBinary)) {
                                tarInput.transferTo(out);
                            }
                            tarInput.close();
                            return; // We're done
                        }
                    }
                    tarInput.close();
                }
            }
        }
        throw new IOException("Could not find " + QEMU_BINARY_NAME + " in .deb archive.");
    }

    private void startVm() {
        startButton.setEnabled(false);
        downloadButton.setEnabled(false);
        updateStatus("Starting VM...");

        new Thread(() -> {
            try {
                File qemuBinary = new File(getFilesDir(), QEMU_BINARY_NAME);
                File osImage = new File(getFilesDir(), OS_IMAGE_NAME);

                if (!qemuBinary.exists() || !osImage.exists()) {
                    throw new IOException("Required files not found. Please download them first.");
                }

                String qemuPath = qemuBinary.getAbsolutePath();
                String osImagePath = osImage.getAbsolutePath();

                // Construct the full command to be run as root
                String command = "chmod 755 " + qemuPath + " && " +
                                 qemuPath +
                                 " -m 2048" +
                                 " -M virt" +
                                 " -cpu cortex-a57" +
                                 " -smp 2" +
                                 " -hda " + osImagePath +
                                 " -netdev user,id=net0,hostfwd=tcp::8123-:8123" +
                                 " -device virtio-net-pci,netdev=net0" +
                                 " -vnc 0.0.0.0:0";

                updateStatus("Executing command as root...");
                Process suProcess = Runtime.getRuntime().exec(new String[]{"su", "-c", command});

                // We can optionally read the output/error streams here to show in the app

                suProcess.waitFor(); // Wait for the process to exit (or run in background)

                if (suProcess.exitValue() == 0) {
                     updateStatus("VM process started! Access HA on http://localhost:8123 or VNC on port 5900. App can be closed.");
                } else {
                    // This part may not be reached if QEMU runs indefinitely.
                    // Reading the error stream would be more useful.
                    updateStatus("VM process exited with an error.");
                }

            } catch (Exception e) {
                updateStatus("Error starting VM: " + e.getMessage());
                Log.e(TAG, "Error in startVm thread", e);
                runOnUiThread(() -> startButton.setEnabled(true));
            }
        }).start();
    }

    private PackageInfo getQemuPackageInfo() throws IOException {
        URL url = new URL(TERMUX_PACKAGES_FILE_URL);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.connect();
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("Failed to get Termux Packages file: " + connection.getResponseMessage());
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                String line;
                boolean foundPackage = false;
                PackageInfo info = new PackageInfo();
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (foundPackage) break;
                        continue;
                    }
                    if (line.equals("Package: qemu-system-aarch64-headless")) {
                        foundPackage = true;
                    }
                    if (foundPackage) {
                        if (line.startsWith("Filename: ")) info.filename = line.substring(10);
                        else if (line.startsWith("Version: ")) info.version = line.substring(9);
                        else if (line.startsWith("SHA256: ")) info.sha256 = line.substring(8);
                    }
                }
                if (info.filename != null && info.version != null && info.sha256 != null) return info;
            }
        } finally {
            connection.disconnect();
        }
        throw new IOException("Could not find qemu-system-aarch64-headless in index.");
    }

    private String calculateSHA256(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream fis = new FileInputStream(file)) {
            byte[] byteArray = new byte[1024];
            int bytesCount;
            while ((bytesCount = fis.read(byteArray)) != -1) {
                digest.update(byteArray, 0, bytesCount);
            }
        }
        byte[] bytes = digest.digest();
        StringBuilder sb = new StringBuilder();
        for (byte aByte : bytes) {
            sb.append(Integer.toString((aByte & 0xff) + 0x100, 16).substring(1));
        }
        return sb.toString();
    }

    private void downloadUrlToFile(String urlString, File file, String fileDescription) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.connect();
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("Server returned HTTP " + connection.getResponseCode() + " " + connection.getResponseMessage());
            }
            int fileLength = connection.getContentLength();
            try (InputStream input = connection.getInputStream(); OutputStream output = new FileOutputStream(file)) {
                byte[] data = new byte[4096];
                long total = 0;
                int count;
                while ((count = input.read(data)) != -1) {
                    total += count;
                    output.write(data, 0, count);
                    if (fileLength > 0) {
                        updateStatus("Downloading " + fileDescription + ": " + (int) (total * 100 / fileLength) + "%");
                    }
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private void updateStatus(final String message) {
        runOnUiThread(() -> statusTextView.setText(message));
    }

    private void decompressXz(File source, File dest) throws IOException {
        try (InputStream in = new XZInputStream(new FileInputStream(source)); OutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }
}
