package com.example.hassosonandroid;

import androidx.appcompat.app.AppCompatActivity;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import org.tukaani.xz.XZInputStream;

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
    private static final String QEMU_BINARY_NAME = "qemu-system-aarch64-headless"; // Corrected name
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
        File qemuDeb = new File(getFilesDir(), QEMU_DEB_NAME);
        File osImage = new File(getFilesDir(), OS_IMAGE_NAME);

        if (qemuDeb.exists() && osImage.exists()) {
            startButton.setEnabled(true);
            downloadButton.setEnabled(true); // Can always check for new versions
            updateStatus("Ready. You can check for updates or start VM.");
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
                // 1. Get latest package info from server
                PackageInfo serverInfo = getQemuPackageInfo();
                File qemuDeb = new File(getFilesDir(), QEMU_DEB_NAME);

                // 2. Get local package info from prefs
                String localVersion = prefs.getString(PREF_QEMU_VERSION, null);

                // 3. Decision logic
                boolean needsDownload = false;
                if (!qemuDeb.exists()) {
                    updateStatus("QEMU package not found. Downloading...");
                    needsDownload = true;
                } else if (!serverInfo.version.equals(localVersion)) {
                    updateStatus("New version of QEMU found. Downloading...");
                    needsDownload = true;
                } else {
                    updateStatus("Local QEMU is up-to-date. Validating checksum...");
                    String localSha = calculateSHA256(qemuDeb);
                    if (!serverInfo.sha256.equalsIgnoreCase(localSha)) {
                        updateStatus("Checksum mismatch! Redownloading...");
                        needsDownload = true;
                    } else {
                        updateStatus("QEMU is validated and up-to-date.");
                    }
                }

                if (needsDownload) {
                    String qemuUrl = TERMUX_REPO_URL + serverInfo.filename;
                    downloadUrlToFile(qemuUrl, qemuDeb, "QEMU");
                    // Verify after download
                    String downloadedSha = calculateSHA256(qemuDeb);
                    if (!serverInfo.sha256.equalsIgnoreCase(downloadedSha)) {
                        throw new IOException("Downloaded QEMU file is corrupt.");
                    }
                    // Save new metadata
                    prefs.edit()
                        .putString(PREF_QEMU_VERSION, serverInfo.version)
                        .putString(PREF_QEMU_SHA256, serverInfo.sha256)
                        .apply();
                }

                // Now handle HAOS (simpler check, no versioning)
                File osImage = new File(getFilesDir(), OS_IMAGE_NAME);
                if (!osImage.exists()) {
                    File osImageXz = new File(getFilesDir(), OS_IMAGE_NAME_XZ);
                    downloadUrlToFile(HAOS_URL, osImageXz, "Home Assistant OS");
                    updateStatus("Decompressing OS image...");
                    decompressXz(osImageXz, osImage);
                    osImageXz.delete();
                }

                updateStatus("Ready. You can start the VM.");
                runOnUiThread(this::checkFilesExist);

            } catch (Exception e) {
                updateStatus("Error: " + e.getMessage());
                Log.e(TAG, "Error in download/validation thread", e);
                runOnUiThread(() -> downloadButton.setEnabled(true));
            }
        }).start();
    }

    private PackageInfo getQemuPackageInfo() throws IOException {
        // ... (This method is already implemented from the previous step)
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

    private void startVm() {
        // ... (This logic is largely the same)
        new Thread(() -> {
             try {
                File filesDir = getFilesDir();
                File qemuDeb = new File(filesDir, QEMU_DEB_NAME);
                File qemuBinary = new File(filesDir, QEMU_BINARY_NAME);

                if (!qemuBinary.exists()) {
                     updateStatus("Extracting QEMU... (Not implemented, creating placeholder)");
                     if(!qemuDeb.exists()) throw new IOException("QEMU deb file not found.");
                     new FileOutputStream(qemuBinary).close();
                }
                if (!qemuBinary.setExecutable(true)) {
                    throw new IOException("Failed to make QEMU binary executable");
                }

                updateStatus("Starting QEMU...");
                ProcessBuilder pb = new ProcessBuilder(
                        qemuBinary.getAbsolutePath(),
                        "-m", "2048", "-M", "virt", "-cpu", "cortex-a57", "-smp", "2",
                        "-hda", new File(filesDir, OS_IMAGE_NAME).getAbsolutePath(),
                        "-netdev", "user,id=net0,hostfwd=tcp::8123-:8123",
                        "-device", "virtio-net-pci,netdev=net0",
                        "-vnc", "0.0.0.0:0"
                );
                pb.redirectErrorStream(true);
                pb.directory(filesDir);
                pb.start();
                updateStatus("VM is running! Access HA on http://localhost:8123 or VNC on port 5900");
             } catch (Exception e) {
                updateStatus("Error starting VM: " + e.getMessage());
                Log.e(TAG, "Error in startVm thread", e);
             }
        }).start();
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
        // ... (This method is the same as before)
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

    private void updateStatus(final String message) { runOnUiThread(() -> statusTextView.setText(message)); }

    private void decompressXz(File source, File dest) throws IOException {
        // ... (This method is the same as before)
        try (InputStream in = new XZInputStream(new FileInputStream(source)); OutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }
}
