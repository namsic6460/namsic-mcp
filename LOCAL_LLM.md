# 로컬 LLM 도구

Qwen/llama.cpp의 OpenAI 호환 API를 MCP에서 호출한다. OpenCode나 셸 실행 권한은 필요하지 않다.
메모리 선택과 최종 판정은 호출하는 매니저가 담당한다. 메모리 관리 도구는 아래 별도 절을 참고한다.

## 도구와 흐름

1. `project_init`으로 프로젝트 세션을 얻는다.
2. `local_llm_status`로 서버 상태와 대기열을 확인한다.
3. `local_llm_vision` 또는 `local_llm_triage`를 호출한다.
4. 반환된 `jobId`를 `local_llm_result(sessionId, jobId)`에 전달한다. 2~5초 간격으로 조회한다.
5. `completed`이면 `output`과 `usage`, `queueMs`, `executionMs`를 런 증거에 저장한다.
   `failed`이면 재시도 또는 상위 모델로 전환한다. 실패는 정상 판정이 아니다.

`vision`: `sessionId, imagePaths, task, referenceMemory?`.
화면 이름, 관찰 목록, 미검증 목록을 반환한다. 정밀 좌표 도구를 대체하지 않는다.

`triage`: `sessionId, imagePaths, itemId, expected, collectorResult, collectorNote, referenceMemory?`.
기존 트리아지의 `itemId/status/reason`에 `unverified`를 추가한다.
`suspect`는 추가 검토 대상이며 결함 확정이 아니다. `ok`도 체크리스트 완료를 뜻하지 않는다.
수집 결과가 OK가 아닌데 ok로 답하거나, 미검증이 있는데 ok로 답하면 스키마 검증에서 실패한다.

모든 추론 작업은 단일 워커에서 순서대로 실행한다. 기본 대기열은 8건이다.
가득 차면 MCP 오류를 반환한다. 자동 중복 재시도나 상위 모델 호출은 하지 않는다.
이미지 1~2장을 지원하며 2장은 전/후 순서이다. PNG/JPEG만 가능하다.
이미지는 제출 시 바이트로 고정되므로 대기 중 같은 파일명이 덮어써져도 입력이 바뀌지 않는다.

## 설정

PC마다 달라질 수 있는 값만 환경 변수(또는 저장소 루트 `.env`)로 바꾼다. 나머지는 코드 상수(`src/config.rs`)다.

| 설정 | 기본값 | 변경 방법 |
|---|---|---|
| base-url | http://127.0.0.1:9000 | `LOCAL_LLM_BASE_URL` |
| model | qwen3.8-27b | `LOCAL_LLM_MODEL` |
| image-roots | 빈 목록 | `LOCAL_LLM_IMAGE_ROOTS` (`;` 구분) |
| request-timeout | 3m (실제 HTTP 추론 시간, 대기 시간 제외) | 상수 |
| queue-capacity | 8 | 상수 |
| max-retained-jobs | 100 | 상수 |
| retention | 30m (완료 후) | 상수 |

예를 들어 기존 체크리스트 캡처를 읽으려면 `.env`에 아래처럼 허용 루트를 추가한다:

```dotenv
LOCAL_LLM_IMAGE_ROOTS=C:/Users/user/Desktop/projects/dream-maker/checklist
```

설정 변경 후 서버를 재시작한다. Claude/Codex에서는 추가된 MCP 도구 목록을 새로 불러온다.
기존 체크리스트 스킬 및 클라이언트 권한은 이 변경에서 자동 수정하지 않는다.

## 경계와 운영

- API 주소는 서버 설정으로만 지정하며 loopback HTTP origin만 허용한다. 클라이언트가 임의 URL을 지정할 수 없다.
- 이미지 경로는 MCP 서버 호스트 기준이다. 세션의 screenshotDir 또는 image-roots 안의 실경로만 허용한다.
  공유 image-roots는 해당 MCP 호출자들이 함께 읽을 수 있는 신뢰된 증거 폴더로만 지정한다.
- 파일당 10 MiB / 20메가픽셀, 참고 필드별 16,000자 제한. 원본을 축소하지 않는다.
- 결과는 제출한 프로젝트 sessionId로만 조회한다. 이 ID는 프로젝트 구분 수단이며 사용자 인증을 대신하지 않는다.
- 프롬프트·이미지·모델의 원문 오류는 서버 로그에 남기지 않는다.
- 작업/결과는 메모리에만 보관한다. 완료 후 보존 기한이 지나면 조회할 수 없고, 다음 제출 때 정리한다.
  재시작하면 모두 사라지므로 매니저가 런 폴더에 요청 규칙과 결과를 기록해야 한다.
