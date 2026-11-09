-- 재발급에 쓰인 시각. 같은 토큰이 직후에 다시 오는 정상 중복 요청(두 번째 탭, 응답 유실 뒤 재전송)과
-- 한참 뒤의 재사용(탈취 의심)을 구분하는 데 쓴다. 이미 쓰인 기존 행은 NULL이라 유예 없이 재사용으로 본다.
ALTER TABLE refresh_tokens ADD COLUMN used_at DATETIME(6) NULL;
