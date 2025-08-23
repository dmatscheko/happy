package com.example.hassosonandroid;

import android.content.Context;
import android.os.Build;
import android.system.Os;
import android.util.Log;

import androidx.annotation.RequiresApi;

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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class PackageManager {
    private static final String TAG = "HassOSPackageManager";
    private static final String TERMUX_REPO_URL = "https://packages.termux.dev/apt/termux-main/";
    private static final String TERMUX_PACKAGES_FILE_URL = TERMUX_REPO_URL + "dists/stable/main/binary-aarch64/Packages";
    private static final String LIB_DIR_NAME = "lib";
    private static final String BIN_DIR_NAME = "bin";

    private final Context context;
    private final StatusListener statusListener;

    public interface StatusListener {
        void onStatusUpdate(String message);
        void onFinalMessage(String message);
        void onError(String message, Throwable e);
    }

    public PackageManager(Context context, StatusListener listener) {
        this.context = context;
        this.statusListener = listener;
    }

    /**
     * The main method to resolve and install a list of packages.
     * It uses an iterative approach to resolve all direct and indirect dependencies.
     * @param initialPackages The list of packages to install.
     */
    public void installDebFromUrl(String url, Set<String> filesToExtract) {
        try {
            statusListener.onStatusUpdate("Downloading firmware from " + url);
            String fileName = url.substring(url.lastIndexOf('/') + 1);
            File debFile = new File(context.getCacheDir(), fileName);
            downloadUrlToFile(url, debFile, "Firmware");

            statusListener.onStatusUpdate("Unpacking firmware...");
            unpackDeb(debFile, filesToExtract);

            debFile.delete();
            statusListener.onStatusUpdate("Firmware setup complete.");

        } catch (Exception e) {
            statusListener.onError("Error during firmware installation", e);
        }
    }

    public void installPackages(List<String> initialPackages) {
        final StringBuilder warnings = new StringBuilder();
        try {
            // 1. Download the package index
            statusListener.onStatusUpdate("Downloading package index...");
            Map<String, PackageInfo> packageDb = parsePackagesFile();

            // 2. Initialize the set of selected packages with the initial list
            Map<String, PackageInfo> selectedPackages = new HashMap<>();
            for (String pkgName : initialPackages) {
                PackageInfo info = findBestPackage(packageDb, pkgName, "");
                if (info != null) {
                    selectedPackages.put(pkgName, info);
                } else {
                    throw new IOException("Initial package not found: " + pkgName);
                }
            }

            // 3. Iteratively resolve dependencies
            // The loop continues as long as we are adding new packages to the list.
            // This ensures that we also resolve the dependencies of the dependencies.
            statusListener.onStatusUpdate("Resolving dependencies...");
            boolean changedInIteration;
            do {
                changedInIteration = false;
                // Collect all dependencies from the currently selected packages
                Set<Dependency> allDependencies = new HashSet<>();
                for (PackageInfo pkg : selectedPackages.values()) {
                    allDependencies.addAll(parseDepends(pkg.depends));
                }

                for (Dependency dep : allDependencies) {
                    if (selectedPackages.containsKey(dep.packageName)) {
                        // Package is already selected. We will check for version conflicts later.
                        continue;
                    }

                    // Try to find a suitable package for this new dependency
                    PackageInfo candidate = findBestPackage(packageDb, dep.packageName, dep.versionConstraint);
                    if (candidate != null) {
                        selectedPackages.put(candidate.packageName, candidate);
                        changedInIteration = true;
                    } else {
                        // If the primary dependency is not found, check alternatives.
                        boolean foundAlternative = false;
                        for (Dependency alternative : dep.alternatives) {
                             if (selectedPackages.containsKey(alternative.packageName)) {
                                if (alternative.isVersionSatisfied(selectedPackages.get(alternative.packageName).version)) {
                                    foundAlternative = true;
                                    break;
                                }
                             }
                             PackageInfo altCandidate = findBestPackage(packageDb, alternative.packageName, alternative.versionConstraint);
                             if (altCandidate != null) {
                                 selectedPackages.put(altCandidate.packageName, altCandidate);
                                 changedInIteration = true;
                                 foundAlternative = true;
                                 break;
                             }
                        }
                        if (!foundAlternative) {
                             warnings.append("Warning: Could not resolve dependency: ").append(dep.packageName).append("\n");
                        }
                    }
                }
            } while (changedInIteration);

            // 4. Conflict detection
            // After the dependency set has stabilized, verify that there are no conflicts.
            statusListener.onStatusUpdate("Verifying dependencies and checking for conflicts...");
            verifyDependencies(selectedPackages, warnings);

            // 5. Download and unpack all selected packages
            List<File> downloadedDebs = new ArrayList<>();
            for (PackageInfo info : selectedPackages.values()) {
                File debFile = new File(context.getCacheDir(), info.filename.replace('/', '_'));
                downloadUrlToFile(TERMUX_REPO_URL + info.filename, debFile, info.packageName);
                downloadedDebs.add(debFile);
            }

            statusListener.onStatusUpdate("Unpacking files...");
            for (File deb : downloadedDebs) unpackDeb(deb, UnpackMode.FILES_ONLY);

            statusListener.onStatusUpdate("Creating symbolic links...");
            for (File deb : downloadedDebs) unpackDeb(deb, UnpackMode.SYMLINKS_ONLY);

            // 6. Clean up downloaded .deb files
            for (File deb : downloadedDebs) deb.delete();

            String finalMessage = "Package setup complete!";
            if (warnings.length() > 0) {
                finalMessage += "\n\nWarnings:\n" + warnings.toString();
            }
            statusListener.onFinalMessage(finalMessage);

        } catch (Exception e) {
            statusListener.onError("Error during package setup", e);
        }
    }

    private void verifyDependencies(Map<String, PackageInfo> selectedPackages, StringBuilder warnings) {
        for (PackageInfo pkg : selectedPackages.values()) {
            Collection<Dependency> dependencies = parseDepends(pkg.depends);
            for (Dependency dep : dependencies) {
                boolean satisfied = false;
                if (selectedPackages.containsKey(dep.packageName)) {
                    if (dep.isVersionSatisfied(selectedPackages.get(dep.packageName).version)) {
                        satisfied = true;
                    }
                }
                for (Dependency alternative : dep.alternatives) {
                    if (selectedPackages.containsKey(alternative.packageName)) {
                        if (alternative.isVersionSatisfied(selectedPackages.get(alternative.packageName).version)) {
                            satisfied = true;
                            break;
                        }
                    }
                }
                if (!satisfied) {
                    warnings.append("Conflict detected: Package '")
                           .append(pkg.packageName)
                           .append("' depends on '")
                           .append(dep.packageName)
                           .append(dep.versionConstraint)
                           .append("', which could not be satisfied by the selected package set.\n");
                }
            }
        }
    }

    private PackageInfo findBestPackage(Map<String, PackageInfo> packageDb, String packageName, String versionConstraint) {
        // This is a simplification. A real resolver would consider more factors like choosing the newest version.
        // For this repository, there's only one version per package, so we find the first one
        // that matches the name and satisfies the version constraint.
        Dependency tempDep = new Dependency(packageName, versionConstraint);
        return packageDb.values().stream()
                .filter(p -> p.packageName.equals(packageName))
                .filter(p -> tempDep.isVersionSatisfied(p.version))
                .findFirst()
                .orElse(null);
    }


    private enum UnpackMode { FILES_ONLY, SYMLINKS_ONLY }

    public static class PackageInfo {
        String packageName;
        String version;
        String filename;
        String depends;
    }

    public static class Dependency {
        String packageName;
        String versionConstraint;
        List<Dependency> alternatives = new ArrayList<>();

        public Dependency(String packageName, String versionConstraint) {
            this.packageName = packageName;
            this.versionConstraint = versionConstraint;
        }

        public boolean isVersionSatisfied(String availableVersion) {
            if (versionConstraint == null || versionConstraint.isEmpty()) return true;

            Pattern pattern = Pattern.compile("\\(([^\\s]+)\\s+([^\\)]+)\\)");
            Matcher matcher = pattern.matcher(versionConstraint);

            if (!matcher.find()) return true; // No parsable constraint

            String operator = matcher.group(1);
            String requiredVersion = matcher.group(2);

            int comparison = compareVersions(availableVersion, requiredVersion);

            switch (operator) {
                case "=": return comparison == 0;
                case ">=": return comparison >= 0;
                case "<=": return comparison <= 0;
                case ">>": return comparison > 0;
                case "<<": return comparison < 0;
                default: return true; // Unsupported operator
            }
        }
    }

    public static Collection<Dependency> parseDepends(String dependsString) {
        Map<String, Dependency> dependencies = new HashMap<>();
        if (dependsString == null || dependsString.isEmpty()) return dependencies.values();

        for (String group : dependsString.split(",\\s*")) {
            List<Dependency> alternatives = new ArrayList<>();
            for (String alternative : group.split("\\s*\\|\\s*")) {
                alternative = alternative.trim();
                Pattern pattern = Pattern.compile("([^\\s]+)\\s*(\\([^\\)]+\\))?");
                Matcher matcher = pattern.matcher(alternative);
                if (matcher.find()) {
                    String name = matcher.group(1);
                    String version = matcher.group(2) != null ? matcher.group(2) : "";
                    alternatives.add(new Dependency(name, version));
                }
            }
            if (!alternatives.isEmpty()) {
                Dependency primary = alternatives.get(0);
                primary.alternatives = alternatives.subList(1, alternatives.size());
                dependencies.put(primary.packageName, primary);
            }
        }
        return dependencies.values();
    }

    public static int compareVersions(String v1, String v2) {
        // Simplified version comparison. Does not handle epochs or tildes.
        String[] parts1 = v1.split("[.\\-:]");
        String[] parts2 = v2.split("[.\\-:]");

        int length = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < length; i++) {
            String part1 = i < parts1.length ? parts1[i] : "0";
            String part2 = i < parts2.length ? parts2[i] : "0";

            try {
                // Try numeric comparison first
                int num1 = Integer.parseInt(part1);
                int num2 = Integer.parseInt(part2);
                if (num1 < num2) return -1;
                if (num1 > num2) return 1;
            } catch (NumberFormatException e) {
                // Fallback to lexical comparison
                int cmp = part1.compareTo(part2);
                if (cmp != 0) return cmp;
            }
        }
        return 0;
    }


    private void unpackDeb(File debFile, Set<String> filesToExtract) throws Exception {
        Set<String> extractedFiles = new HashSet<>();
        try (ArArchiveInputStream arInput = new ArArchiveInputStream(new BufferedInputStream(new FileInputStream(debFile)))) {
            org.apache.commons.compress.archivers.ArchiveEntry entry;
            while ((entry = arInput.getNextEntry()) != null) {
                if (entry.getName().equals("data.tar.xz")) {
                    XZInputStream xzInput = new XZInputStream(arInput);
                    try (TarArchiveInputStream tarInput = new TarArchiveInputStream(xzInput)) {
                        TarArchiveEntry tarEntry;
                        while ((tarEntry = tarInput.getNextTarEntry()) != null) {
                            String entryPath = tarEntry.getName();
                            // Paths in tar are like ./usr/share/AAVMF/AAVMF_CODE.no-secboot.fd
                            String cleanedPath = entryPath.startsWith("./") ? entryPath.substring(2) : entryPath;

                            if (filesToExtract.contains(cleanedPath)) {
                                File outputFile = new File(context.getFilesDir(), new File(cleanedPath).getName());
                                if (outputFile.exists()) outputFile.delete();
                                outputFile.getParentFile().mkdirs();

                                try (OutputStream out = new FileOutputStream(outputFile)) {
                                    tarInput.transferTo(out);
                                }
                                extractedFiles.add(cleanedPath);
                                // If all files are extracted, we can stop.
                                if (extractedFiles.size() == filesToExtract.size()) {
                                    return;
                                }
                            }
                        }
                    }
                    return;
                }
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void unpackDeb(File debFile, UnpackMode mode) throws Exception {
        final String termuxPrefix = "./data/data/com.termux/files/usr/";

        try (ArArchiveInputStream arInput = new ArArchiveInputStream(new BufferedInputStream(new FileInputStream(debFile)))) {
            org.apache.commons.compress.archivers.ArchiveEntry entry;
            while ((entry = arInput.getNextEntry()) != null) {
                if (entry.getName().equals("data.tar.xz")) {
                    XZInputStream xzInput = new XZInputStream(arInput);
                    try (TarArchiveInputStream tarInput = new TarArchiveInputStream(xzInput)) {
                        TarArchiveEntry tarEntry;
                        while ((tarEntry = tarInput.getNextTarEntry()) != null) {
                            String entryPath = tarEntry.getName();
                            if (!entryPath.startsWith(termuxPrefix)) continue;

                            String relativePath = entryPath.substring(termuxPrefix.length());
                            if (relativePath.isEmpty()) continue;

                            File outputFile = new File(context.getFilesDir(), relativePath);

                            if (tarEntry.isDirectory()) {
                                if (mode == UnpackMode.FILES_ONLY) {
                                    outputFile.mkdirs();
                                }
                                continue;
                            }

                            boolean isSymlink = tarEntry.isSymbolicLink();

                            if (mode == UnpackMode.FILES_ONLY && !isSymlink) {
                                outputFile.getParentFile().mkdirs();
                                if (outputFile.exists()) outputFile.delete();
                                try (OutputStream out = new FileOutputStream(outputFile)) {
                                    tarInput.transferTo(out);
                                }
                            } else if (mode == UnpackMode.SYMLINKS_ONLY && isSymlink) {
                                if (outputFile.exists()) {
                                    deleteRecursive(outputFile);
                                }
                                outputFile.getParentFile().mkdirs();
                                Os.symlink(tarEntry.getLinkName(), outputFile.getAbsolutePath());
                            }
                        }
                    }
                    return; // We've processed the data.tar.xz, no need to check other entries.
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

    private void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        if (!fileOrDirectory.delete()) {
            Log.w(TAG, "Failed to delete " + fileOrDirectory);
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
                        statusListener.onStatusUpdate("Downloading " + fileDescription + ": " + (int) (total * 100 / fileLength) + "%");
                    }
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private File binDir() { return new File(context.getFilesDir(), BIN_DIR_NAME); }
    private File libDir() { return new File(context.getFilesDir(), LIB_DIR_NAME); }
}
