package com.signal.global.file;

import com.signal.global.exception.ErrorCode;
import com.signal.global.exception.SignalException;
import java.util.Set;
import org.springframework.web.multipart.MultipartFile;

/**
 * 업로드 파일의 공통 검증(빈 파일, 최대 크기, 허용 content-type, 내용과 content-type 일치)을 담당한다.
 * 도메인마다 최대 크기와 허용 content-type만 다르므로 값은 호출부에서 주입받는다.
 */
public final class UploadFileValidator {

    private UploadFileValidator() {
    }

    public static void validate(MultipartFile file, long maxSize, Set<String> allowedContentTypes) {
        if (file == null || file.isEmpty()) {
            throw new SignalException(ErrorCode.INVALID_INPUT);
        }
        if (file.getSize() > maxSize) {
            throw new SignalException(ErrorCode.FILE_TOO_LARGE);
        }
        String contentType = file.getContentType();
        // Set.of()로 만든 불변 집합은 contains(null)에서 NPE를 던지므로 먼저 null을 걸러낸다.
        if (contentType == null || !allowedContentTypes.contains(contentType)) {
            throw new SignalException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        }
        // 선언한 content-type은 클라이언트 마음대로이므로 실제 내용(매직바이트)과 같은지 확인한다.
        if (UploadFileType.detect(file).map(UploadFileType::contentType).filter(contentType::equals).isEmpty()) {
            throw new SignalException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        }
    }
}
