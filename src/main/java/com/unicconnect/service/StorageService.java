package com.unicconnect.service;

import com.unicconnect.exception.BusinessRuleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Stores exam result PDFs. Defaults to local disk under {@code storage.base-dir}.
 * When SUPABASE_URL / SUPABASE_SERVICE_ROLE_KEY / SUPABASE_BUCKET are configured the
 * same object path is pushed to Supabase Storage via its REST API instead.
 * The path stored in the database is always the logical path
 * {academic_year}/{exam_type_name}/{semester_name}/{pdf_file_name}.
 */
@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    private final String mode;
    private final Path baseDir;
    private final String supabaseUrl;
    private final String supabaseServiceRoleKey;
    private final String supabaseBucket;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public StorageService(@Value("${storage.mode:local}") String mode,
                          @Value("${storage.base-dir:./uploads}") String baseDir,
                          @Value("${storage.supabase-url:}") String supabaseUrl,
                          @Value("${storage.supabase-service-role-key:}") String supabaseServiceRoleKey,
                          @Value("${storage.supabase-bucket:exam-results}") String supabaseBucket) {
        this.mode = "supabase".equalsIgnoreCase(mode) && !supabaseUrl.isBlank() && !supabaseServiceRoleKey.isBlank()
                ? "supabase"
                : "local";
        this.baseDir = Path.of(baseDir).toAbsolutePath().normalize();
        this.supabaseUrl = supabaseUrl.endsWith("/") ? supabaseUrl.substring(0, supabaseUrl.length() - 1) : supabaseUrl;
        this.supabaseServiceRoleKey = supabaseServiceRoleKey;
        this.supabaseBucket = supabaseBucket;
        log.info("StorageService active mode: {}", this.mode);
    }

    public void store(String objectPath, byte[] content) {
        if (objectPath == null || objectPath.isBlank()) {
            throw new BusinessRuleException("Storage object path must not be empty");
        }
        if ("supabase".equals(mode)) {
            storeToSupabase(objectPath, content);
        } else {
            storeToLocal(objectPath, content);
        }
    }

    private void storeToLocal(String objectPath, byte[] content) {
        Path target = baseDir.resolve(sanitize(objectPath)).normalize();
        if (!target.startsWith(baseDir)) {
            throw new BusinessRuleException("Invalid storage path: " + objectPath);
        }
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new BusinessRuleException("Failed to store file locally: " + e.getMessage());
        }
    }

    private void storeToSupabase(String objectPath, byte[] content) {
        try {
            String url = supabaseUrl + "/storage/v1/object/" + encodeSegment(supabaseBucket)
                    + "/" + encodePath(objectPath) + "?upsert=true";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + supabaseServiceRoleKey)
                    .header("apikey", supabaseServiceRoleKey)
                    .header("Content-Type", "application/pdf")
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                    .timeout(Duration.ofSeconds(60))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessRuleException("Supabase upload failed (" + response.statusCode() + "): " + response.body());
            }
        } catch (BusinessRuleException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessRuleException("Supabase upload failed: " + e.getMessage());
        }
    }

    private static String sanitize(String objectPath) {
        return objectPath.replace("\\", "/").replace("..", "_");
    }

    private static String encodePath(String objectPath) {
        String[] segments = objectPath.replace("\\", "/").split("/");
        java.util.ArrayList<String> encoded = new java.util.ArrayList<>();
        for (String s : segments) {
            encoded.add(encodeSegment(s));
        }
        return String.join("/", encoded);
    }

    private static String encodeSegment(String segment) {
        return java.net.URLEncoder.encode(segment, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
