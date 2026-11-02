-- 주기적 재검색(하루 1회)을 위한 검색 시각·실패 횟수, 실제 검색 결과의 제목, 긴 URL 대응
ALTER TABLE monitorings
    ADD COLUMN last_searched_at DATETIME(6) NULL,
    ADD COLUMN next_search_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ADD COLUMN consecutive_failures INT NOT NULL DEFAULT 0;

ALTER TABLE detections
    ADD COLUMN title VARCHAR(500) NULL,
    ADD COLUMN match_type VARCHAR(10) NULL,
    MODIFY COLUMN similarity DOUBLE NULL,
    MODIFY COLUMN source_url VARCHAR(2048) NOT NULL,
    MODIFY COLUMN thumbnail_url VARCHAR(2048) NULL;
