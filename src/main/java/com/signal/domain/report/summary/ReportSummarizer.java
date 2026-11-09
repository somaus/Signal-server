package com.signal.domain.report.summary;

import java.util.Optional;

public interface ReportSummarizer {

    /**
     * 신고인이 쓴 피해 내용을 신고서에 넣을 사건 개요로 요약한다.
     * 요약이 필요 없거나(원문이 짧음) 만들지 못하면 비어 있는 Optional을 돌려준다. 예외는 던지지 않는다.
     */
    Optional<String> summarize(String description, String damageType);
}
