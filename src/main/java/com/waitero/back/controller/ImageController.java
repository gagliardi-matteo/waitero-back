package com.waitero.back.controller;

import org.springframework.core.io.Resource;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.UrlResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.util.Locale;

@RestController
@RequestMapping("/api/image")
@RequiredArgsConstructor
public class ImageController {

    @GetMapping("/images/{filename}")
    public ResponseEntity<Resource> getImage(@PathVariable String filename) throws IOException {
        if (filename == null || filename.isBlank() || filename.contains("/") || filename.contains("\\")
                || filename.contains(":") || filename.equals(".") || filename.equals("..")) {
            return ResponseEntity.badRequest().build();
        }
        Path file;
        try {
            Path root = Paths.get("/app/uploads").toAbsolutePath().normalize();
            file = root.resolve(filename).normalize();
            if (!file.startsWith(root)) return ResponseEntity.badRequest().build();
            if (!Files.isRegularFile(file) || !Files.isReadable(file)) return ResponseEntity.notFound().build();
            if (!file.toRealPath().startsWith(root.toRealPath())) return ResponseEntity.notFound().build();
        } catch (InvalidPathException invalid) {
            return ResponseEntity.badRequest().build();
        } catch (IOException unavailable) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new UrlResource(file.toUri());

        String contentType = filename.toLowerCase(Locale.ROOT).endsWith(".png") ?
                MediaType.IMAGE_PNG_VALUE : MediaType.IMAGE_JPEG_VALUE;

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .body(resource);
    }

}
