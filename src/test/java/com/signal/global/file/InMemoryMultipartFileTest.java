package com.signal.global.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InMemoryMultipartFileTest {

    @Test
    void 이름_형식_크기와_바이트를_그대로_돌려준다() throws IOException {
        byte[] content = {1, 2, 3};
        InMemoryMultipartFile file = new InMemoryMultipartFile("image", "photo.png", "image/png", content);

        assertThat(file.getName()).isEqualTo("image");
        assertThat(file.getOriginalFilename()).isEqualTo("photo.png");
        assertThat(file.getContentType()).isEqualTo("image/png");
        assertThat(file.getSize()).isEqualTo(3);
        assertThat(file.isEmpty()).isFalse();
        assertThat(file.getBytes()).containsExactly(1, 2, 3);
        assertThat(file.getInputStream().readAllBytes()).containsExactly(1, 2, 3);
    }

    @Test
    void 돌려준_바이트를_바꿔도_원본은_바뀌지_않는다() {
        InMemoryMultipartFile file = new InMemoryMultipartFile("image", "a.png", "image/png", new byte[]{1, 2});

        file.getBytes()[0] = 9;

        assertThat(file.getBytes()).containsExactly(1, 2);
    }

    @Test
    void 내용이_없으면_비어_있다() {
        assertThat(new InMemoryMultipartFile("image", "a.png", "image/png", null).isEmpty()).isTrue();
    }

    @Test
    void 파일로_저장할_수_있다(@TempDir Path dir) throws IOException {
        InMemoryMultipartFile file = new InMemoryMultipartFile("image", "a.png", "image/png", new byte[]{4, 5});
        Path target = dir.resolve("out.bin");

        file.transferTo(target);

        assertThat(Files.readAllBytes(target)).containsExactly(4, 5);
    }
}
