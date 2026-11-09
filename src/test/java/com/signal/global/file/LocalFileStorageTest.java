package com.signal.global.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.signal.global.exception.ErrorCode;
import com.signal.global.exception.SignalException;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class LocalFileStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void 파일을_저장하면_baseUrl과_확장자를_포함한_URL을_반환한다() throws Exception {
        LocalFileStorage storage = new LocalFileStorage(tempDir.toString(), "/uploads");
        storage.init();
        MockMultipartFile image = new MockMultipartFile("image", "profile.html", "image/png", TestFileBytes.PNG);

        String url = storage.store(image, "profile");

        assertThat(url).startsWith("/uploads/profile/").endsWith(".png");

        String filename = url.substring(url.lastIndexOf('/') + 1);
        Path savedFile = tempDir.resolve("profile").resolve(filename);
        assertThat(Files.exists(savedFile)).isTrue();
        assertThat(Files.readAllBytes(savedFile)).isEqualTo(TestFileBytes.PNG);
    }

    @Test
    void 내용이_허용_형식이_아니면_파일명이_이미지여도_저장하지_않는다() throws Exception {
        LocalFileStorage storage = new LocalFileStorage(tempDir.toString(), "/uploads");
        storage.init();
        MockMultipartFile html = new MockMultipartFile("image", "a.png", "image/png", "<html></html>".getBytes());

        assertThatThrownBy(() -> storage.store(html, "profile"))
                .isInstanceOfSatisfying(SignalException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE));
    }

    @Test
    void 서버가_만든_바이트의_형식을_알_수_없으면_415가_아니라_내부_오류로_던진다() {
        LocalFileStorage storage = new LocalFileStorage(tempDir.toString(), "/uploads");
        storage.init();

        assertThatThrownBy(() -> storage.store("<html></html>".getBytes(), "profile"))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(SignalException.class);
    }

    @Test
    void 바이트배열을_저장하면_baseUrl과_확장자를_포함한_URL을_반환한다() {
        LocalFileStorage storage = new LocalFileStorage(tempDir.toString(), "/uploads");
        storage.init();

        String url = storage.store(TestFileBytes.PNG, "protections");

        assertThat(url).startsWith("/uploads/protections/").endsWith(".png");
    }

    @Test
    void 저장한_파일을_URL로_다시_읽어올_수_있다() {
        LocalFileStorage storage = new LocalFileStorage(tempDir.toString(), "/uploads");
        storage.init();
        String url = storage.store(TestFileBytes.PNG, "protections");

        byte[] loaded = storage.load(url);

        assertThat(loaded).isEqualTo(TestFileBytes.PNG);
    }

    @Test
    void 파일명이_이상해도_확장자는_내용에서_정해지고_지정한_디렉토리에_저장된다() throws Exception {
        LocalFileStorage storage = new LocalFileStorage(tempDir.toString(), "/uploads");
        storage.init();

        String url = storage.store(TestFileBytes.PNG, "profile");
        String url2 = storage.store(TestFileBytes.PNG, "profile");

        assertThat(url).matches("^/uploads/profile/[0-9a-fA-F-]{36}\\.png$");
        assertThat(url2).matches("^/uploads/profile/[0-9a-fA-F-]{36}\\.png$");
        try (var files = Files.walk(tempDir)) {
            assertThat(files.filter(Files::isRegularFile).map(f -> f.getParent()))
                    .containsOnly(tempDir.resolve("profile"));
        }
    }

    @Test
    void 업로드_디렉토리_밖을_가리키는_URL은_읽을_수_없다() throws Exception {
        LocalFileStorage storage = new LocalFileStorage(tempDir.resolve("root").toString(), "/uploads");
        storage.init();
        Path outside = Files.write(tempDir.resolve("secret.txt"), new byte[]{9});

        assertThatThrownBy(() -> storage.load("/uploads/../secret.txt"))
                .isInstanceOfSatisfying(SignalException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> storage.load(outside.toString()))
                .isInstanceOfSatisfying(SignalException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void 업로드_디렉토리_밖으로_벗어나는_저장_디렉토리는_거부한다() {
        LocalFileStorage storage = new LocalFileStorage(tempDir.resolve("root").toString(), "/uploads");
        storage.init();

        assertThatThrownBy(() -> storage.store(TestFileBytes.PNG, "../escape"))
                .isInstanceOfSatisfying(SignalException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    void 주소가_비어_있으면_삭제를_건너뛴다() throws Exception {
        LocalFileStorage storage = new LocalFileStorage(tempDir.toString(), "/uploads");
        storage.init();

        storage.delete(null);
        storage.delete("  ");

        assertThat(tempDir).exists();
    }
}
