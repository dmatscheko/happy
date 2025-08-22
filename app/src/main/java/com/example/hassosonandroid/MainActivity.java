package com.example.hassosonandroid;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
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

public class MainActivity extends AppCompatActivity {

    private static final String QEMU_DEB_NAME = "qemu.deb";
    private static final String QEMU_BINARY_NAME = "qemu-system-aarch64";
    private static final String OS_IMAGE_NAME_XZ = "haos.qcow2.xz";
    private static final String OS_IMAGE_NAME = "haos.qcow2";

    private static final String HAOS_URL = "https://github.com/home-assistant/operating-system/releases/download/12.3/haos_generic-aarch64-12.3.qcow2.xz";
    private static final String TERMUX_REPO_URL = "https://packages-cf.termux.dev/dists/stable/main/binary-aarch64/";
    private static final String TERMUX_PACKAGES_FILE_URL = TERMUX_REPO_URL + "Packages";


    private TextView statusTextView;
    private Button downloadButton;
    private Button startButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusTextView = findViewById(R.id.textView);
        downloadButton = findViewById(R.id.download_button);
        startButton = findViewById(R.id.start_button);

        downloadButton.setOnClickListener(v -> downloadFiles());
        startButton.setOnClickListener(v -> startVm());

        checkFilesExist();
    }

    private void checkFilesExist() {
        File filesDir = getFilesDir();
        File qemuBinary = new File(filesDir, QEMU_BINARY_NAME);
        File osImage = new File(filesDir, OS_IMAGE_NAME);

        if (qemuBinary.exists() && osImage.exists()) {
            startButton.setEnabled(true);
            downloadButton.setEnabled(false);
            updateStatus("Ready to start VM.");
        } else {
            startButton.setEnabled(false);
            downloadButton.setEnabled(true);
            updateStatus("Please download required files.");
        }
    }

    private void downloadFiles() {
        downloadButton.setEnabled(false);
        updateStatus("Starting downloads...");

        new Thread(() -> {
            try {
                File filesDir = getFilesDir();
                // Download HAOS
                if (!new File(filesDir, OS_IMAGE_NAME).exists()) {
                    updateStatus("Downloading Home Assistant OS...");
                    File osImageXz = new File(filesDir, OS_IMAGE_NAME_XZ);
                    downloadUrlToFile(HAOS_URL, osImageXz, "Home Assistant OS");
                }

                // Download QEMU by first finding its URL
                if (!new File(filesDir, QEMU_DEB_NAME).exists()) {
                    updateStatus("Finding QEMU package URL...");
                    String qemuUrl = getQemuDebUrl();
                    updateStatus("Downloading QEMU...");
                    File qemuDeb = new File(filesDir, QEMU_DEB_NAME);
                    downloadUrlToFile(qemuUrl, qemuDeb, "QEMU");
                }


                updateStatus("Downloads complete. Ready to start.");
                runOnUiThread(() -> {
                    checkFilesExist(); // Re-check to enable start button etc.
                });

            } catch (IOException e) {
                updateStatus("Error downloading files: " + e.getMessage());
                e.printStackTrace();
                runOnUiThread(() -> downloadButton.setEnabled(true));
            }
        }).start();
    }

    private String getQemuDebUrl() throws IOException {
        URL url = new URL(TERMUX_PACKAGES_FILE_URL);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.connect();

        if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
            throw new IOException("Failed to get Termux Packages file: " + connection.getResponseMessage());
        }

        try (InputStream input = connection.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(input))) {
            String line;
            boolean foundPackage = false;
            while ((line = reader.readLine()) != null) {
                if (line.equals("Package: qemu-system-aarch64")) {
                    foundPackage = true;
                }
                if (foundPackage && line.startsWith("Filename: ")) {
                    String filename = line.substring("Filename: ".length());
                    return TERMUX_REPO_URL + filename;
                }
                if (foundPackage && line.isEmpty()) {
                    // Reached end of package block without finding filename
                    break;
                }
            }
        } finally {
            connection.disconnect();
        }

        throw new IOException("Could not find qemu-system-aarch64 package in repository index.");
    }


    private void startVm() {
        // ... (The startVm logic remains largely the same, but for clarity I'll include it)
        startButton.setEnabled(false);
        downloadButton.setEnabled(false);
        updateStatus("Starting VM setup...");

        new Thread(() -> {
            try {
                File filesDir = getFilesDir();
                File qemuDeb = new File(filesDir, QEMU_DEB_NAME);
                File qemuBinary = new File(filesDir, QEMU_BINARY_NAME);
                File osImageXz = new File(filesDir, OS_IMAGE_NAME_XZ);
                File osImage = new File(filesDir, OS_IMAGE_NAME);

                // 1. Extract QEMU from .deb file (Placeholder)
                if (!qemuBinary.exists()) {
                     updateStatus("Extracting QEMU... (Not implemented, creating placeholder)");
                     if(!qemuDeb.exists()) throw new IOException("QEMU deb file not found.");
                     new FileOutputStream(qemuBinary).close();
                }

                // 2. Make QEMU binary executable
                updateStatus("Setting permissions...");
                if (!qemuBinary.setExecutable(true)) {
                    throw new IOException("Failed to make QEMU binary executable");
                }

                // 3. Decompress OS image
                if (!osImage.exists()) {
                    updateStatus("Decompressing OS image...");
                    if(!osImageXz.exists()) throw new IOException("OS image xz file not found.");
                    decompressXz(osImageXz, osImage);
                    osImageXz.delete();
                }

                // 4. Construct and run QEMU command
                updateStatus("Starting QEMU...");
                ProcessBuilder pb = new ProcessBuilder(
                        qemuBinary.getAbsolutePath(),
                        "-m", "2048",
                        "-M", "virt",
                        "-cpu", "cortex-a57",
                        "-smp", "2",
                        "-hda", osImage.getAbsolutePath(),
                        "-netdev", "user,id=net0,hostfwd=tcp::8123-:8123",
                        "-device", "virtio-net-pci,netdev=net0",
                        "-vnc", "0.0.0.0:0"
                );
                pb.redirectErrorStream(true);
                pb.directory(filesDir);
                Process process = pb.start();

                updateStatus("VM is running! Access HA on http://localhost:8123 or VNC on port 5900");

            } catch (IOException e) {
                updateStatus("Error starting VM: " + e.getMessage());
                e.printStackTrace();
                runOnUiThread(() -> {
                    startButton.setEnabled(true);
                    downloadButton.setEnabled(true);
                });
            }
        }).start();
    }

    private void downloadUrlToFile(String urlString, File file, String fileDescription) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.connect();

        if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
            throw new IOException("Server returned HTTP " + connection.getResponseCode() + " " + connection.getResponseMessage());
        }

        int fileLength = connection.getContentLength();

        try (InputStream input = connection.getInputStream();
             OutputStream output = new FileOutputStream(file)) {

            byte[] data = new byte[4096];
            long total = 0;
            int count;
            while ((count = input.read(data)) != -1) {
                total += count;
                output.write(data, 0, count);
                if (fileLength > 0) {
                    final int progress = (int) (total * 100 / fileLength);
                    updateStatus("Downloading " + fileDescription + ": " + progress + "%");
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
        try (InputStream in = new XZInputStream(new FileInputStream(source));
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }
}
