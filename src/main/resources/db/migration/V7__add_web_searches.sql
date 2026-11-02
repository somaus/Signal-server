-- 탐지한 사진의 웹 역이미지 검색 결과
CREATE TABLE web_searches (
    id BIGINT NOT NULL AUTO_INCREMENT,
    detection_id BIGINT NOT NULL,
    owner_key VARCHAR(100) NULL,
    status VARCHAR(20) NOT NULL,
    unavailable_reason VARCHAR(20) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_web_searches_detection UNIQUE (detection_id)
);

CREATE TABLE web_search_matches (
    web_search_id BIGINT NOT NULL,
    match_order INT NOT NULL,
    page_url VARCHAR(2048) NOT NULL,
    title VARCHAR(500) NULL,
    match_type VARCHAR(10) NULL,
    PRIMARY KEY (web_search_id, match_order),
    CONSTRAINT fk_web_search_matches_search FOREIGN KEY (web_search_id) REFERENCES web_searches (id)
);
