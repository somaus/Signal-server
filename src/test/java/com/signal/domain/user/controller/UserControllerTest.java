package com.signal.domain.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.signal.domain.user.entity.User;
import com.signal.domain.user.repository.UserRepository;
import com.signal.domain.user.service.UserService;
import com.signal.global.config.SecurityConfig;
import com.signal.global.exception.ErrorCode;
import com.signal.global.exception.GlobalExceptionHandler;
import com.signal.global.exception.SignalException;
import com.signal.global.security.JwtAccessDeniedHandler;
import com.signal.global.security.JwtAuthenticationEntryPoint;
import com.signal.global.security.jwt.JwtAuthenticationFilter;
import com.signal.global.security.jwt.JwtProvider;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(UserController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = "cors.allowed-origins=http://localhost:3000")
class UserControllerTest {

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.signal.global.file.FileUrlSigner fileUrlSigner;

    private static final RequestPostProcessor USER_1 = authentication(new UsernamePasswordAuthenticationToken(
            1L, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private UserRepository userRepository;

    private User user() {
        User user = User.builder().email("a@b.com").password("x").name("홍길동").age(30)
                .gender(User.Gender.MALE).build();
        ReflectionTestUtils.setField(user, "id", 1L);
        return user;
    }

    @Test
    void 내_프로필을_조회하면_비밀번호_없이_반환한다() throws Exception {
        when(userService.getMyProfile(1L)).thenReturn(user());

        mockMvc.perform(get("/api/v1/users/me").with(USER_1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(1))
                .andExpect(jsonPath("$.email").value("a@b.com"))
                .andExpect(jsonPath("$.name").value("홍길동"))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void 인증_없이_내_정보_API는_401이다() throws Exception {
        mockMvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/users/me")).andExpect(status().isUnauthorized());
        verifyNoInteractions(userService);
    }

    @Test
    void 프로필을_수정한다() throws Exception {
        when(userService.updateProfile(eq(1L), any())).thenReturn(user());

        mockMvc.perform(patch("/api/v1/users/me").with(USER_1)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"홍길동\",\"age\":30}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("홍길동"));
    }

    @Test
    void 공백_이름이나_범위_밖_나이는_400이다() throws Exception {
        mockMvc.perform(patch("/api/v1/users/me").with(USER_1)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mockMvc.perform(patch("/api/v1/users/me").with(USER_1)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"age\":0}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/v1/users/me").with(USER_1)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"gender\":\"X\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(userService);
    }

    @Test
    void 탈퇴하면_204다() throws Exception {
        mockMvc.perform(delete("/api/v1/users/me").with(USER_1))
                .andExpect(status().isNoContent());
        verify(userService).deleteAccount(1L);
    }

    @Test
    void 프로필_이미지를_올리면_URL을_반환한다() throws Exception {
        MockMultipartFile image = new MockMultipartFile("image", "a.png", "image/png", new byte[] {1});
        when(userService.updateProfileImage(eq(1L), any())).thenReturn("/uploads/a.png");

        mockMvc.perform(multipart("/api/v1/users/me/profile-image").file(image).with(USER_1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileImageUrl").value("/uploads/a.png"));
    }

    @Test
    void 프로필_이미지_파트가_없으면_400이다() throws Exception {
        mockMvc.perform(multipart("/api/v1/users/me/profile-image").with(USER_1))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 지원하지_않는_파일_형식이면_서비스_에러를_그대로_매핑한다() throws Exception {
        MockMultipartFile file = new MockMultipartFile("image", "a.txt", "text/plain", new byte[] {1});
        when(userService.updateProfileImage(eq(1L), any()))
                .thenThrow(new SignalException(ErrorCode.UNSUPPORTED_MEDIA_TYPE));

        mockMvc.perform(multipart("/api/v1/users/me/profile-image").file(file).with(USER_1))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }
}
