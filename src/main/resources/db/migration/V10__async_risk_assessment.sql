-- 위험도 분석을 접수 후 조회 방식으로 바꾸면서, 분석이 끝나기 전·실패한 건은 결과 컬럼이 비어 있을 수 있다.
-- 기존 행은 모두 분석이 끝난 결과이므로 status를 COMPLETED로 채운다.
ALTER TABLE risk_assessments
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
    ADD COLUMN failure_reason VARCHAR(30) NULL,
    MODIFY COLUMN overall_risk_level VARCHAR(10) NULL,
    MODIFY COLUMN overall_score INT NULL;
