FROM llm-runner:base

ARG NODE_VERSION=22
ARG CODEX_VERSION=latest
ARG CLAUDE_VERSION=latest
ARG TERRAFORM_VERSION=1.12.0

# Node.js 설치 (nvm)
RUN . "$NVM_DIR/nvm.sh" \
    && nvm install "$NODE_VERSION" \
    && nvm alias default "$NODE_VERSION" \
    && ln -sf "$(dirname "$(dirname "$(nvm which default)")")" "$NVM_DIR/current"
ENV PATH="$NVM_DIR/current/bin:$PATH"

RUN chown -R llmuser:llmuser /usr/local/nvm

# Terraform
RUN ARCH="$(dpkg --print-architecture)" \
    && curl -fsSL -o /tmp/terraform.zip \
       "https://releases.hashicorp.com/terraform/${TERRAFORM_VERSION}/terraform_${TERRAFORM_VERSION}_linux_${ARCH}.zip" \
    && unzip /tmp/terraform.zip -d /usr/local/bin \
    && rm -f /tmp/terraform.zip \
    && terraform version

# Global npm packages
RUN npm install -g @openai/codex@${CODEX_VERSION} @anthropic-ai/claude-code@${CLAUDE_VERSION}
