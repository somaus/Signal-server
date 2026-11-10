# 배포 가이드 (학교 서버 / Docker)

## 자동 배포 (CD)

`main`에 push되면 [.github/workflows/deploy.yml](.github/workflows/deploy.yml)이 자동으로 배포한다.

1. GitHub 러너가 이미지를 빌드해 `ghcr.io/somaus/signal-server:<sha>`로 push
2. 서버에 SSH 접속 → 해당 커밋으로 `git reset --hard` → 이미지 pull → 컨테이너 교체
3. 헬스체크 실패 시 **이전 이미지로 자동 롤백**

서버가 1 vCPU / 2GB라서 서버에서 직접 빌드하면 배포 중 API가 응답 불능이 될 수 있다.
그래서 빌드는 러너에서 하고 서버는 pull만 한다.

PR을 열면 배포 없이 **빌드만 검증**한다.

### 엔티티에 필드/테이블이 추가된 배포

`prod` 프로필은 `ddl-auto: validate`라서 스키마를 먼저 반영해야 한다.
Actions → CD → **Run workflow**에서 `run_schema_update`를 체크하고 실행한다.
(push로 자동 실행된 배포는 이 단계를 건너뛰므로, 스키마가 안 맞으면 헬스체크에서 실패하고 롤백된다.)

### 서버에 필요한 것

`.env`만 서버에 있으면 된다 (git에 안 올라가므로 최초 1회 직접 생성).
CD는 `.env`를 건드리지 않으며, `git reset --hard`는 gitignore된 `.env`를 지우지 않는다.
단 **추적되는 파일을 서버에서 직접 수정하면 배포 때 덮어써진다.**

### 비로그인 IP 제한

비로그인 탐지·위험도 분석·상담·웹 검색은 IP당 1시간 30회, 인증 메일 발송(`POST /api/v1/auth/verification/send`)은 IP당 1시간 10회로 막는다
(`anonymous.ip-rate-limit.max-requests`, `anonymous.ip-rate-limit.verification-max-requests`, `anonymous.ip-rate-limit.window`).
IP는 `getRemoteAddr()`만 쓴다. 인스턴스 메모리라 재시작하면 초기화된다.

- **머지·배포 전에 운영 서버에서 `getRemoteAddr()`가 클라이언트마다 다른 값인지 확인한다.** 앞단 프록시나 Docker 네트워크 때문에 같은 값(게이트웨이·브리지 IP)이면
  모든 비로그인 사용자가 한 칸을 같이 써서 서비스 전체가 1시간에 30회만 쓸 수 있다.
- 프록시 뒤라면 `server.forward-headers-strategy`를 켜야 실제 클라이언트 IP가 된다. 단 신뢰하는 프록시가 `X-Forwarded-For`를 덮어쓸 때만 켠다(아니면 클라이언트가 위조해 제한을 피한다).
- **학교·행사장처럼 한 공인 IP를 여럿이 쓰는 시연 전에 `max-requests`(필요하면 `verification-max-requests`)를 올려 둔다.** 같은 Wi-Fi의 기기들이 한 IP로 보인다.

### 전용 딥페이크 탐지 서비스 (detect)

탐지 방식은 `deepfake-detection.mode`로 정한다. `llm`은 기존 비전 LLM 탐지, `http`는 별도 서비스(`detect-lab`, CommunityForensics ViT-S)의 점수로 등급을 정한다. 자세한 내용은 [docs/deepfake-detection.md](docs/deepfake-detection.md).

