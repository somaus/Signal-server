package com.signal.global.file;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.web.multipart.MultipartFile;

/** 파일 앞부분(매직바이트)으로 확인한 실제 형식. 저장 확장자도 여기서만 정한다. */
public enum UploadFileType {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    PDF("application/pdf", "pdf"),
    MP4("video/mp4", "mp4"),
    MOV("video/quicktime", "mov");

    private static final int HEADER_SIZE = 12;

    private final String contentType;
    private final String extension;

    UploadFileType(String contentType, String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    public String contentType() {
        return contentType;
    }

    public String extension() {
        return extension;
    }

    public static Optional<UploadFileType> detect(byte[] head) {
        if (startsWith(head, 0xFF, 0xD8, 0xFF)) {
            return Optional.of(JPEG);
        }
        if (startsWith(head, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Optional.of(PNG);
        }
        if (startsWith(head, '%', 'P', 'D', 'F', '-')) {
            return Optional.of(PDF);
        }
        if (head.length >= HEADER_SIZE && new String(head, 4, 4, StandardCharsets.ISO_8859_1).equals("ftyp")) {
            // shortcut: ftyp 브랜드가 qt 인지로만 mov/mp4를 나눈다. 다른 컨테이너가 필요해지면 브랜드 표를 늘린다.
            return Optional.of(new String(head, 8, 2, StandardCharsets.ISO_8859_1).equals("qt") ? MOV : MP4);
        }
        return Optional.empty();
    }

    public static Optional<UploadFileType> detect(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return detect(in.readNBytes(HEADER_SIZE));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static boolean startsWith(byte[] head, int... signature) {
        if (head.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((head[i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }
}
