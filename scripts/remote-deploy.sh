#!/usr/bin/env bash
#
# 서버에서 실행되는 배포 스크립트. GitHub Actions(.github/workflows/deploy.yml)가 SSH로 호출한다.
# 직접 실행해도 된다: APP_IMAGE=... GHCR_USER=... GHCR_TOKEN=... bash scripts/remote-deploy.sh
#
# 필수 환경변수
#   APP_IMAGE   배포할 이미지 (예: ghcr.io/somaus/signal-server:<sha>)
#   GHCR_USER   GHCR 로그인 사용자
#   GHCR_TOKEN  GHCR 토큰 (Actions의 GITHUB_TOKEN — 워크플로 실행 중에만 유효)
# 선택 환경변수
#   RUN_SCHEMA_UPDATE=true  엔티티에 컬럼/테이블이 추가된 배포에서 ddl-auto=update로 스키마를 먼저 반영
#   DEEPFAKE_DETECTION_MODE  http면 전용 탐지 서비스(detect)를 띄우고 앱이 그걸 쓰게 한다. llm이면 detect를 내린다.
#                            비어 있으면 건드리지 않는다(서버 .env와 compose 기본값을 따른다).
#   FILE_URL_SIGNING_SECRET  업로드 파일 서명 URL용 키. 서버 .env에 이 키가 없을 때만 .env에 추가한다(있으면 서버 값을 유지).
#   DETECT_IMAGE            detect 이미지(예: ghcr.io/somaus/signal-detect:<sha>). http일 때 필요하다.
#
set -euo pipefail

cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

: "${APP_IMAGE:?APP_IMAGE 가 필요합니다}"
: "${GHCR_USER:?GHCR_USER 가 필요합니다}"
: "${GHCR_TOKEN:?GHCR_TOKEN 이 필요합니다}"
RUN_SCHEMA_UPDATE="${RUN_SCHEMA_UPDATE:-false}"

log() { printf '\n▶ %s\n' "$*"; }

# http를 요청했지만 쓸 수 없을 때. 실패한 detect를 내려(restart: unless-stopped라 두면 크래시 루프를 돈다) 2GB 서버의
# CPU와 메모리를 돌려주고, 앱은 llm 방식으로 배포한다. ::warning::은 GitHub Actions 실행 요약에 경고로 드러난다.
fall_back_to_llm() {
    log "$1 — llm 방식으로 배포합니다"
    echo "::warning::탐지 방식 http를 요청했지만 $1. llm 방식으로 배포했습니다."
    docker compose --profile detect rm -sf detect >/dev/null 2>&1 || true
    export DEEPFAKE_DETECTION_MODE=llm
}

# detect 컨테이너가 healthy가 될 때까지 기다린다. 첫 배포는 모델(약 87MB)을 받느라 몇 분 걸릴 수 있다.
detect_healthy() {
    local cid status
    for _ in $(seq 1 "$1"); do
        cid="$(docker compose --profile detect ps -q detect 2>/dev/null || true)"
        status="$([ -n "$cid" ] && docker inspect --format '{{.State.Health.Status}}' "$cid" 2>/dev/null || true)"
        [ "$status" = "healthy" ] && return 0
        sleep 5
    done
    return 1
}

