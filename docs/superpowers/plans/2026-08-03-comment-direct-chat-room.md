# Comment Direct Chat Room Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an authenticated comment action that creates or reuses a direct chat room with the comment author and opens that room in Android.

**Architecture:** The backend owns idempotency through the existing unique `ChatRoom.direct_key`, with a shared chat service used by both letter matching and the new comment endpoint. Android adds the endpoint to `ChatApiService`, converts the response in `ChatApi`, and navigates only after a successful server response.

**Tech Stack:** FastAPI, SQLAlchemy async, Pydantic, pytest/httpx, Android Kotlin, Retrofit, kotlinx.serialization, Jetpack Compose, MockWebServer, JUnit 4.

## Global Constraints

- The endpoint is exactly `POST /api/v1/comments/{commentId}/chat-room` and has no request body.
- The success envelope contains `data.id` as a UUID string, `data.type` equal to `DIRECT`, and nullable `data.name` equal to `null` for a new direct room.
- The caller must be authenticated by the existing Bearer-token dependency.
- A deleted or missing comment returns `404 RESOURCE_NOT_FOUND`.
- A caller cannot create a room from their own comment; return `400 VALIDATION_ERROR`.
- The same unordered pair of user UUIDs always resolves to one `ChatRoom.direct_key` and one direct room.
- Android navigates only after success and remains on the feed detail screen on failure.
- Do not change message REST/WebSocket contracts, meeting rooms, notification policy, or production infrastructure.
- Do not stage `.android-sdk/`, `.codex-remote-attachments/`, or `.worktrees/`.

## File Map

- `backend/app/chat/service.py`: shared direct-room key and get-or-create transaction helper.
- `backend/app/letters/service.py`: consumes the shared helper instead of maintaining a second direct-room implementation.
- `backend/app/chat/schemas.py`: typed direct-room success response schema.
- `backend/app/feeds/router.py`: comment-scoped HTTP endpoint and validation.
- `backend/tests/feeds/test_comment_chat_room.py`: endpoint behavior, room reuse, participants, and error cases.
- `backend/tests/contract/test_openapi.py`: OpenAPI path and typed success envelope assertion.
- `backend/openapi/slowtalk-v1.json`: regenerated API snapshot.
- `app/src/main/java/com/apptive/slowtalk/data/remote/ChatApiService.kt`: Retrofit endpoint and response DTO reuse.
- `app/src/main/java/com/apptive/slowtalk/ChatApi.kt`: comment-to-room adapter.
- `app/src/main/java/com/apptive/slowtalk/FeedScreens.kt`: non-owner chat action, loading/error handling, and injected request callback.
- `app/src/main/java/com/apptive/slowtalk/MainActivity.kt`: successful room navigation.
- `app/src/test/java/com/apptive/slowtalk/ChatApiContractTest.kt`: exact HTTP path/method and envelope decoding.
- `app/src/test/java/com/apptive/slowtalk/CommentChatNavigationTest.kt`: success-only navigation policy.

---

### Task 1: Shared Backend Direct-Room Service

**Files:**
- Modify: `backend/app/chat/service.py`
- Modify: `backend/app/letters/service.py`
- Test: `backend/tests/chat/test_direct_room_service.py`

**Interfaces:**
- Consumes: `Session`, `ChatRoom`, `ChatParticipant`, two distinct user UUIDs.
- Produces: `direct_key(first: UUID, second: UUID) -> str` and `get_or_create_direct_room(session: Session, first: UUID, second: UUID) -> ChatRoom`.

- [ ] **Step 1: Write failing service tests**

Create `backend/tests/chat/test_direct_room_service.py` with users inserted through the existing auth test helpers, then assert:

```python
assert direct_key(alice_id, bob_id) == direct_key(bob_id, alice_id)

first = await get_or_create_direct_room(session, alice_id, bob_id)
second = await get_or_create_direct_room(session, bob_id, alice_id)
assert second.id == first.id

participants = (
    await session.execute(
        select(ChatParticipant).where(ChatParticipant.room_id == first.id)
    )
).scalars().all()
assert {item.user_id for item in participants} == {alice_id, bob_id}
```

- [ ] **Step 2: Run the service test and confirm RED**

Run:

```powershell
Set-Location backend
python -m pytest tests/chat/test_direct_room_service.py -q
```

Expected: collection or import failure because `get_or_create_direct_room` is not defined in `app.chat.service`.

- [ ] **Step 3: Implement the shared helper**

Move `direct_key` from `app.letters.service` into `app.chat.service` and add:

