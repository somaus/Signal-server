package com.signal.global.file;

import com.signal.global.exception.ErrorCode;
import com.signal.global.exception.SignalException;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Component
public class LocalFileStorage implements FileStorage {

    private final Path rootDir;
    private final String baseUrl;

    public LocalFileStorage(@Value("${file.upload-dir}") String uploadDir,
                             @Value("${file.base-url}") String baseUrl) {
        this.rootDir = Path.of(uploadDir).toAbsolutePath().normalize();
        this.baseUrl = baseUrl;
    }

    @PostConstruct
    void init() {
        try {
            Files.createDirectories(rootDir);
        } catch (IOException e) {
            log.error("업로드 디렉토리 생성 실패: {}", rootDir, e);
            throw new IllegalStateException("업로드 디렉토리를 생성할 수 없습니다.", e);
        }
    }

    @Override
    public String store(MultipartFile file, String directory) {
        String filename = UUID.randomUUID() + extension(UploadFileType.detect(file));
        Path target = resolveTarget(directory, filename);

        try {
            Files.createDirectories(target.getParent());
            file.transferTo(target);
        } catch (IOException e) {
            log.error("파일 저장 실패: {}", filename, e);
            throw new SignalException(ErrorCode.INTERNAL_SERVER_ERROR);
        }

        return toUrl(directory, filename);
    }

    @Override
    public String store(byte[] content, String originalFilename, String directory) {
        String filename = UUID.randomUUID() + extension(UploadFileType.detect(content));
        Path target = resolveTarget(directory, filename);

        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            log.error("파일 저장 실패: {}", filename, e);
            throw new SignalException(ErrorCode.INTERNAL_SERVER_ERROR);
        }

        return toUrl(directory, filename);
    }

    @Override
    public byte[] load(String url) {
        String relativePath = url.startsWith(baseUrl + "/") ? url.substring(baseUrl.length() + 1) : url;
        Path target = rootDir.resolve(relativePath).normalize();
        if (!target.startsWith(rootDir)) {
            log.error("업로드 디렉토리 밖의 파일 접근 시도: {}", url);
            throw new SignalException(ErrorCode.NOT_FOUND);
        }

        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            log.error("파일 로드 실패: {}", target, e);
            throw new SignalException(ErrorCode.NOT_FOUND);
        }
    }

    @Override
    public void delete(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        String relativePath = url.startsWith(baseUrl + "/") ? url.substring(baseUrl.length() + 1) : url;
        Path target = rootDir.resolve(relativePath).normalize();
        if (!target.startsWith(rootDir)) {
            log.error("업로드 디렉토리 밖의 파일 삭제 시도: {}", url);
            return;
        }

        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            log.warn("파일 삭제 실패: {}", target, e);
        }
    }

    private Path resolveTarget(String directory, String filename) {
        Path target = rootDir.resolve(directory).normalize().resolve(filename).normalize();
        if (!target.startsWith(rootDir)) {
            throw new SignalException(ErrorCode.INVALID_INPUT);
        }
        return target;
    }

    private String toUrl(String directory, String filename) {
        return baseUrl + "/" + directory + "/" + filename;
    }

    // 확장자는 클라이언트 파일명이 아니라 확인된 형식에서만 정한다. 알 수 없는 형식은 저장하지 않는다.
    private String extension(Optional<UploadFileType> type) {
        return "." + type.orElseThrow(() -> new SignalException(ErrorCode.UNSUPPORTED_MEDIA_TYPE)).extension();
    }
}
