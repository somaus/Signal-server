package com.signal.domain.websearch.service;

import com.signal.domain.monitoring.entity.MatchType;
import com.signal.domain.monitoring.monitor.ReverseImageSearcher;
import com.signal.domain.monitoring.monitor.ReverseImageSearcher.SearchHit;
import com.signal.domain.websearch.entity.UnavailableReason;
import com.signal.domain.websearch.entity.WebSearch;
import com.signal.domain.websearch.entity.WebSearchStatus;
import com.signal.domain.websearch.repository.WebSearchRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 탐지한 사진을 웹에서 역검색한다. web-search.provider가 none(기본)이면 아무것도 하지 않고, 응답의 webSearch는 null이다.
 * 외부 서비스의 무료 한도(SerpApi 월 250건)를 지키려고 사용자별 하루, 전체 하루, 전체 한 달 상한을 두며,
 * 상한에 닿거나 영상이면 UNAVAILABLE과 이유를 기록한다.
 */
@Slf4j
@Service
public class WebSearchService {

    /** 일일 상한은 한국 시간 자정에 초기화한다(컨테이너가 UTC여도 같다). */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final Duration STALE_AFTER = Duration.ofMinutes(5);

    private final WebSearchRepository webSearchRepository;
    private final WebSearchRunner runner;
    private final ReverseImageSearcher searcher;   // null이면 기능이 꺼진 상태
    private final int dailyLimit;
    private final int monthlyLimit;
    private final int ownerDailyLimit;

    public WebSearchService(WebSearchRepository webSearchRepository,
                            WebSearchRunner runner,
                            @Qualifier("serpApi") ObjectProvider<ReverseImageSearcher> searcher,
                            @Value("${web-search.daily-limit:8}") int dailyLimit,
                            @Value("${web-search.monthly-limit:240}") int monthlyLimit,
                            @Value("${web-search.owner-daily-limit:2}") int ownerDailyLimit) {
        this.webSearchRepository = webSearchRepository;
        this.runner = runner;
        this.searcher = searcher.getIfAvailable();
        this.dailyLimit = dailyLimit;
        this.monthlyLimit = monthlyLimit;
        this.ownerDailyLimit = ownerDailyLimit;
    }

    public boolean isEnabled() {
        return searcher != null;
    }

    /** 한도 확인과 저장 사이에 다른 요청이 끼어 상한을 넘기지 않게 직렬화한다(서버 한 대 기준). */
    private final Object startLock = new Object();

    /** 사진 하나에 검색은 한 번만 한다. 이미 있으면 그 결과를 그대로 쓴다. */
    public void start(Long detectionId, String fileUrl, boolean isVideo, String ownerKey) {
        if (searcher == null) {
            return;
        }
        try {
            synchronized (startLock) {
                if (webSearchRepository.findByDetectionId(detectionId).isPresent()) {
                    return;
                }
                begin(detectionId, fileUrl, isVideo, ownerKey);
            }
        } catch (Exception e) {
            // 웹 검색 준비가 실패해도 요청 자체는 성공해야 한다.
            log.error("웹 검색 시작 실패: detectionId={}, 원인={}", detectionId, e.toString());
        }
    }

    private void begin(Long detectionId, String fileUrl, boolean isVideo, String ownerKey) {
        UnavailableReason reason = unavailableReason(isVideo, ownerKey);
        if (reason != null) {
            log.warn("웹 검색을 하지 않습니다: detectionId={}, 이유={}", detectionId, reason);
            webSearchRepository.save(WebSearch.unavailable(detectionId, ownerKey, reason));
            return;
        }
        webSearchRepository.save(WebSearch.processing(detectionId, ownerKey));
        runner.search(detectionId, fileUrl, searcher);
    }

