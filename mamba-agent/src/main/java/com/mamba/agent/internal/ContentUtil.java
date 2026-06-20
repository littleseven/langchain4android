package com.mamba.agent.internal;

import android.util.Base64;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ContentUtil {

    private ContentUtil() { }

    public static String extractBase64Content(Path filePath) {
        try {
            byte[] fileBytes = Files.readAllBytes(filePath);
            return Base64.encodeToString(fileBytes, Base64.DEFAULT);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
