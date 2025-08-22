package com.example.hassosonandroid;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

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
    private Button clearCacheButton;
    private Button deleteAllButton;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusTextView = findViewById(R.id.textView);
        downloadButton = findViewById(R.id.download_button);
        startButton = findViewById(R.id.start_button);
        clearCacheButton = findViewById(R.id.clear_cache_button);
        deleteAllButton = findViewById(R.id.delete_all_button);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        downloadButton.setOnClickListener(v -> downloadFiles());
        startButton.setOnClickListener(v -> startVm());
        clearCacheButton.setOnClickListener(v -> clearCache());
        deleteAllButton.setOnClickListener(v -> confirmDeleteAllData());


        checkFilesExist();
    }

    private void clearCache() {
        File qemuDeb = new File(getFilesDir(), QEMU_DEB_NAME);
        File osImageXz = new File(getFilesDir(), OS_IMAGE_NAME_XZ);
        if (qemuDeb.exists()) qemuDeb.delete();
        if (osImageXz.exists()) osImageXz.delete();
        Toast.makeText(this, "Cache cleared.", Toast.LENGTH_SHORT).show();
        checkFilesExist();
    }

    private void confirmDeleteAllData() {
        new AlertDialog.Builder(this)
            .setTitle("Delete All Data?")
            .setMessage("This will delete the QEMU binary and the Home Assistant OS image. All Home Assistant data will be lost. Are you sure?")
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton(android.R.string.yes, (dialog, whichButton) -> deleteAllData())
            .setNegativeButton(android.R.string.no, null).show();
    }

    private void deleteAllData() {
        File qemuBinary = new File(getFilesDir(), QEMU_BINARY_NAME);
        File osImage = new File(getFilesDir(), OS_IMAGE_NAME);
        if (qemuBinary.exists()) qemuBinary.delete();
        if (osImage.exists()) osImage.delete();

        // Clear saved version info
        prefs.edit().remove(PREF_QEMU_VERSION).remove(PREF_QEMU_SHA256).apply();

        Toast.makeText(this, "All data deleted.", Toast.LENGTH_SHORT).show();
        checkFilesExist();
    }


    private void checkFilesExist() {
        File qemuBinary = new File(getFilesDir(), QEMU_BINARY_NAME);
        File osImage = new File(getFilesDir(), OS_IMAGE_NAME);

        boolean allExist = qemuBinary.exists() && osImage.exists();
        startButton.setEnabled(allExist);
        deleteAllButton.setEnabled(allExist);
        downloadButton.setEnabled(true); // Can always check for updates

        if (allExist) {
            updateStatus("Ready. You can check for updates or start VM.");
        } else {
            updateStatus("Please download required files.");
        }
    }

    // ... (rest of the file is the same as the previous version)
    private void downloadFiles() {
        downloadButton.setEnabled(false);
        updateStatus("Checking for new versions...");

        new Thread(() -> {
            try {
                PackageInfo serverInfo = getQemuPackageInfo();
                File qemuDeb = new File(getFilesDir(), QEMU_DEB_NAME);
                File qemuBinary = new File(getFilesDir(), QEMU_BINARY_NAME);
                String localVersion = prefs.getString(PREF_QEMU_VERSION, null);

                boolean needsDownload = false;
                if (!qemuBinary.exists()) {
                    updateStatus("QEMU binary not found. Downloading...");
                    needsDownload = true;
                } else if (!serverInfo.version.equals(localVersion)) {
                    updateStatus("New version of QEMU found. Downloading...");
                    needsDownload = true;
                } else {
                    updateStatus("QEMU is up-to-date.");
                }

                if (needsDownload) {
                    String qemuUrl = TERMUX_REPO_URL + serverInfo.filename;
                    downloadUrlToFile(qemuUrl, qemuDeb, "QEMU");
                    String downloadedSha = calculateSHA256(qemuDeb);
                    if (!serverInfo.sha256.equalsIgnoreCase(downloadedSha)) {
                        throw new IOException("Downloaded QEMU .deb file is corrupt.");
                    }
                    updateStatus("Unpacking QEMU...");
                    unpackDeb(qemuDeb, qemuBinary);
                    qemuDeb.delete();
                    prefs.edit()
                        .putString(PREF_QEMU_VERSION, serverInfo.version)
                        .putString(PREF_QEMU_SHA256, serverInfo.sha256)
                        .apply();
                }

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
                    XZInputStream xzInput = new XZInputStream(arInput);
                    TarArchiveInputStream tarInput = new TarArchiveInputStream(xzInput);
                    org.apache.commons.compress.archivers.ArchiveEntry tarEntry;
                    while ((tarEntry = tarInput.getNextEntry()) != null) {
                        if (tarEntry.getName().endsWith("/" + QEMU_BINARY_NAME)) {
                            updateStatus("Extracting " + QEMU_BINARY_NAME + "...");
                            try (OutputStream out = new FileOutputStream(destinationBinary)) {
                                tarInput.transferTo(out);
                            }
                            tarInput.close();
                            return;
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

                String command = "chmod 755 " + qemuPath + " && " + qemuPath + " -m 2048" + " -M virt" + " -cpu cortex-a57" + " -smp 2" + " -hda " + osImagePath + " -netdev user,id=net0,hostfwd=tcp::8123-:8123" + " -device virtio-net-pci,netdev=net0" + " -vnc 0.0.0.0:0";

                updateStatus("Executing command as root...");
                Process suProcess = Runtime.getRuntime().exec(new String[]{"su", "-c", command});

                new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(suProcess.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            Log.d(TAG, "QEMU stdout: " + line);
                        }
                    } catch (IOException e) {
                        Log.e(TAG, "Error reading QEMU stdout", e);
                    }
                }).start();

                final StringBuilder errorOutput = new StringBuilder();
                new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(suProcess.getErrorStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            Log.e(TAG, "QEMU stderr: " + line);
                            errorOutput.append(line).append("\n");
                        }
                    } catch (IOException e) {
                        Log.e(TAG, "Error reading QEMU stderr", e);
                    }
                }).start();

                int exitValue = suProcess.waitFor();

                if (exitValue == 0) {
                     updateStatus("VM process started! Access HA on http://localhost:8123 or VNC on port 5900. App can be closed.");
                } else {
                    updateStatus("VM process exited with error code " + exitValue + ".\n" + "Error: " + errorOutput.toString());
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
