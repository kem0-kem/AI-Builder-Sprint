# Chat Read State Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 채팅방을 열고 마지막 확인 메시지를 기록하면 새 메시지 표시가 사라지고 서버가 남은 읽지 않은 메시지 수를 반환하도록 한다.

**Architecture:** 기존 UUID 기반 채팅 모델과 공통 API 응답 봉투를 유지하면서 새 `PATCH /chat-rooms/{room_id}/read` 계약을 추가한다. 읽음 위치는 뒤로 이동하지 않으며, 기존 PUT 경로는 호환 별칭으로 유지한다. 현재 정적 데이터인 Android 화면에서는 대화를 여는 즉시 해당 항목의 로컬 `unread` 상태를 해제한다.

**Tech Stack:** FastAPI, SQLAlchemy async, Pydantic v2, pytest/httpx, Android Jetpack Compose/Kotlin

## Global Constraints

- 메시지와 채팅방 식별자는 기존 모델과 동일하게 UUID를 사용한다.
- API 응답은 프로젝트 표준 `{"ok": true, "data": ...}` 봉투를 사용한다.
- 인증된 채팅방 참여자만 읽음 위치를 변경할 수 있다.
- 기존 `PUT /chat-rooms/{room_id}/read-position` 호출은 깨뜨리지 않는다.
- `.codex-remote-attachments/`는 소스 변경에 포함하지 않는다.

---

### Task 1: Backend read-state contract and behavior

**Files:**
- Modify: `backend/app/chat/schemas.py`
- Modify: `backend/app/chat/router.py`
- Test: `backend/tests/chat/test_chat_api.py`

**Interfaces:**
- Consumes: `ChatParticipant.last_read_message_id`, `ChatMessage.id`, `require_participant()`
- Produces: `PATCH /api/v1/chat-rooms/{room_id}/read` accepting `{"lastReadMessageId": UUID}` and returning `data.lastReadMessageId` plus `data.unreadCount`

- [ ] **Step 1: Write failing endpoint tests**

```python
read = await client.patch(
    f"/api/v1/chat-rooms/{room_id}/read",
    headers=alice,
    json={"lastReadMessageId": first_message_id},
)
assert read.json()["data"] == {
    "lastReadMessageId": first_message_id,
    "unreadCount": 1,
}
```

Add focused assertions for latest-message count `0`, foreign-room message rejection, non-participant rejection, and a second request with an older message retaining the newer marker.

- [ ] **Step 2: Run tests and confirm the new PATCH route fails**

Run: `pytest tests/chat/test_chat_api.py -q`
Expected: FAIL because `/read` does not exist.

- [ ] **Step 3: Add the request schema and shared read-state service function**

```python
class ChatRoomReadUpdate(BaseModel):
    model_config = ConfigDict(populate_by_name=True)
    last_read_message_id: UUID = Field(alias="lastReadMessageId")
```

In the router, validate room membership and message ownership, prevent regression by comparing the stored marker's `created_at`, count later messages from other users with SQL `count()`, commit, and return the standard success envelope.

- [ ] **Step 4: Add PATCH and preserve the legacy PUT alias**

```python
@router.patch("/chat-rooms/{room_id}/read")
async def mark_room_read(...):
    return await update_room_read_state(...)
```

Keep the old PUT handler translating `messageId` into the same internal operation and its legacy response field.

- [ ] **Step 5: Run focused backend tests**

Run: `pytest tests/chat/test_chat_api.py -q`
Expected: PASS.

### Task 2: Android unread feedback removal

**Files:**
- Modify: `app/src/main/java/com/apptive/slowtalk/MainActivity.kt`

**Interfaces:**
- Consumes: immutable `Conversation.copy(unread = false)`
- Produces: mutable Compose conversation collections whose selected row loses its unread indicator before navigating to `Screen.Chat`

- [ ] **Step 1: Convert remembered conversation lists to observable mutable lists**

```kotlin
val anonymousConversations = remember { mutableStateListOf(/* existing rows */) }
val groupConversations = remember { mutableStateListOf(/* existing rows */) }
```

- [ ] **Step 2: Clear only the selected row on open**

```kotlin
onOpen = { conversation ->
    val conversations = if (conversation.isGroup) groupConversations else anonymousConversations
    val index = conversations.indexOf(conversation)
    if (index >= 0) conversations[index] = conversation.copy(unread = false)
    screen = Screen.Chat(conversation.title, conversation.isGroup)
}
```

- [ ] **Step 3: Compile the Android app**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

### Task 3: API documentation and full verification

**Files:**
- Modify: `backend/openapi/slowtalk-v1.json`

**Interfaces:**
- Consumes: FastAPI-generated OpenAPI schema
- Produces: checked-in snapshot containing the PATCH route and `ChatRoomReadUpdate`

- [ ] **Step 1: Regenerate OpenAPI snapshot**

Run from `backend`: `python scripts/export_openapi.py openapi/slowtalk-v1.json`
Expected: snapshot includes `/api/v1/chat-rooms/{room_id}/read` with PATCH.

- [ ] **Step 2: Run backend quality gates**

Run from `backend`: `ruff check app tests`
Expected: PASS.

Run from `backend`: `mypy app`
Expected: PASS.

Run from `backend`: `pytest -q`
Expected: PASS, with only documented environment-dependent skips.

- [ ] **Step 3: Inspect the final diff**

Run: `git diff --check` and `git status --short`
Expected: no whitespace errors and only intended source, test, plan, and OpenAPI changes; `.codex-remote-attachments/` remains untracked.
