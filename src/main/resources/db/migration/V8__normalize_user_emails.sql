-- 가입·로그인이 이메일을 소문자·trim으로 정규화하므로 이미 저장된 값도 같게 맞춘다.
-- MySQL 기본 컬레이션은 대소문자를 구분하지 않아 대소문자만 다른 중복은 이미 없다.
UPDATE users SET email = LOWER(TRIM(email)) WHERE email <> LOWER(TRIM(email));
