package com.example.hassosonandroid;

import android.os.Build;
import android.util.Log;
import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

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
}
