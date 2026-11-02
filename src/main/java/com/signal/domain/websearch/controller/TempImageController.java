package com.signal.domain.websearch.controller;

import com.signal.domain.websearch.client.TempImageStore;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 외부 역이미지 검색 서비스가 검색하는 동안만 가져가는 임시 이미지. 토큰을 아는 쪽만 받을 수 있고 곧 만료된다. */
@Hidden
@RestController
@RequestMapping("/api/v1/web-search/temp-images")
@RequiredArgsConstructor
public class TempImageController {

    private final TempImageStore tempImageStore;

    @GetMapping("/{token}")
    public ResponseEntity<byte[]> get(@PathVariable String token) {
        TempImageStore.Entry entry = tempImageStore.get(token);
        if (entry == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(entry.contentType()))
                .cacheControl(CacheControl.noStore())
                .body(entry.content());
    }
}
