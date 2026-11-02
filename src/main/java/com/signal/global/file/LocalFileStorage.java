package com.signal.global.file;

import com.signal.global.exception.ErrorCode;
import com.signal.global.exception.SignalException;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Component
public class LocalFileStorage implements FileStorage {

    private static final Pattern SAFE_EXTENSION = Pattern.compile("\\.[A-Za-z0-9]{1,10}");

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
        String filename = UUID.randomUUID() + extractExtension(file.getOriginalFilename());
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
        String filename = UUID.randomUUID() + extractExtension(originalFilename);
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

    private String extractExtension(String originalFilename) {
        if (originalFilename == null) {
            return "";
        }
        int dotIndex = originalFilename.lastIndexOf('.');
        if (dotIndex < 0) {
            return "";
        }
        // 확장자는 사용자 입력이므로 경로 구분자 등이 섞이지 않도록 영숫자만 허용한다.
        String extension = originalFilename.substring(dotIndex);
        return SAFE_EXTENSION.matcher(extension).matches() ? extension : "";
    }
}