    /** 영상이거나 한도(사용자별 하루, 전체 하루, 전체 한 달)에 닿았으면 이유를, 검색해도 되면 null을 돌려준다. */
    private UnavailableReason unavailableReason(boolean isVideo, String ownerKey) {
        if (isVideo) {
            return UnavailableReason.VIDEO;
        }
        LocalDateTime today = startOfTodayKst();
        if (ownerKey != null && webSearchRepository.countByOwnerKeyAndCreatedAtGreaterThanEqualAndStatusNot(
                ownerKey, today, WebSearchStatus.UNAVAILABLE) >= ownerDailyLimit) {
            return UnavailableReason.OWNER_LIMIT;
        }
        if (webSearchRepository.countByCreatedAtGreaterThanEqualAndStatusNot(
                today, WebSearchStatus.UNAVAILABLE) >= dailyLimit) {
            return UnavailableReason.DAILY_LIMIT;
        }
        if (webSearchRepository.countByCreatedAtGreaterThanEqualAndStatusNot(
                startOfMonthKst(), WebSearchStatus.UNAVAILABLE) >= monthlyLimit) {
            return UnavailableReason.MONTHLY_LIMIT;
        }
        return null;
    }

    /**
     * 위험도 분석에서 사용자가 동의해 하는 검색. 호출한 스레드(분석 작업)에서 바로 수행하고 결과 건수만 돌려준다.
     * 한도는 탐지의 검색과 같은 표(web_searches)로 세므로 탐지 id 없는 행을 남기며, 찾은 페이지 주소는 저장하지 않는다.
     */
    public AssessmentSearchOutcome searchForAssessment(String ownerKey, byte[] image) {
        if (searcher == null) {
            return AssessmentSearchOutcome.unavailable(UnavailableReason.DISABLED);
        }
        WebSearch row;
        synchronized (startLock) {
            UnavailableReason reason = unavailableReason(false, ownerKey);
            if (reason != null) {
                log.warn("분석의 웹 검색을 하지 않습니다: 이유={}", reason);
                webSearchRepository.save(WebSearch.unavailable(null, ownerKey, reason));
                return AssessmentSearchOutcome.unavailable(reason);
            }
            row = webSearchRepository.save(WebSearch.processing(null, ownerKey));
        }
        try {
            List<SearchHit> hits = searcher.search(image);
            row.complete(List.of());
            webSearchRepository.save(row);
            int same = (int) hits.stream().filter(hit -> hit.matchType() == MatchType.FULL
                    || hit.matchType() == MatchType.PARTIAL).count();
            return AssessmentSearchOutcome.completed(same, hits.size() - same);
        } catch (Exception e) {
            row.fail();
            webSearchRepository.save(row);
            log.error("분석의 웹 검색 실패: 원인={}", e.toString());
            return AssessmentSearchOutcome.failed();
        }
    }

    /** 서버가 검색 도중 재시작되면 PROCESSING이 남으므로, 오래된 것은 FAILED로 정리해 돌려준다. */
    public WebSearch find(Long detectionId) {
        Optional<WebSearch> found = webSearchRepository.findByDetectionId(detectionId);
        found.filter(w -> w.getStatus() == WebSearchStatus.PROCESSING
                && w.getCreatedAt().isBefore(LocalDateTime.now().minus(STALE_AFTER))).ifPresent(w -> {
            w.fail();
            webSearchRepository.save(w);
        });
        return found.orElse(null);
    }

    /** 계정을 지울 때 그 사용자의 검색 기록도 지운다. */
    @Transactional
    public void deleteByOwner(Long userId) {
        webSearchRepository.deleteByOwnerKey(WebSearchOwnerKey.user(userId));
    }

    /** 보존 기간이 지난 검색 결과(페이지 URL 목록)를 지운다. */
    @Transactional
    public int purgeOlderThan(LocalDateTime before) {
        return webSearchRepository.deleteByCreatedAtBefore(before);
    }

    /** 저장된 시각은 서버 기본 시간대이므로, 한국 시간 자정을 같은 시간대로 옮겨 비교한다. */
    private static LocalDateTime startOfTodayKst() {
        return LocalDate.now(KST).atStartOfDay(KST).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }

    private static LocalDateTime startOfMonthKst() {
        return LocalDate.now(KST).withDayOfMonth(1).atStartOfDay(KST)
                .withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }
}
