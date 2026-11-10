package com.signal.global.file;

import org.springframework.web.multipart.MultipartFile;

public interface FileStorage {

    /**
     * 파일을 저장하고 접근 가능한 URL을 반환한다.
     */
    String store(MultipartFile file, String directory);

    /**
     * 서버가 만든 바이트 배열(하이라이트, 보호 이미지 등)을 파일로 저장하고 접근 가능한 URL을 반환한다.
     * 확장자는 내용에서 정한다. 형식을 알 수 없으면 사용자 업로드 오류가 아니라 내부 오류이므로 IllegalStateException을 던진다.
     */
    String store(byte[] content, String directory);

    /**
     * 저장된 파일을 URL로 읽어온다.
     */
    byte[] load(String url);

    /**
     * 저장된 파일을 삭제한다. 이미 없으면 아무 일도 하지 않는다.
     */
    void delete(String url);
}
