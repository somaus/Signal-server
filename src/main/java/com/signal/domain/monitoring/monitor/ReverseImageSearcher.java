package com.signal.domain.monitoring.monitor;

import com.signal.domain.monitoring.entity.MatchType;
import java.util.List;

public interface ReverseImageSearcher {

    /** 이미지와 같거나 비슷한 이미지가 실린 웹 페이지를 찾는다. 실패하면 예외를 던진다. */
    List<SearchHit> search(byte[] image);

    /**
     * @param pageUrl 이미지가 실린 페이지
     * @param imageUrl 페이지에 있는 일치 이미지(없으면 null)
     * @param matchType 일치 종류. 점수가 아니며 얼굴 동일인 판정도 아니다
     */
    record SearchHit(String pageUrl, String imageUrl, String title, MatchType matchType) {
    }
}
