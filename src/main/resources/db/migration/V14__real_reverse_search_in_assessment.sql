-- 위험도 분석에서 사용자가 동의하면 실제 웹 역이미지 검색 결과를 점수에 반영한다(#55).
-- 분석에서 한 검색은 탐지의 검색과 같은 한도 표(web_searches)로 세므로 탐지 id 없이도 행을 둘 수 있게 한다.
ALTER TABLE web_searches MODIFY COLUMN detection_id BIGINT NULL;

ALTER TABLE risk_assessments
    ADD COLUMN web_search_consent BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN web_search_status VARCHAR(20) NULL,
    ADD COLUMN web_search_reason VARCHAR(20) NULL;