CD의 기본은 `llm`이다(기존 비전 LLM 탐지, detect는 띄우지 않는다). 평가(#33, #35)로 기준선을 넘는 걸 확인한 뒤 **저장소 변수로 켠다**. 모델을 켜는 결정과 배포 자동화를 분리하려는 것이다.

켜는 방법: 저장소 변수 `DEEPFAKE_DETECTION_MODE`를 `http`로 만들고(Settings > Secrets and variables > Actions > Variables) Actions > CD > Run workflow로 다시 실행한다. 켠 뒤 배포는 아래와 같이 움직인다.

1. 러너가 `detect-lab`도 빌드해 `ghcr.io/somaus/signal-detect:<sha>`로 push한다. 서버는 pull만 한다.
2. 서버에서 `docker compose --profile detect up -d detect`로 띄우고 healthy가 될 때까지 최대 5분 기다린다. 첫 배포는 모델(약 87MB)을 받는다. 가중치는 볼륨(`detect-model-cache`)에 남아 다음부터는 받지 않는다.
3. **준비되지 않으면 앱은 `llm` 방식으로 배포한다.** `http` 방식에서 detect가 죽어 있으면 탐지 요청이 모두 실패하므로, 사이트가 멈추지 않게 하려는 안전장치다. 이때 실패한 detect 컨테이너는 내리고(크래시 루프로 서버 자원을 쓰지 않게), CD 실행 요약에 경고(`::warning::`)가 남는다. 워크플로는 초록색이어도 요약의 경고를 확인한다.
4. detect 컨테이너는 `restart: unless-stopped`라 서버가 재부팅되어도 다시 올라온다. 매번 직접 띄울 필요가 없다.

되돌리려면 변수를 `llm`으로 바꾸거나 지우고 CD를 다시 실행한다. 이때 detect 컨테이너는 내려가 메모리를 돌려준다. `http`와 `llm` 이외의 값은 무시되고(서버 설정 유지) 경고가 남는다.

**서버에서 detect를 직접 다룰 때는** CD가 쓴 `DETECT_IMAGE`를 같이 주거나 CD를 다시 실행한다. 이미지 이름 없이 `docker compose --profile detect up -d detect`를 치면 GHCR 이미지가 아니라 로컬 빌드(`signal-detect:local`)를 찾고, 없으면 서버에서 빌드를 시도해 1 vCPU 서버가 멈출 수 있다.

**켜기 전에 서버 여유를 확인한다.** 한도는 detect 컨테이너만 지키므로 mysql + app(JVM) + detect가 2GB를 넘으면 호스트 OOM killer가 mysql이나 app을 죽일 수 있다. 서버에서 `free -m`(스왑 유무 포함)과 `docker stats --no-stream`으로 현재 사용량을 보고, 여유가 적으면 서버 `.env`에 `DETECT_MEM_LIMIT`을 낮게 넣고 시작한다.

서버가 1 vCPU / 2GB라 detect에는 메모리 한도(`DETECT_MEM_LIMIT`, 기본 768m)와 스레드 1, 동시 처리 1을 걸어 두었다. **실측 전의 잠정값**이다. 첫 배포 뒤 `docker stats`로 사용량을 보고 조정한다. 한도를 넘으면 detect만 재시작되고 mysql과 app은 영향을 받지 않는다.

`http` 방식으로 처음 배포하기 전에는 DB 스키마 변경(V5 마이그레이션)이 들어 있는 버전이 먼저 배포돼 있어야 한다.

### 필요한 GitHub Secrets

| 이름 | 값 |
| --- | --- |
| `SSH_HOST` | 서버 주소 |
| `SSH_PORT` | SSH 포트 |
| `SSH_USER` | 접속 계정 |
| `SSH_PRIVATE_KEY` | 배포용 SSH 개인키 (공개키는 서버 `~/.ssh/authorized_keys`에 등록) |

GHCR 인증은 워크플로의 `GITHUB_TOKEN`을 서버에 전달해 처리하므로 별도 PAT가 필요 없다.

---

## 수동 배포

아래는 CD 없이 직접 배포하거나, CD가 실패했을 때 쓰는 절차다.

## 0. 사전 준비 (서버에 Docker 설치돼있어야 함)

```bash
docker --version
docker compose version
```

둘 다 안 되면 Docker 설치부터:

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER
# 위 명령 실행 후 로그아웃 → 재로그인 필요
```

## 1. 코드 받기

```bash
git clone <레포_주소> signal-server
cd signal-server
```

## 2. `.env` 파일 올리기

`.env`는 git에 안 올라가므로(비밀정보 포함) 로컬에서 서버로 직접 복사해야 함.

```bash
scp .env <서버계정>@<서버주소>:~/signal-server/.env
```

scp로 옮기는 게 제일 안전함 (실제 비밀번호/키가 이 문서에는 안 적혀있음 — 로컬 `.env`에만 있음).

> 챗봇이 실제 LLM(Groq)으로 답하게 하려면 `.env`에 `GROQ_API_KEY`를 추가해야 함 (https://console.groq.com 에서 무료 발급). 없어도 서버는 정상 구동되며, 이 경우 챗봇은 자동으로 룰 기반 응답으로 동작함.

## 3. 최초 1회: DB 스키마 생성

`prod` 프로필은 `ddl-auto: validate`라서 빈 DB에는 그냥 못 뜸. 최초 1번만 `update` 모드로 스키마를 만들어줘야 함.

```bash
docker compose build app
docker compose run --rm -e SPRING_JPA_HIBERNATE_DDLAUTO=update app
```

> ⚠️ `docker compose build app`을 꼭 먼저 실행할 것. `docker compose run`은 이미지를 자동으로 새로 빌드하지 않아서, 방금 `git pull`한 새 코드가 아니라 예전에 캐시된 이미지로 실행될 수 있음 (스키마 변경사항이 반영 안 된 채로 돌아가서 계속 실패하는 원인이 됨).

로그에 아래처럼 뜨면 성공 → `Ctrl + C`로 종료:

```
Started SignalApplication in ... seconds
```

> ⚠️ 이 단계는 **컬럼/테이블이 추가되는 배포마다** 필요함 (최초 배포 포함). 코드 변경이 엔티티 필드 추가 없이 로직만 바뀐 거면 생략 가능.

## 4. 실행

```bash
docker compose up -d --build
```

## 5. 확인

```bash
docker compose ps
curl -i http://localhost:8080/swagger-ui/index.html
```

`200`이나 정상 응답 오면 배포 완료.

## 이후 재배포 (코드 수정 후)

엔티티에 새 필드/테이블이 생겼으면 (마이그레이션 필요):
```bash
git pull
docker compose build app
docker compose run --rm -e SPRING_JPA_HIBERNATE_DDLAUTO=update app
# Started 뜨면 Ctrl+C
docker compose up -d --build
```

로직만 바뀌고 스키마 변경이 없으면:
```bash
git pull
docker compose up -d --build
```

## 자주 쓰는 명령어

```bash
docker compose logs -f app      # 앱 로그 실시간으로 보기
docker compose restart app      # 앱만 재시작
docker compose down             # 전체 정지 (데이터는 volume에 남아있음)
docker compose down -v          # 전체 정지 + DB/업로드 데이터까지 삭제 (주의!)
```
