## 작업 규칙

### git/gh 쓰기 작업은 명시적 지시가 있을 때만 — 커밋은 `/ide-commit` 로만
`git commit`, `git push`, `git merge`, `git rebase`, `git reset`, `git checkout <branch>`, `gh pr create`, `gh pr merge` 등 저장소 상태를 바꾸는 명령은 **사용자가 명시적으로 지시했을 때만** 실행합니다. 코드 변경 후 선제적으로 커밋·푸시하지 마세요. 변경 내용을 요약 보고한 뒤 지시를 기다립니다. 승인은 해당 호출 1회 범위에 한정됩니다 — 다음에 또 해도 된다는 뜻이 아닙니다. `git status`/`git diff`/`git log` 같은 읽기 전용 조회는 자유롭게 사용해도 됩니다.

**커밋은 반드시 `/ide-commit` 스킬을 통해서만 수행합니다.** 사용자가 "커밋해줘"라고 지시하더라도 shell 에서 `git commit` 을 직접 실행하지 말고 `/ide-commit` 으로 라우팅하세요. push 는 별도 명시 지시가 있을 때만 실행합니다.

**이 프로젝트의 커밋 전 게이트 (둘 다 적용):**
1. **cargo 게이트 (필수)** — 아래가 모두 통과해야 커밋합니다. 실패하면 고치고 다시 돌립니다.
   - `cargo fmt --check`
   - `cargo clippy --all-targets -- -D warnings`
   - `cargo test`
2. **IDE 인스펙션 (IDE 가 열려 있을 때)** — IntelliJ IDEA 가 이 프로젝트를 열고 인덱싱을 마친 상태면 `/ide-commit` 절차대로 `mcp__idea__lint_files` 분석 → 경고 처리 → 재분석을 반복합니다. IDE 가 닫혀 있으면 cargo 게이트만으로 커밋하되 보고에 "IDE 인스펙션 생략"을 적습니다.

⚠️ **`mcp__idea__*` 호출 시 `projectPath` 를 반드시 명시**하세요 — IntelliJ 에 dream-maker 등 다른 프로젝트가 동시에 열려 있어, 생략하면 엉뚱한 프로젝트가 분석됩니다. 이 저장소는 `C:\Users\user\Desktop\projects\namsic-mcp` 이고 VCS 루트가 프로젝트 루트 하나뿐(단일 리포)입니다.

### Rust 코드 규칙
- `cargo fmt` 기본 스타일을 따르고 clippy 경고를 남기지 않습니다(`#[allow]` 는 사유 주석과 함께 가장 좁은 범위에만).
- `unwrap()`/`expect()` 는 테스트 코드와 깨질 수 없는 불변식(사유 주석)에만 씁니다. 도구 경로의 실패는 MCP 오류 응답으로 돌려줍니다.
- 경로는 `use` 로 가져와 짧은 이름으로 씁니다(같은 이름이 충돌할 때만 경로 한정).
- **MCP 도구의 이름·설명·파라미터·응답 문자열은 클라이언트 계약입니다.** 바꿀 때는 의도적으로 바꾸고, 관련 테스트를 함께 갱신합니다.

### 설정
PC·사용자마다 달라질 수 있는 값만 `.env`(선택, `.env.example` 참고)로 받고, 나머지는 `src/config.rs` 상수로 둡니다. 새 설정이 정말 환경마다 달라지는 값이 아니면 상수로 추가합니다.
