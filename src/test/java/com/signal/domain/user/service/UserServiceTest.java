package com.signal.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.signal.domain.user.entity.User;
import com.signal.domain.user.repository.UserRepository;
import com.signal.global.exception.ErrorCode;
import com.signal.global.exception.SignalException;
import com.signal.global.file.FileStorage;
import java.util.Optional;
import com.signal.domain.websearch.service.WebSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private FileStorage fileStorage;

    @Mock
    private WebSearchService webSearchService;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, fileStorage, webSearchService);
    }

    @Test
    void 프로필_이미지를_업로드하면_URL을_반환하고_User가_갱신된다() {
        User user = User.builder()
                .email("test@example.com")
                .password("encoded")
                .name("tester")
                .age(20)
                .gender(User.Gender.NONE)
                .build();
        MockMultipartFile image = new MockMultipartFile("image", "profile.png", "image/png", new byte[]{1, 2, 3});

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(fileStorage.store(any(), anyString())).thenReturn("/uploads/profile/generated.png");

        String result = userService.updateProfileImage(1L, image);

        assertThat(result).isEqualTo("/uploads/profile/generated.png");
        assertThat(user.getProfileImageUrl()).isEqualTo("/uploads/profile/generated.png");
        verify(fileStorage).store(image, "profile");
    }

    @Test
    void 지원하지_않는_파일_형식이면_예외가_발생한다() {
        MockMultipartFile file = new MockMultipartFile("image", "malware.exe", "application/octet-stream", new byte[]{1});

        assertThatThrownBy(() -> userService.updateProfileImage(1L, file))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void 파일_크기가_10MB를_초과하면_예외가_발생한다() {
        byte[] tooLarge = new byte[(int) (10L * 1024 * 1024) + 1];
        MockMultipartFile file = new MockMultipartFile("image", "big.png", "image/png", tooLarge);

        assertThatThrownBy(() -> userService.updateProfileImage(1L, file))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FILE_TOO_LARGE);
    }

    @Test
    void 존재하지_않는_유저면_예외가_발생한다() {
        MockMultipartFile image = new MockMultipartFile("image", "profile.jpg", "image/jpeg", new byte[]{1, 2, 3});
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateProfileImage(1L, image))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_FOUND);
    }
}
