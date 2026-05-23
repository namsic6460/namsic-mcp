#!/bin/bash
set -e

# Feature detection: nvm 초기화
if [ -s "/usr/local/nvm/nvm.sh" ]; then
  export NVM_DIR=/usr/local/nvm
  . "$NVM_DIR/nvm.sh"
fi

# Feature detection: SDKMAN 초기화
if [ -s "/usr/local/sdkman/bin/sdkman-init.sh" ]; then
  export SDKMAN_DIR=/usr/local/sdkman
  . "$SDKMAN_DIR/bin/sdkman-init.sh"
fi

# nvm/SDKMAN 존재 시 자식 프로세스(codex 등)에서도 사용 가능하도록 설정
# /etc/profile.d/ → 모든 login shell(bash -lc)에서 /etc/profile 경유로 항상 소싱
# ~/.bashrc → non-login interactive shell 대응
if [ -s "/usr/local/nvm/nvm.sh" ] || [ -s "/usr/local/sdkman/bin/sdkman-init.sh" ]; then
  cat > /etc/profile.d/runtime-init.sh <<'INITBLOCK'
export NVM_DIR=/usr/local/nvm
[ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh"
export SDKMAN_DIR=/usr/local/sdkman
[ -s "$SDKMAN_DIR/bin/sdkman-init.sh" ] && . "$SDKMAN_DIR/bin/sdkman-init.sh"
INITBLOCK
  cp /etc/profile.d/runtime-init.sh "$HOME/.bashrc"
fi

if [ -f /etc/profile.d/runtime-init.sh ]; then
  export BASH_ENV=/etc/profile.d/runtime-init.sh
fi

if [ -s "/usr/local/nvm/nvm.sh" ]; then
  cat > /usr/local/bin/nvm <<'NVMBLOCK'
#!/bin/bash
set -e
export NVM_DIR=/usr/local/nvm
. "$NVM_DIR/nvm.sh"
nvm "$@"
NVMBLOCK
  chmod +x /usr/local/bin/nvm
fi

if [ -s "/usr/local/sdkman/bin/sdkman-init.sh" ]; then
  cat > /usr/local/bin/sdk <<'SDKBLOCK'
#!/bin/bash
set -e
export SDKMAN_DIR=/usr/local/sdkman
. "$SDKMAN_DIR/bin/sdkman-init.sh"
sdk "$@"
SDKBLOCK
  chmod +x /usr/local/bin/sdk
fi

# Gradle 데몬 IPv6 loopback 연결 실패 방지
export GRADLE_OPTS="${GRADLE_OPTS:+$GRADLE_OPTS }-Djava.net.preferIPv4Stack=true"

# global npm 모듈을 js_repl에서 찾을 수 있도록 NODE_PATH 설정
GLOBAL_NODE_MODULES="$(npm root -g 2>/dev/null || true)"
if [ -n "$GLOBAL_NODE_MODULES" ] && [ -d "$GLOBAL_NODE_MODULES" ]; then
  export NODE_PATH="${GLOBAL_NODE_MODULES}${NODE_PATH:+:$NODE_PATH}"
fi

# CDP_ENDPOINT_URL의 localhost를 host.docker.internal로 치환
# (Docker 컨테이너에서 호스트의 Chrome DevTools에 접근하기 위함)
if [ -n "$CDP_ENDPOINT_URL" ]; then
  CDP_ENDPOINT_URL="$(echo "$CDP_ENDPOINT_URL" | sed 's|://localhost:|://host.docker.internal:|g' | sed 's|://127\.0\.0\.1:|://host.docker.internal:|g')"
  export CDP_ENDPOINT_URL
fi

LLMUSER_HOME=$(getent passwd llmuser | cut -d: -f6)

# MCP server configuration for Codex CLI (root — codex 인증 파일이 /root에 마운트됨)
mkdir -p "$HOME/.codex"
cat > "$HOME/.codex/config.toml" <<EOF
[features]
js_repl = true

[mcp_servers.ai-helper]
transport = "sse"
url = "http://host.docker.internal:8070/mcp"
tool_timeout_sec = 7200
EOF

# MCP server configuration for Claude CLI (root)
# Claude Code CLI 2.1+ 는 ~/.claude/settings.json 의 mcpServers 를 무시하고
# ~/.claude.json 의 mcpServers 블록만 인식함. settings.json 은 env/permissions 만 유지.
mkdir -p "$HOME/.claude"
cat > "$HOME/.claude/settings.json" <<EOF
{
  "env": {
    "CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS": "1",
    "MCP_TIMEOUT": "7200000"
  },
  "permissions": {
    "allow": ["mcp__ai-helper"],
    "deny": []
  }
}
EOF

cat > "$HOME/.claude.json" <<EOF
{
  "mcpServers": {
    "ai-helper": {
      "type": "http",
      "url": "http://host.docker.internal:8070/mcp"
    }
  }
}
EOF

# llmuser 홈에도 동일한 설정 복사
cp -r "$HOME/.codex" "$LLMUSER_HOME/.codex"
cp -r "$HOME/.claude" "$LLMUSER_HOME/.claude"
cp "$HOME/.claude.json" "$LLMUSER_HOME/.claude.json"

# 마운트된 인증 파일을 llmuser 홈으로 복사 (읽기전용 마운트라 심볼릭 링크 불가)
if [ -f /root/.codex/auth.json ]; then
  cp /root/.codex/auth.json "$LLMUSER_HOME/.codex/auth.json"
fi
if [ -f /root/.claude/.credentials.json ]; then
  cp /root/.claude/.credentials.json "$LLMUSER_HOME/.claude/.credentials.json"
fi

chown -R llmuser:llmuser "$LLMUSER_HOME"

# 워크스페이스 및 캐시 디렉토리 권한 부여
chown -R llmuser:llmuser /workspace 2>/dev/null || true
chown -R llmuser:llmuser /cache 2>/dev/null || true

# llmuser에도 git safe.directory 설정
gosu llmuser git config --global --add safe.directory '*'
gosu llmuser git config --global user.name "AI Helper Bot"
gosu llmuser git config --global user.email ${GOOGLE_EMAIL:-"ai-helper-bot@users.noreply.github.com"}
gosu llmuser git config --global core.autocrlf input

# llmuser에도 .bashrc 설정 (nvm/SDKMAN — /etc/profile.d/는 전역이라 별도 복사 불필요)
if [ -s "/usr/local/nvm/nvm.sh" ] || [ -s "/usr/local/sdkman/bin/sdkman-init.sh" ]; then
  cp "$HOME/.bashrc" "$LLMUSER_HOME/.bashrc" 2>/dev/null || true
  chown llmuser:llmuser "$LLMUSER_HOME/.bashrc"
fi

# npm 인증 설정 — LLM CLI가 자식 프로세스에 env var를 전파하지 않을 수 있으므로 파일로 확정
# (a) ~/.npmrc에 토큰 값 직접 기록 (Node 22+: placeholder 치환 실패해도 user-level auth로 동작)
# (b) /etc/profile.d/ + ~/.bashrc에 export (login/non-login 양쪽 대응)
if [ -n "$NPM_TOKEN" ] && [ -n "$NPM_AUTH_TOKEN" ]; then
  NPM_REGISTRY="${NPM_REPOSITORY:-https://nexus2.help-me.kr/repository/npm}"
  REGISTRY_HOST_PATH="$(echo "$NPM_REGISTRY" | sed 's|^https\?:||')"

  cat > "$LLMUSER_HOME/.npmrc" <<NPMRC
registry=${NPM_REGISTRY}
always-auth=true
${REGISTRY_HOST_PATH}/:_auth=${NPM_TOKEN}
${REGISTRY_HOST_PATH}/npm/:_authToken=${NPM_AUTH_TOKEN}
${REGISTRY_HOST_PATH}/npm-private/:_authToken=${NPM_AUTH_TOKEN}
NPMRC
  chown llmuser:llmuser "$LLMUSER_HOME/.npmrc"

  NPM_EXPORTS=$(printf "export NPM_TOKEN='%s'\nexport npm_token='%s'\n" "$NPM_TOKEN" "$NPM_TOKEN")
  echo "$NPM_EXPORTS" >> /etc/profile.d/npm-token.sh
  echo "$NPM_EXPORTS" >> "$LLMUSER_HOME/.bashrc"
  chown llmuser:llmuser "$LLMUSER_HOME/.bashrc"
fi

# Docker Desktop 환경: localhost가 호스트 머신을 가리키도록 /etc/hosts 수정
# (컨테이너 내 127.0.0.1 → host-gateway IPv4 로 교체)
#
# getent hosts 는 시스템 설정에 따라 IPv6 주소를 먼저 반환할 수 있고, 그 경우
# localhost 가 호스트의 IPv6 게이트웨이로 매핑되어 IPv6 라우트가 없는 환경에서
# "Network is unreachable" 가 발생함. 반드시 ahostsv4 로 IPv4 만 뽑아야 한다.
if HOSTIP=$(getent ahostsv4 host.docker.internal 2>/dev/null | awk 'NR==1 {print $1}') && [ -n "$HOSTIP" ]; then
  cp /etc/hosts /tmp/hosts.bak
  sed -e "s/^127\.0\.0\.1\(\s\)/$HOSTIP\1/" \
      -e "s/^::1\(\s\+\)localhost/$HOSTIP\1localhost/" \
      /tmp/hosts.bak > /etc/hosts 2>/dev/null || true
fi

exec gosu llmuser "$@"
