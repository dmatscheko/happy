package com.example.hassosonandroid;

import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.CheckBox;
import android.widget.Toast;

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
import java.util.Arrays;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;


public class MainActivity extends AppCompatActivity {

    private static final String TAG = "HassOS";
    private static final String QEMU_BINARY_PATH = "usr/bin/qemu-system-aarch64";
    private static final String OS_IMAGE_PATH = "haos.qcow2";
    private static final String AAVMF_CODE_PATH = "usr/share/AAVMF/AAVMF_CODE.no-secboot.fd";
    private static final String AAVMF_VARS_TEMPLATE_PATH = "usr/share/AAVMF/AAVMF_VARS.fd";
    private static final String AAVMF_VARS_PATH = "AAVMF_VARS.writable.fd";

    private TextView statusTextView;
    private Button downloadButton, startButton, clearCacheButton, deleteAllButton, terminateButton;
    private CheckBox runAsRootCheckBox;
    private Process qemuProcess;
    private FileUtils fileUtils;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        fileUtils = new FileUtils(getApplicationContext());

        statusTextView = findViewById(R.id.textView);
        downloadButton = findViewById(R.id.download_button);
        startButton = findViewById(R.id.start_button);
        clearCacheButton = findViewById(R.id.clear_cache_button);
        deleteAllButton = findViewById(R.id.delete_all_button);
        terminateButton = findViewById(R.id.terminate_button);
        runAsRootCheckBox = findViewById(R.id.run_as_root_checkbox);

        downloadButton.setOnClickListener(v -> downloadFiles());
        startButton.setOnClickListener(v -> startVm());
        clearCacheButton.setOnClickListener(v -> clearCache());
        deleteAllButton.setOnClickListener(v -> confirmDeleteAllData());
        terminateButton.setOnClickListener(v -> terminateVm());

