package com.signal.global.file;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.signal.global.exception.ErrorCode;
import com.signal.global.exception.SignalException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class UploadFileValidatorTest {

    private static final long MAX_SIZE = 12L;
    private static final Set<String> ALLOWED = Set.of("image/jpeg", "image/png");

    @Test
    void 허용된_파일은_통과한다() {
        MockMultipartFile file = new MockMultipartFile("image", "a.png", "image/png", TestFileBytes.PNG);

        assertThatCode(() -> UploadFileValidator.validate(file, MAX_SIZE, ALLOWED)).doesNotThrowAnyException();
    }

    @Test
    void 파일이_null이면_INVALID_INPUT_예외가_발생한다() {
        assertThatThrownBy(() -> UploadFileValidator.validate(null, MAX_SIZE, ALLOWED))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);
    }

    @Test
    void 파일이_비어있으면_INVALID_INPUT_예외가_발생한다() {
        MockMultipartFile file = new MockMultipartFile("image", "a.png", "image/png", new byte[0]);

        assertThatThrownBy(() -> UploadFileValidator.validate(file, MAX_SIZE, ALLOWED))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);
    }

    @Test
    void 최대_크기를_초과하면_FILE_TOO_LARGE_예외가_발생한다() {
        MockMultipartFile file = new MockMultipartFile("image", "a.png", "image/png", new byte[13]);

        assertThatThrownBy(() -> UploadFileValidator.validate(file, MAX_SIZE, ALLOWED))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FILE_TOO_LARGE);
    }

    @Test
    void 허용되지_않은_content_type이면_UNSUPPORTED_MEDIA_TYPE_예외가_발생한다() {
        MockMultipartFile file = new MockMultipartFile("file", "a.gif", "image/gif", new byte[]{1});

        assertThatThrownBy(() -> UploadFileValidator.validate(file, MAX_SIZE, ALLOWED))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void content_type이_null이면_UNSUPPORTED_MEDIA_TYPE_예외가_발생한다() {
        MockMultipartFile file = new MockMultipartFile("file", "a", null, new byte[]{1});

        assertThatThrownBy(() -> UploadFileValidator.validate(file, MAX_SIZE, ALLOWED))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void 내용이_선언한_content_type과_다르면_UNSUPPORTED_MEDIA_TYPE_예외가_발생한다() {
        MockMultipartFile html = new MockMultipartFile("file", "a.png", "image/png", "<html></html>".getBytes());
        MockMultipartFile jpegAsPng = new MockMultipartFile("file", "a.png", "image/png", TestFileBytes.JPEG);

        assertThatThrownBy(() -> UploadFileValidator.validate(html, 100L, ALLOWED))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        assertThatThrownBy(() -> UploadFileValidator.validate(jpegAsPng, 100L, ALLOWED))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void 영상과_PDF도_내용으로_확인한다() {
        Set<String> allowed = Set.of("video/mp4", "application/pdf");
        MockMultipartFile mp4 = new MockMultipartFile("file", "a.mp4", "video/mp4", TestFileBytes.MP4);
        MockMultipartFile pdf = new MockMultipartFile("file", "a.pdf", "application/pdf", "%PDF-1.7".getBytes());
        MockMultipartFile fakeMp4 = new MockMultipartFile("file", "a.mp4", "video/mp4", TestFileBytes.PNG);

        assertThatCode(() -> UploadFileValidator.validate(mp4, 100L, allowed)).doesNotThrowAnyException();
        assertThatCode(() -> UploadFileValidator.validate(pdf, 100L, allowed)).doesNotThrowAnyException();
        assertThatThrownBy(() -> UploadFileValidator.validate(fakeMp4, 100L, allowed))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }
}
