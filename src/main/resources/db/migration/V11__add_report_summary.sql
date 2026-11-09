-- 신고서 요약(AI가 원문을 요약한 사건 개요). 신고 문서(PDF)는 이제 저장하지 않고 요청할 때 만들기 때문에 document_url은 더 쓰지 않는다.
ALTER TABLE reports ADD COLUMN summary VARCHAR(1500) NULL;