```python
def direct_key(first: UUID, second: UUID) -> str:
    return ":".join(sorted((str(first), str(second))))


async def get_or_create_direct_room(
    session: Session,
    first: UUID,
    second: UUID,
) -> ChatRoom:
    key = direct_key(first, second)
    room = await session.scalar(select(ChatRoom).where(ChatRoom.direct_key == key))
    if room is not None:
        participant_ids = set(
            (
                await session.execute(
                    select(ChatParticipant.user_id).where(
                        ChatParticipant.room_id == room.id
                    )
                )
            ).scalars()
        )
        if participant_ids != {first, second}:
            raise ApiError(
                "RESOURCE_CONFLICT",
                "채팅방 참가자 정보가 올바르지 않습니다.",
                409,
            )
        return room
    room = ChatRoom(type="DIRECT", name=None, direct_key=key)
    session.add(room)
    await session.flush()
    session.add_all(
        [
            ChatParticipant(room_id=room.id, user_id=first, alias="익명의 이웃 01"),
            ChatParticipant(room_id=room.id, user_id=second, alias="익명의 이웃 02"),
        ]
    )
    return room
```

Keep transaction commit ownership with the caller. Preserve the database unique constraint on `direct_key`.

- [ ] **Step 4: Update letter matching to consume the helper**

Replace the local direct-room key/query/create block in `backend/app/letters/service.py` with:

```python
room = await get_or_create_direct_room(self._session, owner_id, recipient.id)
```

Retain the existing letter message, mailbox, outbox, and final commit behavior.

- [ ] **Step 5: Run focused service and letter tests**

Run:

```powershell
python -m pytest tests/chat/test_direct_room_service.py tests/letters -q
```

Expected: all tests pass and repeated letter matching still reuses a direct room.

- [ ] **Step 6: Commit the shared service**

```powershell
git add backend/app/chat/service.py backend/app/letters/service.py backend/tests/chat/test_direct_room_service.py
git commit -m "refactor: share direct chat room creation"
```

---

### Task 2: Comment-to-Chat Backend Endpoint

**Files:**
- Modify: `backend/app/chat/schemas.py`
- Modify: `backend/app/feeds/router.py`
- Create: `backend/tests/feeds/test_comment_chat_room.py`
- Modify: `backend/tests/contract/test_openapi.py`
- Modify: `backend/openapi/slowtalk-v1.json`

**Interfaces:**
- Consumes: `get_or_create_direct_room(session, caller_id, comment.author_id)` from Task 1.
- Produces: `POST /api/v1/comments/{comment_id}/chat-room` with typed `ChatRoomSuccessResponse`.

- [ ] **Step 1: Write failing endpoint tests**

Create two users and visible comments through HTTP. Cover these exact cases in `test_comment_chat_room.py`:

```python
created = await client.post(
    f"/api/v1/comments/{bob_comment_id}/chat-room",
    headers=alice_headers,
)
assert created.status_code == 200
assert created.json()["data"] == {
    "id": created.json()["data"]["id"],
    "type": "DIRECT",
    "name": None,
}

replayed = await client.post(
    f"/api/v1/comments/{bob_comment_id}/chat-room",
    headers=alice_headers,
)
assert replayed.json()["data"]["id"] == created.json()["data"]["id"]
```

Also assert the same ID for a reverse-direction user-pair request, `400 VALIDATION_ERROR` for the caller's own comment, `404 RESOURCE_NOT_FOUND` after comment deletion, `404` for an unknown UUID, and `401` without Authorization.

- [ ] **Step 2: Run endpoint tests and confirm RED**

Run:

```powershell
python -m pytest tests/feeds/test_comment_chat_room.py -q
```

Expected: responses are `404` because the route does not exist.

- [ ] **Step 3: Add typed response schemas**

Add to `backend/app/chat/schemas.py`:

```python
from typing import Any, Literal


class ChatRoomView(BaseModel):
    id: UUID
    type: Literal["DIRECT"]
    name: None = None


class ChatRoomSuccessResponse(BaseModel):
    ok: Literal[True] = True
    data: ChatRoomView
    error: None = None
    meta: dict[str, Any] | None = None
```

- [ ] **Step 4: Implement validation and route**

Add the route to `backend/app/feeds/router.py` so the public path matches the specification:

