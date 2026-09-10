## 작업 규칙

### git/gh 쓰기 작업은 명시적 지시가 있을 때만 — 커밋은 `/ide-commit` 로만
`git commit`, `git push`, `git merge`, `git rebase`, `git reset`, `git checkout <branch>`, `gh pr create`, `gh pr merge` 등 저장소 상태를 바꾸는 명령은 **사용자가 명시적으로 지시했을 때만** 실행합니다. 코드 변경 후 선제적으로 커밋·푸시하지 마세요. 변경 내용을 요약 보고한 뒤 지시를 기다립니다. 승인은 해당 호출 1회 범위에 한정됩니다 — 다음에 또 해도 된다는 뜻이 아닙니다. `git status`/`git diff`/`git log` 같은 읽기 전용 조회는 자유롭게 사용해도 됩니다.

**커밋은 반드시 `/ide-commit` 스킬을 통해서만 수행합니다.** IDE 인스펙션(`mcp__idea__lint_files`) 을 커밋 전 게이트로 강제(변경 파일 분석 → 경고 분류·수정 → 재분석 반복 → 커밋)하기 위함입니다. 사용자가 "커밋해줘"라고 지시하더라도 shell 에서 `git commit` 을 직접 실행하지 말고 `/ide-commit` 으로 라우팅하세요. push 는 이 스킬 범위 밖이며 별도 명시 지시가 있을 때만 실행합니다. **이 스킬은 IntelliJ IDEA 가 이 프로젝트를 열고 인덱싱을 마친 상태를 전제**합니다 — IDE 가 닫혀 있으면 분석 게이트를 통과할 수 없으므로 커밋하지 말고 IDE 실행을 요청하세요.
⚠️ **`mcp__idea__*` 호출 시 `projectPath` 를 반드시 명시**하세요 — IntelliJ 에 dream-maker 등 다른 프로젝트가 동시에 열려 있어, 생략하면 엉뚱한 프로젝트가 분석됩니다. 이 저장소는 `C:\Users\user\Desktop\projects\namsic-mcp` 이고 VCS 루트가 프로젝트 루트 하나뿐(단일 리포)이라, dream-maker 처럼 저장소별로 나눠 커밋할 필요가 없습니다.

### Java FQCN 금지 — 반드시 import 후 simple name
`new java.util.ArrayList<>()`, `java.util.List<String>` 같은 완전한정명(FQCN) 직접 사용을 금지합니다. 파일 상단에 `import` 선언 후 `new ArrayList<>()`, `List<String>` 형태의 simple name 으로 씁니다. 같은 파일 안에서 동명의 두 클래스를 동시에 써야 하는 Java 언어 제약 상황에서만 한쪽을 FQCN 으로 둘 수 있습니다. 계획서·예시 스니펫에도 동일 규칙을 적용합니다.

### Java `this` 키워드 의무 사용
인스턴스 필드·메서드 참조 시 항상 `this.` 접두사를 붙입니다 (예: `name` → `this.name`, `calculate()` → `this.calculate()`). static 멤버 접근에는 붙이지 않습니다. 생성자·setter 의 파라미터-필드 이름 충돌 해소뿐 아니라 **모든 인스턴스 멤버 접근**에 적용되는 규칙입니다. 기존 파일을 수정할 때 주변 코드가 `this.` 를 생략했더라도 새로 쓰거나 고치는 라인은 규칙을 따릅니다.
