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
                    }
                    return;
                }
            }
        }
    }

    // ... (All other methods are the same as the previous final version)
}