```python
@router.post(
    "/comments/{comment_id}/chat-room",
    response_model=ChatRoomSuccessResponse,
)
async def create_comment_chat_room(
    comment_id: UUID,
    user_id: CurrentUserId,
    session: Session,
) -> dict[str, object]:
    comment = await require_comment(session, comment_id)
    if comment.author_id == user_id:
        raise ApiError("VALIDATION_ERROR", "자기 댓글에서는 채팅을 시작할 수 없습니다.", 400)
    room = await get_or_create_direct_room(session, user_id, comment.author_id)
    await session.commit()
    return success({"id": str(room.id), "type": room.type, "name": room.name})
```

- [ ] **Step 5: Add OpenAPI contract assertion**

Extend `backend/tests/contract/test_openapi.py`:

```python
operation = document["paths"]["/api/v1/comments/{comment_id}/chat-room"]["post"]
schema = operation["responses"]["200"]["content"]["application/json"]["schema"]
assert "$ref" in schema
assert "requestBody" not in operation
```

Resolve the referenced component and assert that `data` references a schema containing `id`, `type`, and `name`.

- [ ] **Step 6: Run backend endpoint, contract, and static checks**

Run:

```powershell
python -m pytest tests/feeds/test_comment_chat_room.py tests/contract/test_openapi.py -q
python -m ruff check app tests
python -m mypy app
python scripts/export_openapi.py openapi/slowtalk-v1.json
python -m pytest tests/contract/test_openapi.py -q
```

Expected: tests and static checks pass, and the exported snapshot includes the new path.

- [ ] **Step 7: Commit the backend endpoint**

```powershell
git add backend/app/chat/schemas.py backend/app/feeds/router.py backend/tests/feeds/test_comment_chat_room.py backend/tests/contract/test_openapi.py backend/openapi/slowtalk-v1.json
git commit -m "feat: create direct chat rooms from comments"
```

---

### Task 3: Android API Contract and Adapter

**Files:**
- Modify: `app/src/main/java/com/apptive/slowtalk/data/remote/ChatApiService.kt`
- Modify: `app/src/main/java/com/apptive/slowtalk/ChatApi.kt`
- Create: `app/src/test/java/com/apptive/slowtalk/ChatApiContractTest.kt`

**Interfaces:**
- Consumes: `ApiEnvelope<ChatRoomInfoDto>` from the new backend endpoint.
- Produces: `ChatApi.openFromComment(commentId: String): Result<ChatRoomInfo>`.

- [ ] **Step 1: Write the failing Retrofit contract test**

Use `MockWebServer` and the same Retrofit setup as `FeedApiContractTest`:

```kotlin
server.enqueue(
    jsonResponse(
        """{"ok":true,"data":{"id":"$ROOM_ID","type":"DIRECT","name":null},"error":null,"meta":null}"""
    )
)

val result = api.createFromComment(COMMENT_ID)

assertEquals(ROOM_ID, result.data?.id)
server.takeRequest().let {
    assertEquals("POST", it.method)
    assertEquals("/api/v1/comments/$COMMENT_ID/chat-room", it.path)
    assertEquals(0L, it.bodySize)
}
```

- [ ] **Step 2: Run the contract test and confirm RED**

Run:

```powershell
$env:ANDROID_HOME=(Resolve-Path '.android-sdk').Path
.\gradlew.bat testDebugUnitTest --tests "*ChatApiContractTest*" --rerun-tasks
```

Expected: Kotlin compilation fails because `createFromComment` does not exist.

- [ ] **Step 3: Add the Retrofit method and adapter**

Add to `ChatApiService`:

```kotlin
@POST("comments/{commentId}/chat-room")
suspend fun createFromComment(
    @Path("commentId") commentId: String,
): ApiEnvelope<ChatRoomInfoDto>
```

Add to `ChatApi`:

```kotlin
suspend fun openFromComment(commentId: String): Result<ChatRoomInfo> = runCatching {
    apiData { RetrofitClient.chatApi.createFromComment(commentId) }.let {
        ChatRoomInfo(
            id = it.id,
            isGroup = it.type == "GROUP",
            name = it.name,
            participantCount = null,
        )
    }
}
```

