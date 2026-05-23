FROM llm-runner:base

ARG NODE_VERSION=22
ARG JAVA_VERSION=""
ARG JDK_DIST=temurin
ARG CODEX_VERSION=latest
ARG CLAUDE_VERSION=latest
ARG PLAYWRIGHT_VERSION=1.58.2

# Node.js 설치 (nvm)
RUN . "$NVM_DIR/nvm.sh" \
    && nvm install "$NODE_VERSION" \
    && nvm alias default "$NODE_VERSION" \
    && ln -sf "$(dirname "$(dirname "$(nvm which default)")")" "$NVM_DIR/current"
ENV PATH="$NVM_DIR/current/bin:$PATH"

# Java 설치 (sdkman, 조건부)
COPY install-sdkman-java.sh /tmp/install-sdkman-java.sh
RUN sed -i 's/\r$//' /tmp/install-sdkman-java.sh && chmod +x /tmp/install-sdkman-java.sh \
    && if [ -n "$JAVA_VERSION" ]; then \
         /tmp/install-sdkman-java.sh "$JAVA_VERSION" "$JDK_DIST"; \
       fi \
    && rm -f /tmp/install-sdkman-java.sh

RUN chown -R llmuser:llmuser /usr/local/nvm /usr/local/sdkman

# Global npm packages
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
RUN npm install -g @openai/codex@${CODEX_VERSION} @anthropic-ai/claude-code@${CLAUDE_VERSION} playwright@${PLAYWRIGHT_VERSION} \
    && npx playwright@${PLAYWRIGHT_VERSION} install --with-deps chromium
