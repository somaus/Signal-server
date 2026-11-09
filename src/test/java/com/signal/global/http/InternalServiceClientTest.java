package com.signal.global.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.util.MultiValueMap;

class InternalServiceClientTest {

    @Test
    void 파일_본문은_file_파트에_이름과_바이트를_담는다() throws Exception {
        MultiValueMap<String, Object> body = InternalServiceClient.fileBody(new byte[] {1, 2, 3}, "a.png");

        Resource part = (Resource) body.getFirst("file");
        assertThat(part.getFilename()).isEqualTo("a.png");
        assertThat(part.contentLength()).isEqualTo(3);
    }

    @Test
    void 파일_이름이_비어_있으면_image를_쓴다() {
        assertThat(((Resource) InternalServiceClient.fileBody(new byte[0], null).getFirst("file")).getFilename())
                .isEqualTo("image");
        assertThat(((Resource) InternalServiceClient.fileBody(new byte[0], " ").getFirst("file")).getFilename())
                .isEqualTo("image");
    }

    @Test
    void 업로드_주소에서_파일_이름만_뽑는다() {
        assertThat(InternalServiceClient.filenameOf("/uploads/deepfake-detections/abc.png")).isEqualTo("abc.png");
        assertThat(InternalServiceClient.filenameOf("abc.png")).isEqualTo("abc.png");
        assertThat(InternalServiceClient.filenameOf("/uploads/x/")).isEqualTo("image");
        assertThat(InternalServiceClient.filenameOf(null)).isEqualTo("image");
        assertThat(InternalServiceClient.filenameOf("")).isEqualTo("image");
    }

    @Test
    void 원인_사슬_안의_타임아웃을_찾는다() {
        assertThat(InternalServiceClient.isTimeout(new RuntimeException(new SocketTimeoutException("read")))).isTrue();
        assertThat(InternalServiceClient.isTimeout(new RuntimeException(new HttpTimeoutException("http")))).isTrue();
        assertThat(InternalServiceClient.isTimeout(new RuntimeException("x"))).isFalse();
        assertThat(InternalServiceClient.isTimeout(null)).isFalse();
    }
}
