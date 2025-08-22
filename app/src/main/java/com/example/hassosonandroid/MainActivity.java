package com.example.hassosonandroid;

import androidx.appcompat.app.AppCompatActivity;

import android.content.res.AssetManager;
import android.os.Bundle;
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

public class MainActivity extends AppCompatActivity {

    private static final String QEMU_BINARY_NAME = "qemu-system-aarch64";
    private static final String OS_IMAGE_NAME_XZ = "haos.qcow2.xz";
    private static final String OS_IMAGE_NAME = "haos.qcow2";

    private TextView statusTextView;
    private Button startButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusTextView = findViewById(R.id.textView);
        startButton = findViewById(R.id.start_button);

        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startVm();
            }
        });
    }

    private void startVm() {
        startButton.setEnabled(false);
        updateStatus("Starting VM setup...");

        // Run all file operations and QEMU execution on a background thread
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File filesDir = getFilesDir();
                    File qemuBinary = new File(filesDir, QEMU_BINARY_NAME);
                    File osImageXz = new File(filesDir, OS_IMAGE_NAME_XZ);
                    File osImage = new File(filesDir, OS_IMAGE_NAME);

                    // 1. Copy assets to internal storage
                    updateStatus("Copying assets...");
                    copyAssetToFile(QEMU_BINARY_NAME, qemuBinary);
                    copyAssetToFile(OS_IMAGE_NAME_XZ, osImageXz);

                    // 2. Make QEMU binary executable
                    updateStatus("Setting permissions...");
                    if (!qemuBinary.setExecutable(true)) {
                        throw new IOException("Failed to make QEMU binary executable");
                    }

                    // 3. Decompress OS image
                    updateStatus("Decompressing OS image...");
                    decompressXz(osImageXz, osImage);
                    osImageXz.delete(); // Clean up compressed file

                    // 4. Construct and run QEMU command
                    updateStatus("Starting QEMU...");
                    ProcessBuilder pb = new ProcessBuilder(
                            qemuBinary.getAbsolutePath(),
                            "-m", "2048", // 2GB RAM
                            "-M", "virt",
                            "-cpu", "cortex-a57",
                            "-smp", "2",
                            "-hda", osImage.getAbsolutePath(),
                            "-netdev", "user,id=net0,hostfwd=tcp::8123-:8123",
                            "-device", "virtio-net-pci,netdev=net0",
                            "-vnc", "0.0.0.0:0" // VNC server on all interfaces, port 5900
                    );
                    pb.redirectErrorStream(true);
                    pb.directory(filesDir);
                    Process process = pb.start();

                    // For this example, we don't capture output, just announce it's running
                    updateStatus("VM is running! Access HA on http://localhost:8123 or VNC on port 5900");

                } catch (IOException e) {
                    updateStatus("Error: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private void updateStatus(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusTextView.setText(message);
            }
        });
    }

    private void copyAssetToFile(String assetName, File destFile) throws IOException {
        AssetManager assetManager = getAssets();
        try (InputStream in = assetManager.open(assetName);
             OutputStream out = new FileOutputStream(destFile)) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
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