- [ ] **Step 4: Run the targeted Android contract test**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "*ChatApiContractTest*" --rerun-tasks
```

Expected: the request is bodyless `POST`, path and UUID decode assertions pass.

- [ ] **Step 5: Commit the Android transport layer**

```powershell
git add app/src/main/java/com/apptive/slowtalk/data/remote/ChatApiService.kt app/src/main/java/com/apptive/slowtalk/ChatApi.kt app/src/test/java/com/apptive/slowtalk/ChatApiContractTest.kt
git commit -m "feat: add comment chat room Android API"
```

---

### Task 4: Comment Action and Success-Only Navigation

**Files:**
- Modify: `app/src/main/java/com/apptive/slowtalk/FeedScreens.kt`
- Modify: `app/src/main/java/com/apptive/slowtalk/MainActivity.kt`
- Create: `app/src/test/java/com/apptive/slowtalk/CommentChatNavigationTest.kt`

**Interfaces:**
- Consumes: `openCommentChat: suspend (String) -> Result<ChatRoomInfo>`.
- Produces: `onCommentChatOpened: (ChatRoomInfo) -> Unit`, mapped by `MainActivity` to `Screen.Chat`.

- [ ] **Step 1: Write the navigation policy test**

Extract a pure policy helper in `MainActivity.kt` and test it before implementation:

```kotlin
assertEquals(
    Screen.Chat("익명의 이웃", isGroup = false, chatRoomId = ROOM_ID),
    screenForOpenedCommentChat(ChatRoomInfo(ROOM_ID, false, null, null)),
)
```

The request failure path is tested by invoking a small suspend action helper with `Result.failure` and asserting that the navigation callback count remains zero while the error callback count becomes one.

- [ ] **Step 2: Run the navigation test and confirm RED**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "*CommentChatNavigationTest*" --rerun-tasks
```

Expected: Kotlin compilation fails because the policy/action helpers do not exist.

- [ ] **Step 3: Add injected callbacks to the feed detail screen**

Extend `FeedDetailScreen` with:

```kotlin
openCommentChat: suspend (String) -> Result<ChatRoomInfo>,
onCommentChatOpened: (ChatRoomInfo) -> Unit,
```

Pass an `onChat` action only for `!comment.isMine && comment.id != null`. While a request is active, disable repeated taps for that comment ID. On success call `onCommentChatOpened`; on failure keep the screen and display a Toast stating that the chat room could not be opened and can be retried.

- [ ] **Step 4: Add the visible comment action**

In `CommentCard`, add `onChat: (() -> Unit)?`. For non-owner comments, display a `채팅하기` dropdown item or icon action that calls `onChat`. Do not render it for the current user's own comment.

- [ ] **Step 5: Wire MainActivity navigation**

Pass:

```kotlin
openCommentChat = ChatApi::openFromComment,
onCommentChatOpened = { room ->
    screen = screenForOpenedCommentChat(room)
},
```

Implement:

```kotlin
internal fun screenForOpenedCommentChat(room: ChatRoomInfo): Screen.Chat = Screen.Chat(
    title = room.name ?: "익명의 이웃",
    isGroup = room.isGroup,
    chatRoomId = room.id,
)
```

- [ ] **Step 6: Run targeted and complete Android verification**

Run sequentially:

```powershell
.\gradlew.bat testDebugUnitTest --tests "*ChatApiContractTest*" --tests "*CommentChatNavigationTest*" --rerun-tasks --no-daemon
.\gradlew.bat testDebugUnitTest --rerun-tasks --no-daemon
.\gradlew.bat assembleDebug --rerun-tasks --no-daemon
```

Expected: targeted and full tests pass; `app/build/outputs/apk/debug/app-debug.apk` is produced.

- [ ] **Step 7: Run complete backend verification**

Run:

```powershell
Set-Location backend
python -m ruff check app tests
python -m mypy app
python -m pytest -q
```

Expected: all backend checks pass with only existing intentional skips.

- [ ] **Step 8: Perform local API smoke test**

Against the running local backend, create Alice and Bob, have Bob comment on Alice's feed, call the endpoint as Alice twice, and assert both responses contain the same room UUID. Fetch `/chat-rooms/{roomId}` as both users and require `200`.

- [ ] **Step 9: Commit the UI integration**

```powershell
git add app/src/main/java/com/apptive/slowtalk/FeedScreens.kt app/src/main/java/com/apptive/slowtalk/MainActivity.kt app/src/test/java/com/apptive/slowtalk/CommentChatNavigationTest.kt
git commit -m "feat: open direct chats from feed comments"
```

---

## Completion Gate

- The new backend route returns the exact envelope from the approved design.
- Repeated and reverse-direction requests reuse one room UUID.
- The room contains exactly the two expected participants.
- Own, missing, deleted, and unauthenticated comment cases return the documented errors.
- Android sends a bodyless authenticated POST and decodes the room UUID.
- Only non-owner comments expose the chat action.
- Navigation occurs only after success; failure remains retryable on the feed detail screen.
- Backend full checks, Android full unit tests, and `assembleDebug` all pass.
- Only intended source, test, OpenAPI, spec, and plan files are committed.