# 헬스체크. prod는 compose가 80:8080으로 매핑하므로 호스트에서는 80포트다.
health() { curl -fsS --retry "$1" --retry-delay 3 --retry-all-errors -o /dev/null http://localhost/health; }

# 롤백 대상으로 현재 떠 있는 컨테이너의 이미지를 기억해 둔다.
PREV_IMAGE=""
if cid="$(docker compose ps -q app 2>/dev/null)" && [ -n "$cid" ]; then
    PREV_IMAGE="$(docker inspect --format '{{.Config.Image}}' "$cid" 2>/dev/null || true)"
fi
log "현재 이미지: ${PREV_IMAGE:-(없음)}"
log "배포할 이미지: $APP_IMAGE"

# 운영 프로필은 FILE_URL_SIGNING_SECRET이 없으면 기동하지 못한다. 서버 .env에 없을 때만 넘겨받은 값을 추가한다.
if [ -n "${FILE_URL_SIGNING_SECRET:-}" ] && ! grep -qE '^FILE_URL_SIGNING_SECRET=' .env 2>/dev/null; then
    log ".env에 FILE_URL_SIGNING_SECRET 추가"
    printf '\nFILE_URL_SIGNING_SECRET=%s\n' "$FILE_URL_SIGNING_SECRET" >> .env
fi

log "GHCR 로그인"
printf '%s' "$GHCR_TOKEN" | docker login ghcr.io -u "$GHCR_USER" --password-stdin
trap 'docker logout ghcr.io >/dev/null 2>&1 || true' EXIT

log "이미지 pull"
docker pull "$APP_IMAGE"
export APP_IMAGE

# 전용 탐지 서비스(detect). 준비되지 않으면 앱만이라도 정상 배포되도록 llm 방식으로 내린다.
# (http 방식에서 detect가 죽어 있으면 탐지 요청이 모두 실패하기 때문이다.)
case "${DEEPFAKE_DETECTION_MODE:-}" in
    http)
        if [ -z "${DETECT_IMAGE:-}" ]; then
            fall_back_to_llm "DETECT_IMAGE가 없습니다"
        else
            log "탐지 서비스 이미지 pull: $DETECT_IMAGE"
            export DETECT_IMAGE
            if docker pull "$DETECT_IMAGE" && docker compose --profile detect up -d detect; then
                log "탐지 서비스 준비 대기 (최대 5분)"
                if detect_healthy 60; then
                    log "탐지 서비스 준비됨 — http 방식으로 배포"
                else
                    docker compose --profile detect logs --tail=40 detect || true
                    fall_back_to_llm "탐지 서비스가 5분 안에 준비되지 않았습니다"
                fi
            else
                fall_back_to_llm "탐지 서비스 이미지를 받거나 띄우지 못했습니다"
            fi
        fi
        ;;
    llm)
        # 쓰지 않는 detect가 2GB 서버의 메모리를 차지하지 않게 내린다.
        docker compose --profile detect rm -sf detect >/dev/null 2>&1 || true
        export DEEPFAKE_DETECTION_MODE=llm
        ;;
    "")
        ;;
    *)
        # 오타 등. 아무것도 바꾸지 않고(서버 설정 유지) 알려 준다.
        echo "::warning::DEEPFAKE_DETECTION_MODE 값 '${DEEPFAKE_DETECTION_MODE}'을 알 수 없어 탐지 방식을 바꾸지 않았습니다. http 또는 llm만 쓸 수 있습니다."
        unset DEEPFAKE_DETECTION_MODE
        ;;
esac

# prod 프로필은 ddl-auto=validate라서, 엔티티에 필드가 추가된 배포는 스키마를 먼저 만들어야 한다.
if [ "$RUN_SCHEMA_UPDATE" = "true" ]; then
    log "스키마 반영 (ddl-auto=update)"
    docker compose up -d mysql
    for _ in $(seq 1 60); do
        mysql_cid="$(docker compose ps -q mysql)"
        [ "$(docker inspect --format '{{.State.Health.Status}}' "$mysql_cid" 2>/dev/null || true)" = "healthy" ] && break
        sleep 3
    done

    schema_log="$(mktemp)"
    docker compose run --rm --name signal-schema-init \
        -e SPRING_JPA_HIBERNATE_DDLAUTO=update app > "$schema_log" 2>&1 &
    init_pid=$!

    schema_ok=false
    for _ in $(seq 1 90); do
        if grep -qa "Started SignalApplication" "$schema_log"; then schema_ok=true; break; fi
        if grep -qa "APPLICATION FAILED TO START" "$schema_log"; then break; fi
        kill -0 "$init_pid" 2>/dev/null || break
        sleep 2
    done

    # 스키마만 만들면 되므로 기동 확인 후 바로 종료한다 (DEPLOY.md의 Ctrl+C 단계에 해당).
    docker rm -f signal-schema-init >/dev/null 2>&1 || true
    kill "$init_pid" 2>/dev/null || true
    wait "$init_pid" 2>/dev/null || true

    if [ "$schema_ok" != "true" ]; then
        log "스키마 반영 실패 — 배포를 중단합니다"
        tail -40 "$schema_log"
        exit 1
    fi
    log "스키마 반영 완료"
fi

log "앱 기동"
docker compose up -d app

log "헬스체크"
if health 40; then
    log "배포 성공: $APP_IMAGE (탐지 방식: ${DEEPFAKE_DETECTION_MODE:-서버 설정})"
else
    log "헬스체크 실패"
    docker compose logs --tail=60 app || true

    if [ -n "$PREV_IMAGE" ] && [ "$PREV_IMAGE" != "$APP_IMAGE" ]; then
        log "이전 이미지로 롤백: $PREV_IMAGE"
        APP_IMAGE="$PREV_IMAGE" docker compose up -d app
        if health 20; then
            log "롤백 완료 — 서비스는 이전 버전으로 정상 동작 중"
        else
            log "롤백 후에도 비정상 — 수동 확인이 필요합니다"
        fi
    else
        log "롤백할 이전 이미지가 없습니다"
    fi
    exit 1
fi

# 디스크 20GB짜리 서버라 오래된 이미지를 정리한다.
docker image prune -f --filter "until=168h" >/dev/null 2>&1 || true
