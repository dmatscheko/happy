package com.example.hassosonandroid;

import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.system.Os;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.apache.commons.compress.archivers.ar.ArArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "HassOS";
    private static final String QEMU_BINARY_NAME = "qemu-system-aarch64";
    private static final String OS_IMAGE_NAME = "haos.qcow2";
    private static final String LIB_DIR_NAME = "lib";
    private static final String BIN_DIR_NAME = "bin";

    private static final String HAOS_URL = "https://github.com/home-assistant/operating-system/releases/download/12.3/haos_generic-aarch64-12.3.qcow2.xz";
    private static final String TERMUX_REPO_URL = "https://packages.termux.dev/apt/termux-main/";
    private static final String TERMUX_PACKAGES_FILE_URL = TERMUX_REPO_URL + "dists/stable/main/binary-aarch64/Packages";

    private enum UnpackMode { FILES_ONLY, SYMLINKS_ONLY }

    private static class PackageInfo {
        String packageName;
        String version;
        String filename;
        String depends;
    }

    private TextView statusTextView;
    private Button downloadButton, startButton, clearCacheButton, deleteAllButton;
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

        checkFilesExistAndUpdateUi();
    }

    private void downloadFiles() {
        setAllButtonsEnabled(false);
        new Thread(() -> {
            final StringBuilder warnings = new StringBuilder();
            try {
                updateStatus("Downloading package index...");
                Map<String, PackageInfo> packageDb = parsePackagesFile();

                Set<String> packagesToProcess = new HashSet<>();
                Set<String> processedPackages = new HashSet<>();
                List<File> downloadedDebs = new ArrayList<>();

                packagesToProcess.add("qemu-system-aarch64-headless");

                updateStatus("Resolving dependencies...");
                while (!packagesToProcess.isEmpty()) {
                    String pkgName = packagesToProcess.iterator().next();
                    packagesToProcess.remove(pkgName);
                    if (processedPackages.contains(pkgName)) continue;

                    try {
                        PackageInfo info = packageDb.get(pkgName);
                        if (info == null) throw new IOException("Package not found in index.");

                        File debFile = new File(getCacheDir(), info.filename.replace('/', '_'));
                        downloadUrlToFile(TERMUX_REPO_URL + info.filename, debFile, info.packageName);
                        downloadedDebs.add(debFile);

                        if (info.depends != null && !info.depends.isEmpty()) {
                            for (String dep : info.depends.split(",\\s*")) {
                                packagesToProcess.add(dep.split("\\s+")[0]);
                            }
                        }
                    } catch (Exception e) {
                        String warningMsg = "Warning: Failed to process package " + pkgName + ": " + e.getMessage();
                        Log.w(TAG, warningMsg);
                        warnings.append(warningMsg).append("\n");
                    } finally {
                        processedPackages.add(pkgName);
                    }
                }

                updateStatus("Unpacking files...");
                for (File deb : downloadedDebs) unpackDeb(deb, UnpackMode.FILES_ONLY);

                updateStatus("Creating symbolic links...");
                for (File deb : downloadedDebs) unpackDeb(deb, UnpackMode.SYMLINKS_ONLY);

                for (File deb : downloadedDebs) deb.delete();

                File osImage = new File(getFilesDir(), OS_IMAGE_NAME);
                if (!osImage.exists()) {
                    File osImageXz = new File(getFilesDir(), OS_IMAGE_NAME + ".xz");
                    downloadUrlToFile(HAOS_URL, osImageXz, "Home Assistant OS");
                    decompressXz(osImageXz, osImage);
                    osImageXz.delete();
                }

                String finalMessage = "Setup complete! Ready to start VM.";
                if (warnings.length() > 0) {
                    finalMessage += "\n\nWarnings:\n" + warnings.toString();
                }
                updateStatus(finalMessage);

            } catch (Exception e) {
                updateStatus("Error during setup: " + e.getMessage());
                Log.e(TAG, "Error in download/setup thread", e);
            } finally {
                runOnUiThread(this::checkFilesExistAndUpdateUi);
            }
        }).start();
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void unpackDeb(File debFile, UnpackMode mode) throws Exception {
        File libDir = libDir();
        File binDir = binDir();
        if (libDir.exists() && !libDir.isDirectory()) libDir.delete();
        if (!libDir.exists()) libDir.mkdirs();
        if (binDir.exists() && !binDir.isDirectory()) binDir.delete();
        if (!binDir.exists()) binDir.mkdirs();

        try (ArArchiveInputStream arInput = new ArArchiveInputStream(new BufferedInputStream(new FileInputStream(debFile)))) {
            org.apache.commons.compress.archivers.ArchiveEntry entry;
            while ((entry = arInput.getNextEntry()) != null) {
                if (entry.getName().equals("data.tar.xz")) {
                    XZInputStream xzInput = new XZInputStream(arInput);
                    try (TarArchiveInputStream tarInput = new TarArchiveInputStream(xzInput)) {
                        TarArchiveEntry tarEntry;
                        while ((tarEntry = tarInput.getNextTarEntry()) != null) {
                            String entryPath = tarEntry.getName();
                            File outputFile;
                            if (entryPath.contains("/lib/")) outputFile = new File(libDir, new File(entryPath).getName());
                            else if (entryPath.contains("/bin/")) outputFile = new File(binDir, new File(entryPath).getName());
                            else continue;

                            boolean isSymlink = tarEntry.isSymbolicLink();

                            if(mode == UnpackMode.FILES_ONLY && !isSymlink) {
                                if (outputFile.exists()) outputFile.delete();
                                try (OutputStream out = new FileOutputStream(outputFile)) {
                                    tarInput.transferTo(out);
                                }
                            } else if (mode == UnpackMode.SYMLINKS_ONLY && isSymlink) {
                                if (outputFile.exists()) outputFile.delete();
                                Os.symlink(tarEntry.getLinkName(), outputFile.getAbsolutePath());
                            }
                        }
                    } catch (Exception e) {
                        updateStatus("Error during unpacking: " + e.getMessage());
                        Log.e(TAG, "Error in unpack/setup thread", e);
                    }
                    return;
                }
            }
        }
    }


    private Map<String, PackageInfo> parsePackagesFile() throws IOException {
        Map<String, PackageInfo> db = new HashMap<>();
        URL url = new URL(TERMUX_PACKAGES_FILE_URL);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.connect();
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("Failed to get Termux Packages file");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                String line;
                PackageInfo currentInfo = null;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("Package: ")) {
                        if (currentInfo != null) db.put(currentInfo.packageName, currentInfo);
                        currentInfo = new PackageInfo();
                        currentInfo.packageName = line.substring(9);
                    } else if (currentInfo != null) {
                        if (line.startsWith("Filename: ")) currentInfo.filename = line.substring(10);
                        else if (line.startsWith("Version: ")) currentInfo.version = line.substring(9);
                        else if (line.startsWith("Depends: ")) currentInfo.depends = line.substring(9);
                    }
                }
                if (currentInfo != null) db.put(currentInfo.packageName, currentInfo);
            }
        } finally {
            connection.disconnect();
        }
        return db;
    }

    private void startVm() {
        setAllButtonsEnabled(false);
        updateStatus("Starting VM...");
        new Thread(() -> {
            try {
                File qemuBinary = new File(binDir(), QEMU_BINARY_NAME);
                File osImage = new File(getFilesDir(), OS_IMAGE_NAME);
                if (!qemuBinary.exists() || !osImage.exists()) throw new IOException("Required files not found.");

                String command = "chmod -R 755 " + binDir().getAbsolutePath() + " && " +
                                 "export PATH=" + binDir().getAbsolutePath() + ":$PATH && " +
                                 "export LD_LIBRARY_PATH=" + libDir().getAbsolutePath() + " && " +
                                 qemuBinary.getAbsolutePath() +
                                 " -m 2048 -M virt -cpu cortex-a57 -smp 2" +
                                 " -hda " + osImage.getAbsolutePath() +
                                 " -netdev user,id=net0,hostfwd=tcp::8123-:8123" +
                                 " -device virtio-net-pci,netdev=net0 -vnc 0.0.0.0:0";

                Process suProcess = Runtime.getRuntime().exec(new String[]{"su", "-c", command});

                new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(suProcess.getInputStream()))) {
                        String line; while ((line = reader.readLine()) != null) Log.d(TAG, "QEMU stdout: " + line);
                    } catch (IOException e) { Log.e(TAG, "Error reading QEMU stdout", e); }
                }).start();

                final StringBuilder errorOutput = new StringBuilder();
                new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(suProcess.getErrorStream()))) {
                        String line; while ((line = reader.readLine()) != null) {
                            Log.e(TAG, "QEMU stderr: " + line);
                            errorOutput.append(line).append("\n");
                        }
                    } catch (IOException e) { Log.e(TAG, "Error reading QEMU stderr", e); }
                }).start();

                int exitValue = suProcess.waitFor();
                if (exitValue != 0) {
                    updateStatus("VM process exited with error code " + exitValue + ".\n" + "Error: " + errorOutput.toString());
                } else {
                    updateStatus("VM process started successfully (but has exited).");
                }
            } catch (Exception e) {
                updateStatus("Error starting VM: " + e.getMessage());
                Log.e(TAG, "Error in startVm thread", e);
            } finally {
                runOnUiThread(this::checkFilesExistAndUpdateUi);
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
        deleteRecursive(binDir());
        deleteRecursive(libDir());
        File osImage = new File(getFilesDir(), OS_IMAGE_NAME);
        if (osImage.exists()) osImage.delete();

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith("version_")) {
                editor.remove(key);
            }
        }
        editor.apply();

        Toast.makeText(this, "All data deleted.", Toast.LENGTH_SHORT).show();
        checkFilesExistAndUpdateUi();
    }

    void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.exists()) {
            if (fileOrDirectory.isDirectory()) {
                for (File child : fileOrDirectory.listFiles()) {
                    deleteRecursive(child);
                }
            }
            fileOrDirectory.delete();
        }
    }

    private void checkFilesExistAndUpdateUi() {
        File qemuBinary = new File(binDir(), QEMU_BINARY_NAME);
        File osImage = new File(getFilesDir(), OS_IMAGE_NAME);
        boolean allExist = qemuBinary.exists() && osImage.exists();

        startButton.setEnabled(allExist);
        deleteAllButton.setEnabled(allExist);
        downloadButton.setEnabled(true);
        clearCacheButton.setEnabled(true);

        if (allExist) {
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
    }
}
