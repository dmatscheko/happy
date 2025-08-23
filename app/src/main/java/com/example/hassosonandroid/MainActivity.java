package com.example.hassosonandroid;

import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.system.Os;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.CheckBox;
import android.widget.Toast;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.tukaani.xz.XZInputStream;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collections;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "HassOS";
    private static final String QEMU_BINARY_NAME = "qemu-system-aarch64";
    private static final String OS_IMAGE_NAME = "haos.qcow2";
    private static final String LIB_DIR_NAME = "lib";
    private static final String BIN_DIR_NAME = "bin";

    private static final String HAOS_URL = "https://github.com/home-assistant/operating-system/releases/download/12.3/haos_generic-aarch64-12.3.qcow2.xz";

    private TextView statusTextView;
    private Button downloadButton, startButton, clearCacheButton, deleteAllButton, terminateButton;
    private CheckBox runAsRootCheckBox;
    private SharedPreferences prefs;
    private Process qemuProcess;
    private boolean isRunningAsRoot = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusTextView = findViewById(R.id.textView);
        downloadButton = findViewById(R.id.download_button);
        startButton = findViewById(R.id.start_button);
        clearCacheButton = findViewById(R.id.clear_cache_button);
        deleteAllButton = findViewById(R.id.delete_all_button);
        terminateButton = findViewById(R.id.terminate_button);
        runAsRootCheckBox = findViewById(R.id.run_as_root_checkbox);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        downloadButton.setOnClickListener(v -> downloadFiles());
        startButton.setOnClickListener(v -> startVm());
        clearCacheButton.setOnClickListener(v -> clearCache());
        deleteAllButton.setOnClickListener(v -> confirmDeleteAllData());
        terminateButton.setOnClickListener(v -> terminateVm());

        checkFilesExistAndUpdateUi();
    }

    private void downloadFiles() {
        setAllButtonsEnabled(false);
        new Thread(() -> {
            PackageManager.StatusListener listener = new PackageManager.StatusListener() {
                @Override
                public void onStatusUpdate(String message) {
                    updateStatus(message);
                }

                @Override
                public void onFinalMessage(String message) {
                    updateStatus(message);
                    // Now that packages are set up, download the OS image
                    downloadOsImage();
                }

                @Override
                public void onError(String message, Throwable e) {
                    updateStatus(message + ": " + e.getMessage());
                    Log.e(TAG, message, e);
                    runOnUiThread(MainActivity.this::checkFilesExistAndUpdateUi);
                }
            };

            PackageManager packageManager = new PackageManager(getApplicationContext(), listener);
            packageManager.installPackages(Collections.singletonList("qemu-system-aarch64-headless"));
        }).start();
    }

    private void downloadOsImage() {
        new Thread(() -> {
            try {
                File osImage = new File(getFilesDir(), OS_IMAGE_NAME);
                if (!osImage.exists()) {
                    File osImageXz = new File(getFilesDir(), OS_IMAGE_NAME + ".xz");
                    downloadUrlToFile(HAOS_URL, osImageXz, "Home Assistant OS");
                    decompressXz(osImageXz, osImage);
                    osImageXz.delete();
                }
                updateStatus("Setup complete! Ready to start VM.");
            } catch (Exception e) {
                updateStatus("Error during OS image download: " + e.getMessage());
                Log.e(TAG, "Error in OS image download thread", e);
            } finally {
                runOnUiThread(this::checkFilesExistAndUpdateUi);
            }
        }).start();
    }

    private void startVm() {
        setAllButtonsEnabled(false);
        updateStatus("Starting VM...");
        new Thread(() -> {
            isRunningAsRoot = runAsRootCheckBox.isChecked();
            try {
                File qemuBinary = new File(binDir(), QEMU_BINARY_NAME);
                File osImage = new File(getFilesDir(), OS_IMAGE_NAME);
                if (!qemuBinary.exists() || !osImage.exists()) throw new IOException("Required files not found.");

                File pidFile = new File(getFilesDir(), "qemu.pid");
                if(pidFile.exists()) pidFile.delete();

                String command = "chmod -R 755 " + binDir().getAbsolutePath() + " && " +
                                 "export PATH=" + binDir().getAbsolutePath() + ":$PATH && " +
                                 "export LD_LIBRARY_PATH=" + libDir().getAbsolutePath() + " && " +
                                 qemuBinary.getAbsolutePath() +
                                 " -m 2048 -M virt -cpu cortex-a57 -smp 2" +
                                 " -hda " + osImage.getAbsolutePath() +
                                 " -netdev user,id=net0,hostfwd=tcp::8123-:8123" +
                                 " -device virtio-net-pci,netdev=net0 -vnc 0.0.0.0:0 -display none" +
                                 " -pidfile " + pidFile.getAbsolutePath();

                if (isRunningAsRoot) {
                    // command += " -accel kvm";
                    qemuProcess = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
                } else {
                    qemuProcess = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
                }

                runOnUiThread(() -> {
                    startButton.setEnabled(false);
                    terminateButton.setEnabled(true);
                });

                new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(qemuProcess.getInputStream()))) {
                        String line; while ((line = reader.readLine()) != null) Log.d(TAG, "QEMU stdout: " + line);
                    } catch (IOException e) { Log.e(TAG, "Error reading QEMU stdout", e); }
                }).start();

                final StringBuilder errorOutput = new StringBuilder();
                new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(qemuProcess.getErrorStream()))) {
                        String line; while ((line = reader.readLine()) != null) {
                            Log.e(TAG, "QEMU stderr: " + line);
                            errorOutput.append(line).append("\n");
                        }
                    } catch (IOException e) { Log.e(TAG, "Error reading QEMU stderr", e); }
                }).start();

                int exitValue = qemuProcess.waitFor();
                if (exitValue != 0) {
                    updateStatus("VM process exited with error code " + exitValue + ".\n" + "Error: " + errorOutput.toString());
                } else {
                    updateStatus("VM process started successfully (but has exited).");
                }
            } catch (Exception e) {
                updateStatus("Error starting VM: " + e.getMessage());
                Log.e(TAG, "Error in startVm thread", e);
            } finally {
                qemuProcess = null;
                runOnUiThread(() -> {
                    checkFilesExistAndUpdateUi();
                    terminateButton.setEnabled(false);
                });
            }
        }).start();
    }

    private void terminateVm() {
        new Thread(() -> {
            File pidFile = new File(getFilesDir(), "qemu.pid");
            if (pidFile.exists()) {
                try {
                    String pid;
                    if (isRunningAsRoot) {
                        Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat " + pidFile.getAbsolutePath()});
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                            pid = reader.readLine();
                        }
                        p.waitFor();
                    } else {
                        pid = new String(Files.readAllBytes(pidFile.toPath())).trim();
                    }

                    if (pid != null && !pid.isEmpty()) {
                        String command = "kill -9 " + pid;
                        if (isRunningAsRoot) {
                            Runtime.getRuntime().exec(new String[]{"su", "-c", command}).waitFor();
                        } else {
                            Runtime.getRuntime().exec(new String[]{"sh", "-c", command}).waitFor();
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error killing process with pid from pidfile", e);
                    // Fallback to destroying the process handle if reading pid file fails
                    if (qemuProcess != null) {
                        qemuProcess.destroy();
                    }
                } finally {
                    pidFile.delete();
                }
            } else {
                // Fallback if pidfile doesn't exist for some reason
                if (qemuProcess != null) {
                    qemuProcess.destroy();
                }
            }
        }).start();
    }

    private void decompressXz(File source, File dest) throws IOException {
        updateStatus("Unpacking " + source.getName() + "...");
        try (InputStream in = new XZInputStream(new FileInputStream(source)); OutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
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

    private void clearCache() {
        File cacheDir = getCacheDir();
        if (cacheDir.exists()) {
            for(File file: cacheDir.listFiles()) file.delete();
        }
        File osImageXz = new File(getFilesDir(), OS_IMAGE_NAME + ".xz");
        if(osImageXz.exists()) osImageXz.delete();
        Toast.makeText(this, "Cache cleared.", Toast.LENGTH_SHORT).show();
    }

    private void confirmDeleteAllData() {
        new AlertDialog.Builder(this)
            .setTitle("Delete All Data?")
            .setMessage("This will delete QEMU, its libraries, and the Home Assistant OS image. All Home Assistant data will be lost. Are you sure?")
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton(android.R.string.yes, (dialog, whichButton) -> deleteAllData())
            .setNegativeButton(android.R.string.no, null).show();
    }

    private void deleteAllData() {
        // Stop the VM if it is running
        if (qemuProcess != null) {
            terminateVm();
        }

        // Delete specific files and directories, excluding the cache.
        deleteRecursive(new File(getFilesDir(), OS_IMAGE_NAME));
        deleteRecursive(binDir());
        deleteRecursive(libDir());

        // Clear related preferences
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith("version_")) { // Assuming package versions are stored with this prefix
                editor.remove(key);
            }
        }
        editor.apply();

        Toast.makeText(this, "All data except cache has been deleted.", Toast.LENGTH_SHORT).show();
        checkFilesExistAndUpdateUi();
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    void deleteRecursive(File fileOrDirectory) {
        Path path = fileOrDirectory.toPath();
        // Use NOFOLLOW_LINKS to check the link itself, not the target
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }

        try {
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                    for (Path entry : stream) {
                        deleteRecursive(entry.toFile());
                    }
                }
            }
            Files.delete(path);
        } catch (IOException e) {
            Log.e(TAG, "Failed to delete " + path, e);
        }
    }

    private boolean isDirectoryNotEmpty(File directory) {
        if (directory != null && directory.exists() && directory.isDirectory()) {
            String[] files = directory.list();
            return files != null && files.length > 0;
        }
        return false;
    }

    private void checkFilesExistAndUpdateUi() {
        File qemuBinary = new File(binDir(), QEMU_BINARY_NAME);
        File osImage = new File(getFilesDir(), OS_IMAGE_NAME);
        File osImageXz = new File(getFilesDir(), OS_IMAGE_NAME + ".xz");

        boolean startable = qemuBinary.exists() && osImage.exists();
        boolean isRunning = qemuProcess != null;

        // Data exists if there's more than just the 'profileInstalled' file.
        File[] files = getFilesDir().listFiles();
        boolean dataExists = (files != null && files.length > 1) || (files != null && files.length == 1 && !files[0].getName().equals("profileInstalled"));
        if (!dataExists) {
            // Also check subdirectories, as the root might only have bin/lib which could be empty.
            dataExists = isDirectoryNotEmpty(binDir()) || isDirectoryNotEmpty(libDir()) || osImage.exists();
        }

        boolean cacheExists = isDirectoryNotEmpty(getCacheDir()) || osImageXz.exists();

        startButton.setEnabled(startable && !isRunning);
        terminateButton.setEnabled(isRunning);
        deleteAllButton.setEnabled(dataExists);
        clearCacheButton.setEnabled(cacheExists);
        downloadButton.setEnabled(!isRunning);
        runAsRootCheckBox.setEnabled(!isRunning);


        if (isRunning) {
            updateStatus("VM is running.");
        } else if (startable) {
            updateStatus("Ready. You can check for updates or start VM.");
        } else {
            updateStatus("Please download required files.");
        }
    }

    private File binDir() { return new File(getFilesDir(), BIN_DIR_NAME); }
    private File libDir() { return new File(getFilesDir(), LIB_DIR_NAME); }

    private void setAllButtonsEnabled(boolean enabled) {
        downloadButton.setEnabled(enabled);
        startButton.setEnabled(enabled);
        clearCacheButton.setEnabled(enabled);
        deleteAllButton.setEnabled(enabled);
        terminateButton.setEnabled(enabled);
        runAsRootCheckBox.setEnabled(enabled);
    }
}
