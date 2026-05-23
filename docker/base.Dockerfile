FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    git curl ca-certificates unzip zip \
    python3 python3-pip python3-venv \
    ripgrep procps file bash bubblewrap gosu \
    && ln -sf /usr/bin/python3 /usr/bin/python \
    && rm -f /usr/lib/python*/EXTERNALLY-MANAGED \
    && rm -rf /var/lib/apt/lists/*

# nvm 설치
ENV NVM_DIR=/usr/local/nvm
RUN mkdir -p "$NVM_DIR" \
    && curl -fsSL https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.3/install.sh | bash

# sdkman 설치
ENV SDKMAN_DIR=/usr/local/sdkman
RUN curl -fsSL https://get.sdkman.io | bash

RUN useradd -m -s /bin/bash llmuser \
    && ln -sf /usr/local/nvm /home/llmuser/.nvm \
    && ln -sf /usr/local/sdkman /home/llmuser/.sdkman

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN sed -i 's/\r$//' /usr/local/bin/entrypoint.sh && chmod +x /usr/local/bin/entrypoint.sh
ENTRYPOINT ["entrypoint.sh"]

WORKDIR /workspace