        checkFilesExistAndUpdateUi();
    }


    private Process run(String command) throws Exception{
        boolean isRunningAsRoot = runAsRootCheckBox.isChecked();
        if (isRunningAsRoot) {
            return Runtime.getRuntime().exec(new String[]{"su", "-c", command});
        } else {
            return Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
        }
    }


    public String getLatestHaosDownloadUrl() throws Exception {
        String apiUrl = "https://api.github.com/repos/home-assistant/operating-system/releases/latest";
        URL url = new URL(apiUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json");

        if (conn.getResponseCode() != 200) {
            throw new RuntimeException("Failed : HTTP error code : " + conn.getResponseCode());
        }

        BufferedReader br = new BufferedReader(new InputStreamReader((conn.getInputStream())));
        StringBuilder response = new StringBuilder();
        String output;
        while ((output = br.readLine()) != null) {
            response.append(output);
        }
        conn.disconnect();

        JSONObject json = new JSONObject(response.toString());
        String tagName = json.getString("tag_name");
        JSONArray assets = json.getJSONArray("assets");

        String targetAssetName = "haos_generic-aarch64-" + tagName + ".qcow2.xz";
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            String name = asset.getString("name");
            if (name.equals(targetAssetName)) {
                return asset.getString("browser_download_url");
            }
        }

        throw new RuntimeException("Asset not found: " + targetAssetName);
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
                    // Now that packages and firmware are set up, download the OS image
                    downloadOsImage();
                }

                @Override
                public void onError(String message, Throwable e) {
                    updateStatus(message + ": " + e.getMessage());
                    Log.e(TAG, message, e);
                    runOnUiThread(MainActivity.this::checkFilesExistAndUpdateUi);
                }
            };

            PackageManager packageManager = new PackageManager(fileUtils, listener);
            packageManager.installPackages(Arrays.asList("qemu-system-aarch64", "qemu-efi-aarch64"));
        }).start();
    }


    private void downloadOsImage() {
        new Thread(() -> {
            try {
                File osImage = new File(fileUtils.filesDir(), OS_IMAGE_PATH);
                // if (!osImage.exists()) {
                String url = getLatestHaosDownloadUrl();
                String fileName = url.substring(url.lastIndexOf('/') + 1);
                File osImageXz = new File(fileUtils.cacheDir(), fileName);
                FileUtils.downloadUrlToFile(url, osImageXz, false, message -> updateStatus(message));
                decompressXz(osImageXz, osImage);
                // }
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
            try {
                File qemuBinary = new File(fileUtils.filesDir(), QEMU_BINARY_PATH);
                File osImage = new File(fileUtils.filesDir(), OS_IMAGE_PATH);
                File aavmfCodeFd = new File(fileUtils.filesDir(), AAVMF_CODE_PATH);
                File aavmfVarsTemplate = new File(fileUtils.filesDir(), AAVMF_VARS_TEMPLATE_PATH);
                File aavmfVarsFd = new File(fileUtils.filesDir(), AAVMF_VARS_PATH);

                if (!qemuBinary.exists() || !osImage.exists() || !aavmfCodeFd.exists() || !aavmfVarsTemplate.exists()) {
                    throw new IOException("Required files not found for starting VM. Make sure QEMU and firmware are installed.");
                }

                // Prepare the writable AAVMF_VARS.fd by copying it from the template
                if (!aavmfVarsFd.exists()) {
                    try (InputStream in = new FileInputStream(aavmfVarsTemplate);
                         OutputStream out = new FileOutputStream(aavmfVarsFd)) {
                        in.transferTo(out);
                    }
                    aavmfVarsFd.setWritable(true);
                }

                File pidFile = new File(fileUtils.filesDir(), "qemu.pid");
                if(pidFile.exists()) pidFile.delete();
                osImage.setWritable(true);

                        // "chmod -R 755 " + fileUtils.binDir().getAbsolutePath() + " && " +
                String command = "chmod -R a+rx " + fileUtils.filesDir().getAbsolutePath() + " && " +
                        "export PATH=" + fileUtils.binDir().getAbsolutePath() + ":$PATH && " +
                        fileUtils.libDir().getAbsolutePath() + "/aarch64-linux-gnu/ld-linux-aarch64.so.1 --library-path " + fileUtils.libDir().getAbsolutePath() + "/aarch64-linux-gnu " +
                        // "export LD_LIBRARY_PATH=" + fileUtils.libDir().getAbsolutePath() + ":" + fileUtils.libDir().getAbsolutePath() + "/aarch64-linux-gnu && " +
                        qemuBinary.getAbsolutePath() +
                        " -m 8192 -M virt,highmem=on -cpu cortex-a72 -smp 8" +
                        " -drive file=" + osImage.getAbsolutePath() + ",format=qcow2,if=none,id=hd0" +
                        " -device virtio-blk-device,drive=hd0" +
                        " -netdev user,id=net0,hostfwd=tcp::8123-:8123,dns=1.1.1.1,ipv6=off" +
                        " -device virtio-net-pci,netdev=net0,romfile=\"\"" +
                        " -drive if=pflash,format=raw,readonly=on,file=" + aavmfCodeFd.getAbsolutePath() +
                        " -drive if=pflash,format=raw,file=" + aavmfVarsFd.getAbsolutePath() +
                        " -vnc 0.0.0.0:0 -display none -serial vc" +
                        " -pidfile " + pidFile.getAbsolutePath() +
                        " -L " + fileUtils.filesDir() + "/usr/share/qemu";

                // command += " -accel kvm";
                qemuProcess = run(command);

                runOnUiThread(this::checkFilesExistAndUpdateUi);

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
                runOnUiThread(this::checkFilesExistAndUpdateUi);
            }
        }).start();
    }


    private void terminateVm() {
        new Thread(() -> {
            File pidFile = new File(fileUtils.filesDir(), "qemu.pid");
            if (pidFile.exists()) {
                try {
                    String pid;
                    Process p = run("cat " + pidFile.getAbsolutePath());
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                        pid = reader.readLine();
                    }
                    p.waitFor();

                    if (pid != null && !pid.isEmpty()) {
                        String command = "kill -9 " + pid;
                        run(command).waitFor();
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
            runOnUiThread(this::checkFilesExistAndUpdateUi);
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


    private void updateStatus(final String message) {
        Log.i(TAG, message);
        runOnUiThread(() -> statusTextView.setText(message));
    }


    private void clearCache() {
        FileUtils.deleteRecursive(fileUtils.cacheDir());
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
        if (isVMRunning()) {
            terminateVm();
        }

        FileUtils.deleteRecursive(fileUtils.filesDir());

        Toast.makeText(this, "All data except cache has been deleted.", Toast.LENGTH_SHORT).show();
        checkFilesExistAndUpdateUi();
    }


    private boolean isVMRunning() {
        File pidFile = new File(fileUtils.filesDir(), "qemu.pid");
        try {
            Process p = run("test -f " + pidFile.getAbsolutePath());
            return p.waitFor() == 0;
        } catch (Exception e) {
            Log.e(TAG, "Error checking PID file", e);
            return false;
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
        File qemuBinary = new File(fileUtils.filesDir(), QEMU_BINARY_PATH);
        File osImage = new File(fileUtils.filesDir(), OS_IMAGE_PATH);
        File aavmfCodeFd = new File(fileUtils.filesDir(), AAVMF_CODE_PATH);
        File aavmfVarsTemplate = new File(fileUtils.filesDir(), AAVMF_VARS_TEMPLATE_PATH);

        boolean cacheExists = isDirectoryNotEmpty(fileUtils.cacheDir());
        boolean dataExists = isDirectoryNotEmpty(fileUtils.filesDir());

        boolean startable = qemuBinary.exists() && osImage.exists() && aavmfCodeFd.exists() && aavmfVarsTemplate.exists();
        boolean isRunning = isVMRunning();

        runAsRootCheckBox.setEnabled(!isRunning);
        startButton.setEnabled(startable && !isRunning);
        terminateButton.setEnabled(isRunning);
        downloadButton.setEnabled(!isRunning);
        clearCacheButton.setEnabled(cacheExists);
        deleteAllButton.setEnabled(dataExists);

        if (isRunning) {
            updateStatus("VM is running.");
        } else if (startable) {
            updateStatus("Ready. You can check for updates or start VM.");
        } else {
            updateStatus("Please download required files.");
        }
    }


    private void setAllButtonsEnabled(boolean enabled) {
        runAsRootCheckBox.setEnabled(enabled);
        startButton.setEnabled(enabled);
        terminateButton.setEnabled(enabled);
        downloadButton.setEnabled(enabled);
        clearCacheButton.setEnabled(enabled);
        deleteAllButton.setEnabled(enabled);
    }
}
