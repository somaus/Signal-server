package com.signal.global.file;

import org.springframework.web.multipart.MultipartFile;

public interface FileStorage {

    /**
     * 파일을 저장하고 접근 가능한 URL을 반환한다.
     */
    String store(MultipartFile file, String directory);

    /**
     * 바이트 배열을 파일로 저장하고 접근 가능한 URL을 반환한다.
     */
    String store(byte[] content, String originalFilename, String directory);

    /**
     * 저장된 파일을 URL로 읽어온다.
     */
    byte[] load(String url);

    /**
     * 저장된 파일을 삭제한다. 이미 없으면 아무 일도 하지 않는다.
     */
    void delete(String url);
}
