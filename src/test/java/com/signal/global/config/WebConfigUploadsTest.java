package com.signal.global.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.signal.domain.user.repository.UserRepository;
import com.signal.global.file.TestFileBytes;
import com.signal.global.health.HealthController;
import com.signal.global.security.jwt.JwtProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * /uploads 정적 서빙: 탈퇴·기록 삭제로 지운 파일을 요청하면 읽기 오류(500)가 아니라 404여야 한다.
 * (리소스 체인에 캐시가 켜져 있으면 지워진 파일의 Resource를 계속 돌려줄 수 있어서 확인한다.)
 */
@WebMvcTest(controllers = HealthController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(WebConfig.class)
class WebConfigUploadsTest {

    private static final Path UPLOAD_DIR = createUploadDir();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private UserRepository userRepository;

    private static Path createUploadDir() {
        try {
            return Files.createTempDirectory("uploads-test");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void uploadDir(DynamicPropertyRegistry registry) {
        registry.add("file.upload-dir", UPLOAD_DIR::toString);
    }

    @AfterAll
    static void cleanUp() throws IOException {
        try (var files = Files.walk(UPLOAD_DIR)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @Test
    void 허용_형식의_파일은_서빙하고_지운_뒤에는_404를_돌려준다() throws Exception {
        Path file = Files.createDirectories(UPLOAD_DIR.resolve("protections")).resolve("a.png");
        Files.write(file, TestFileBytes.PNG);

        mockMvc.perform(get("/uploads/protections/a.png")).andExpect(status().isOk());
        mockMvc.perform(get("/uploads/protections/a.png")).andExpect(status().isOk());

        Files.delete(file);

        mockMvc.perform(get("/uploads/protections/a.png")).andExpect(status().isNotFound());
    }

    @Test
    void 허용_형식이_아닌_확장자는_파일이_있어도_서빙하지_않는다() throws Exception {
        Path file = Files.createDirectories(UPLOAD_DIR.resolve("profile")).resolve("x.html");
        Files.writeString(file, "<script>alert(1)</script>");

        mockMvc.perform(get("/uploads/profile/x.html")).andExpect(status().isNotFound());
    }
}
