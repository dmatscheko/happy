package com.example.hassosonandroid;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import org.tukaani.xz.XZInputStream;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "HassOS";
    private static final String QEMU_BINARY_NAME = "libqemu.so";
    private static final String OS_IMAGE_NAME_XZ = "haos.qcow2.xz";
    private static final String OS_IMAGE_NAME = "haos.qcow2";

    private static final String HAOS_URL = "https://github.com/home-assistant/operating-system/releases/download/12.3/haos_generic-aarch64-12.3.qcow2.xz";

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
        File osImage = new File(getFilesDir(), OS_IMAGE_NAME);
        // QEMU binary is checked implicitly by its existence in the native lib dir.
        // We just need to check for the OS image.
        if (osImage.exists()) {
            startButton.setEnabled(true);
            downloadButton.setEnabled(false); // Can be re-enabled if download fails
            updateStatus("Ready to start VM.");
        } else {
            startButton.setEnabled(false);
            downloadButton.setEnabled(true);
            updateStatus("Please download the Home Assistant OS image.");
        }
    }

    private void downloadFiles() {
        downloadButton.setEnabled(false);
        updateStatus("Downloading Home Assistant OS...");

        new Thread(() -> {
            try {
                File osImage = new File(getFilesDir(), OS_IMAGE_NAME);
                if (!osImage.exists()) {
                    File osImageXz = new File(getFilesDir(), OS_IMAGE_NAME_XZ);
                    downloadUrlToFile(HAOS_URL, osImageXz, "Home Assistant OS");
                    updateStatus("Decompressing OS image...");
                    decompressXz(osImageXz, osImage);
                    osImageXz.delete();
                }
                updateStatus("Download complete. Ready to start VM.");
                runOnUiThread(this::checkFilesExist);

            } catch (Exception e) {
                updateStatus("Error downloading OS image: " + e.getMessage());
                Log.e(TAG, "Error in download thread", e);
                runOnUiThread(() -> downloadButton.setEnabled(true));
            }
        }).start();
    }

    private void startVm() {
        startButton.setEnabled(false);
        downloadButton.setEnabled(false);
        updateStatus("Starting VM...");

        new Thread(() -> {
            try {
                File qemuBinary = new File(getApplicationInfo().nativeLibraryDir, QEMU_BINARY_NAME);
                if (!qemuBinary.exists()) {
                    throw new IOException("QEMU binary not found in native library directory. Please check the build process.");
                }

                ProcessBuilder pb = new ProcessBuilder(
                        qemuBinary.getAbsolutePath(),
                        "-m", "2048",
                        "-M", "virt",
                        "-cpu", "cortex-a57",
                        "-smp", "2",
                        "-hda", new File(getFilesDir(), OS_IMAGE_NAME).getAbsolutePath(),
                        "-netdev", "user,id=net0,hostfwd=tcp::8123-:8123",
                        "-device", "virtio-net-pci,netdev=net0",
                        "-vnc", "0.0.0.0:0"
                );
                pb.redirectErrorStream(true);
                pb.directory(getFilesDir());
                Process process = pb.start();

                updateStatus("VM is running! Access HA on http://localhost:8123 or VNC on port 5900");

            } catch (Exception e) {
                updateStatus("Error starting VM: " + e.getMessage());
                Log.e(TAG, "Error in startVm thread", e);
                runOnUiThread(() -> startButton.setEnabled(true));
            }
        }).start();
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