- 동시 실행 수는 현재 로컬 모델의 단일 슬롯에 맞춰 1로 고정했다.
- 서버 종료 시 실행 중 요청을 중단한다. 추론 제한시간 초과 후 백엔드의 실제 GPU 작업 종료까지는
  백엔드 취소 처리에 달려 있다.
- 정밀 판정/반증/자동 메모리 승격/우선순위 스케줄링은 이 초기 구현에 포함되지 않는다.

## 검증

`cargo test`: 가짜 로컬 HTTP 서버로 요청 형식, 대기열 포화/직렬화, 세션 분리,
허용 경로, 이미지 형식, 응답 검증, 제한시간 및 도구 중복 등록을 검사한다.

실제 Qwen 시험(기본 테스트에서는 제외):

```powershell
cargo test -- --ignored live_local_qwen_round_trip
# 실제 기존 이미지 사용 시: $env:LOCAL_LLM_IMAGE='C:/absolute/path/to/shot.png'
```

## LLM 메모리 관리

MCP 전용 저장소이며 Codex/Claude 개인 메모리와 분리된다. Qwen 자체를 학습시키지 않는다.
매니저가 명시적으로 도구를 호출할 때만 저장/갱신/삭제된다.

| 도구 | 역할 |
|---|---|
| `local_llm_memory_save` | 생성/전체 필드 수정, 후보·확정·보관 상태 변경 |
| `local_llm_memory_search` | 제목·본문·태그·적용 범위의 대소문자 무시 부분 문자열 검색, 페이지 조회 |
| `local_llm_memory_get` | 내용·근거·현재 revision 조회 |
| `local_llm_memory_delete` | 현재 revision 확인 후 영구 삭제 |
| `local_llm_memory_context` | 선택한 확정 메모리를 `referenceMemory` 문자열로 조합 |

권장 흐름:
1. 관찰이나 교정 후보를 `candidate`로 저장한다. 신규 ID는 생략하고 `expectedRevision=0`.
2. 매니저가 증거를 검토하고 `scope`(화면/기기/버전/적용 조건) 및 `evidence`를 보완해
   `confirmed`로 갱신한다. 업데이트에는 현재 revision을 전달한다.
3. 작업마다 search/get으로 관련 내용을 검토하고 context에 선택한 ID 1~10개를 전달한다.
4. 반환된 `referenceMemory`를 기존 vision/triage 도구의 같은 이름 인자에 넣는다.
   반환된 ID/revision 목록은 검수 증거와 함께 저장한다.
5. 오래되었거나 적용하지 않을 내용은 `archived`로 갱신한다. 필요하면 delete로 영구 제거한다.

`confirmed`에는 근거 문자열이 필수이지만 서버가 그 근거의 진위를 검증하지는 않는다.
확정 책임은 매니저에게 있다. 검색 기본값은 confirmed이며 후보 검토는 status=candidate,
전체 관리는 status=all을 사용한다. 검색 결과는 실행 지시가 아닌 참고 데이터로 다룬다.
검색은 의미 기반 벡터 검색이 아니라 단순 부분 문자열 검색이다.
주입 문맥은 16,000자를 넘으면 실패하며 내용을 조용히 자르지 않는다.
기존 `referenceMemory` 직접 입력도 유지되므로 후보 배제는 context 도구의 보장 범위다.

저장 위치: `~/.namsic-mcp/llm-memory` (상수, Java 판과 같은 파일 형식).
프로젝트의 정규화된 이름을 키로 사용하므로 재시작 후 같은 이름으로 project_init을 호출하면 재사용한다.
같은 정규화 이름은 같은 저장소를 공유한다. sessionId는 사용자 인증 경계가 아니다.
임의 메모리 파일 경로를 도구 인자로 받지 않으며 파일 이름은 프로젝트 이름의 SHA-256이다.

프로젝트당 최대 1,000건. 제목 200자, 본문 8,000자, 적용 범위 1,000자, 근거 2,000자,
태그 최대 20개(각 100자). 한 서버 내 동기화와 파일 잠금으로 쓰기를 직렬화하고
임시 파일을 원자적으로 교체한다. 손상된 저장소는 덮어쓰지 않고 오류를 반환한다.
동시 업데이트는 revision 충돌 시 다시 읽고 재판단해야 한다.
자동 만료/변경 이력/삭제 복구는 제공하지 않는다. 보관 상태를 먼저 사용하는 것을 권장한다.
실행 중인 MCP 서버를 재시작하고 클라이언트의 도구 목록을 갱신해야 새 도구가 노출된다.
