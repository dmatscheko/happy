package com.example.hassosonandroid;

import android.os.Build;
import android.util.Log;
import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.IOException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.GeneralSecurityException;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class FileUtils {
    private static final String TAG = "FileUtils";

    @RequiresApi(api = Build.VERSION_CODES.O)
    public static void deleteRecursive(File fileOrDirectory) {
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

    public interface DownloadProgressListener {
        void onProgressUpdate(String message);
    }

    public static void downloadUrlToFile(String urlString, File file, boolean ignoreTls, DownloadProgressListener listener) throws IOException, GeneralSecurityException {
        if (file.exists()) {
            if (listener != null) listener.onProgressUpdate("Using cached file: " + file.getName());
            return;
        }

        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();

        if (ignoreTls && (connection instanceof HttpsURLConnection)) {
            HttpsURLConnection httpsConnection = (HttpsURLConnection) connection;

            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) { }
                    public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) { }
                }
            };

            SSLContext sc = SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            httpsConnection.setSSLSocketFactory(sc.getSocketFactory());
            httpsConnection.setHostnameVerifier((hostname, session) -> true);
        }

        try {
            connection.connect();
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("Server returned HTTP " + connection.getResponseCode() + " " + connection.getResponseMessage());
            }
            int fileLength = connection.getContentLength();
            if (listener != null) listener.onProgressUpdate("Downloading " + file.getName() + "...");
            try (InputStream input = connection.getInputStream(); OutputStream output = new FileOutputStream(file)) {
                byte[] data = new byte[8192];
                long total = 0;
                int count;
                while ((count = input.read(data)) != -1) {
                    total += count;
                    output.write(data, 0, count);
                    if (fileLength > 0 && listener != null) {
                        listener.onProgressUpdate("Downloading " + file.getName() + ": " + (int) (total * 100 / fileLength) + "%");
                    }
                }
            }
        } finally {
            connection.disconnect();
        }
    }
}
