-- 얼굴 좌표를 모르는 경우 박스를 지어내지 않고 비워 두므로 좌표 컬럼을 NULL 허용으로 바꾼다.
ALTER TABLE risk_assessment_faces
    MODIFY COLUMN x INT NULL,
    MODIFY COLUMN y INT NULL,
    MODIFY COLUMN width INT NULL,
    MODIFY COLUMN height INT NULL;
