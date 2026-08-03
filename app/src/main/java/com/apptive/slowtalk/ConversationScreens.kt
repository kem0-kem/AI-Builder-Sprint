package com.apptive.slowtalk

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ConversationListScreen(
    loadRooms: suspend () -> Result<List<Conversation>>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    onOpen: (Conversation) -> Unit,
    onCreateGroup: () -> Unit,
    onProfile: () -> Unit,
    onTab: (MainTab) -> Unit,
    showBottomBar: Boolean = true
) {
    var rooms by remember { mutableStateOf(emptyList<Conversation>()) }
    var isLoading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun refreshRooms() {
        isLoading = true
        loadRooms().fold(
            onSuccess = {
                rooms = it
                loadFailed = false
            },
            onFailure = {
                rooms = emptyList()
                loadFailed = true
            }
        )
        isLoading = false
    }

    LaunchedEffect(Unit) { refreshRooms() }
    val shown = rooms.filter { it.isGroup == (selectedIndex == 1) }
    PaperBackground {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                if (showBottomBar) {
                    AppBottomBar(MainTab.CONVERSATIONS, onTab)
                }
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp)
            ) {
                item {
                    PageHeader("내 대화", onProfile = onProfile)
                    Row(
                        Modifier.padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        listOf("익명 대화", "모임 대화").forEachIndexed { index, label ->
                            FilterChip(
                                selected = selectedIndex == index,
                                onClick = { onSelectedIndexChange(index) },
                                label = { Text(label, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = PurpleSoft,
                                    selectedLabelColor = Purple
                                )
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (selectedIndex == 1) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 8.dp)
                                .clickable(onClick = onCreateGroup),
                            shape = RoundedCornerShape(15.dp),
                            colors = CardDefaults.cardColors(BlockSurface),
                            elevation = CardDefaults.cardElevation(2.dp)
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Surface(Modifier.size(46.dp), shape = CircleShape, color = PurpleSoft) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Outlined.GroupAdd, null, tint = Purple)
                                    }
                                }
                                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                    Text("모임 대화 만들기", fontWeight = FontWeight.Bold)
                                    Text("관심사가 비슷한 이웃과 대화를 시작해보세요.", fontSize = 11.sp, color = SubtleInk)
                                }
                                Text("›", fontSize = 24.sp)
                            }
                        }
                    }
                }
                if (shown.isEmpty()) {
                    item {
                        ConversationEmptyState(
                            loading = isLoading,
                            loadFailed = loadFailed,
                            onRetry = { scope.launch { refreshRooms() } }
                        )
                    }
                }
                items(shown) { conversation ->
                    ConversationRow(conversation) { onOpen(conversation) }
                }
            }
        }
    }
}

@Composable
private fun ConversationEmptyState(
    loading: Boolean,
    loadFailed: Boolean,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 54.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (loading) {
            CircularProgressIndicator(color = Purple, strokeWidth = 3.dp)
            Spacer(Modifier.height(14.dp))
            Text("대화 목록을 불러오고 있어요.", color = SubtleInk, fontSize = 13.sp)
            return@Column
        }
        Surface(Modifier.size(54.dp), shape = CircleShape, color = PurpleSoft) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Send, null, tint = Purple)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            if (loadFailed) "대화 목록을 불러오지 못했어요." else "서로 대화한 내역이 없습니다.",
            fontWeight = FontWeight.Bold
        )
        if (loadFailed) {
            Spacer(Modifier.height(6.dp))
            Text("서버 연결을 확인한 뒤 다시 시도해주세요.", color = SubtleInk, fontSize = 12.sp)
            Spacer(Modifier.height(14.dp))
            Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Purple)) {
                Text("다시 시도")
            }
        }
    }
}

@Composable
private fun ConversationRow(item: Conversation, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(50.dp),
            shape = CircleShape,
            color = if (item.isGroup) Peach.copy(alpha = 0.35f) else PurpleSoft
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (item.isGroup) Icons.Outlined.Groups else Icons.Outlined.Send,
                    null,
                    tint = if (item.isGroup) Color(0xFFE88E76) else Purple
                )
            }
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, fontWeight = FontWeight.Bold)
                if (item.isGroup && item.members > 0) {
                    Text("  ${item.members}명", color = SubtleInk, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(item.preview, color = SubtleInk, fontSize = 12.sp, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(item.time, color = SubtleInk, fontSize = 10.sp)
            if (item.unread) {
                Spacer(Modifier.height(8.dp))
                Surface(Modifier.size(8.dp), shape = CircleShape, color = Purple) {}
            }
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 84.dp), color = LineColor)
}

@Composable
fun ChatScreen(
    title: String,
    isGroup: Boolean,
    chatRoomId: String?,
    markAsRead: suspend (chatRoomId: String, lastReadMessageId: String) -> Result<Int>,
    leaveRoom: suspend (chatRoomId: String) -> Result<Unit>,
    onLeft: () -> Unit,
    onBack: () -> Unit
) {
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var text by remember { mutableStateOf("") }
    var roomInfo by remember { mutableStateOf<ChatRoomInfo?>(null) }
    var socketConnection by remember { mutableStateOf<ChatSocketConnection?>(null) }
    var messagesLoading by remember { mutableStateOf(chatRoomId != null) }
    var roomMenuExpanded by remember { mutableStateOf(false) }
    var showLeaveDialog by remember { mutableStateOf(false) }
    var isLeaving by remember { mutableStateOf(false) }
    var leaveError by remember { mutableStateOf<String?>(null) }
    val chatScope = rememberCoroutineScope()
    val messageListState = rememberLazyListState()

    LaunchedEffect(chatRoomId) {
        messages.clear()
        messagesLoading = chatRoomId != null
        chatRoomId?.let { roomId ->
            ChatApi.getRoom(roomId).onSuccess { roomInfo = it }
            ChatApi.getMessages(roomId)
                .onSuccess { loaded ->
                    messages.addAll(loaded)
                    loaded.lastOrNull()?.id
                        ?.let { lastMessageId ->
                            markAsRead(roomId, lastMessageId)
                        }
                }
            messagesLoading = false
        }
    }

    DisposableEffect(chatRoomId) {
        val connection = chatRoomId?.let { roomId ->
            ChatSocket.connect(
                chatRoomId = roomId,
                onMessage = { incoming ->
                    chatScope.launch {
                        if (incoming.id == null || messages.none { it.id == incoming.id }) {
                            messages.add(incoming)
                        }
                        incoming.id?.let { lastMessageId ->
                            markAsRead(roomId, lastMessageId)
                        }
                    }
                },
                onFailure = {
                    chatScope.launch { socketConnection = null }
                }
            )
        }
        socketConnection = connection
        onDispose {
            connection?.close()
            socketConnection = null
        }
    }

    LaunchedEffect(messages.size, messagesLoading) {
        if (!messagesLoading && messages.isNotEmpty()) {
            messageListState.scrollToItem(messages.lastIndex)
        }
    }

    PaperBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                Surface(color = Paper.copy(alpha = 0.96f)) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "뒤로") }
                            Surface(Modifier.size(38.dp), shape = CircleShape, color = PurpleSoft) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(if (isGroup) Icons.Outlined.Groups else Icons.Outlined.Send, null, tint = Purple)
                                }
                            }
                            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                                Text(roomInfo?.name ?: title, fontWeight = FontWeight.ExtraBold)
                                Text(
                                    if (isGroup) {
                                        roomInfo?.participantCount?.let { "${it}명 참여" } ?: "모임 대화"
                                    } else {
                                        "1:1 대화"
                                    },
                                    color = Color(0xFF67B985),
                                    fontSize = 11.sp
                                )
                            }
                            Box {
                                IconButton(onClick = { roomMenuExpanded = true }) {
                                    Icon(Icons.Outlined.MoreVert, "더보기")
                                }
                                DropdownMenu(
                                    expanded = roomMenuExpanded,
                                    onDismissRequest = { roomMenuExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "대화방 나가기",
                                                color = Color(0xFFD95C55),
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        },
                                        onClick = {
                                            roomMenuExpanded = false
                                            leaveError = null
                                            showLeaveDialog = true
                                        }
                                    )
                                }
                            }
                        }
                        Card(
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                            colors = CardDefaults.cardColors(PurpleSoft.copy(alpha = 0.55f)),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Lock, null, tint = SubtleInk, modifier = Modifier.size(18.dp))
                                Text(
                                    if (isGroup) "서로를 존중하며 따뜻하게 대화해주세요." else "서로의 소중한 대화를 보호하고 있어요.",
                                    modifier = Modifier.padding(start = 8.dp),
                                    color = SubtleInk,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            },
            bottomBar = {
                Surface(
                    modifier = Modifier.fillMaxWidth().imePadding().padding(12.dp),
                    shape = RoundedCornerShape(28.dp),
                    color = BlockSurface,
                    shadowElevation = 4.dp
                ) {
                    Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (isGroup) IconButton(onClick = {}) { Icon(Icons.Outlined.Add, "첨부") }
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("메시지를 입력해주세요", fontSize = 12.sp) },
                            singleLine = true
                        )
                        IconButton(onClick = {
                            val content = text.trim()
                            if (content.isNotBlank()) {
                                text = ""
                                chatRoomId?.let { roomId ->
                                    chatScope.launch {
                                        ChatApi.sendMessage(roomId, content)
                                            .onSuccess { sent ->
                                                if (messages.none { it.id == sent.id }) {
                                                    messages.add(sent)
                                                }
                                            }
                                    }
                                }
                            }
                        }) { Icon(Icons.Outlined.Send, "전송", tint = Purple) }
                    }
                }
            }
        ) { padding ->
            LazyColumn(
                state = messageListState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 64.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (messagesLoading) {
                                CircularProgressIndicator(color = Purple, strokeWidth = 3.dp)
                            } else {
                                Text("아직 주고받은 메시지가 없습니다.", color = SubtleInk)
                            }
                        }
                    }
                }
                items(messages) { message -> MessageBubble(message) }
            }
        }
    }

    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!isLeaving) showLeaveDialog = false
            },
            title = {
                Text(
                    if (isGroup) "모임 대화에서 나갈까요?" else "익명 대화에서 나갈까요?",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text("나가면 이 대화방이 내 대화 목록에서 사라집니다.")
                    leaveError?.let { message ->
                        Spacer(Modifier.height(10.dp))
                        Text(message, color = Color(0xFFD95C55), fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !isLeaving && chatRoomId != null,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD95C55)),
                    onClick = {
                        val roomId = chatRoomId ?: return@Button
                        chatScope.launch {
                            isLeaving = true
                            leaveError = null
                            leaveRoom(roomId).fold(
                                onSuccess = {
                                    socketConnection?.close()
                                    socketConnection = null
                                    showLeaveDialog = false
                                    onLeft()
                                },
                                onFailure = { error ->
                                    leaveError = error.message ?: "대화방을 나가지 못했습니다."
                                }
                            )
                            isLeaving = false
                        }
                    }
                ) {
                    if (isLeaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("나가기")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isLeaving,
                    onClick = { showLeaveDialog = false }
                ) {
                    Text("취소")
                }
            }
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!message.mine) {
            Surface(Modifier.size(28.dp), shape = CircleShape, color = PurpleSoft) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Send, null, tint = Purple, modifier = Modifier.size(14.dp))
                }
            }
            Spacer(Modifier.size(7.dp))
        }
        Column(horizontalAlignment = if (message.mine) Alignment.End else Alignment.Start) {
            if (!message.mine) Text(message.sender, fontSize = 10.sp, color = SubtleInk)
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (message.mine) PurpleSoft else BlockSurface,
                shadowElevation = if (message.mine) 0.dp else 2.dp
            ) {
                Text(
                    message.body,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            }
            Text(message.time, color = SubtleInk, fontSize = 9.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupScreen(
    loadInviteUsers: suspend (String?) -> Result<List<MeetingInviteUser>>,
    createMeeting: suspend (String, String, List<String>) -> Result<MeetingCreation>,
    onBack: () -> Unit,
    onCreated: (String, String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var intro by remember { mutableStateOf("") }
    val selectedPeople = remember { mutableStateListOf<String>() }
    var showPeoplePicker by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var remotePeople by remember { mutableStateOf(emptyList<MeetingInviteUser>()) }
    var inviteUsersLoading by remember { mutableStateOf(false) }
    var isCreating by remember { mutableStateOf(false) }
    var creationFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val peopleSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val shownPeople = remotePeople

    LaunchedEffect(showPeoplePicker, searchQuery) {
        if (showPeoplePicker) {
            if (searchQuery.isNotBlank()) delay(300)
            inviteUsersLoading = true
            loadInviteUsers(searchQuery.takeIf { it.isNotBlank() })
                .onSuccess { remotePeople = it }
                .onFailure { remotePeople = emptyList() }
            inviteUsersLoading = false
        }
    }

    PaperBackground {
        Scaffold(containerColor = Color.Transparent) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "뒤로") }
                        Text("모임 대화 만들기", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Text("관심사가 비슷한 사람들과 대화를 시작해보세요.", color = SubtleInk, fontSize = 12.sp)
                }
                item {
                    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(BlockSurface)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("모임 대화 제목", color = Purple, fontWeight = FontWeight.Bold)
                            OutlinedTextField(title, { title = it.take(30) }, Modifier.fillMaxWidth(), placeholder = { Text("모임 제목을 입력해주세요.") })
                            Text("모임 소개", color = Purple, fontWeight = FontWeight.Bold)
                            OutlinedTextField(
                                intro,
                                { intro = it.take(150) },
                                Modifier.fillMaxWidth().height(110.dp),
                                placeholder = { Text("어떤 주제로, 어떤 이야기를 나누고 싶은지 소개해주세요.") }
                            )
                            Text("모임 인원 선택", color = Purple, fontWeight = FontWeight.Bold)
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showPeoplePicker = true },
                                shape = RoundedCornerShape(14.dp),
                                color = PurpleSoft.copy(alpha = 0.5f)
                            ) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.GroupAdd, null, tint = Purple)
                                    Text(
                                        if (selectedPeople.isEmpty()) {
                                            "함께할 이웃을 선택해주세요."
                                        } else {
                                            "${selectedPeople.size}명 선택됨"
                                        },
                                        Modifier.padding(start = 10.dp).weight(1f)
                                    )
                                    Text("›")
                                }
                            }
                        }
                    }
                }
                item {
                    Button(
                        onClick = {
                            if (!isCreating) {
                                isCreating = true
                                creationFailed = false
                                scope.launch {
                                    createMeeting(title, intro, selectedPeople.toList())
                                        .onSuccess { onCreated(title, it.chatRoomId) }
                                        .onFailure { creationFailed = true }
                                    isCreating = false
                                }
                            }
                        },
                        enabled = title.isNotBlank() &&
                            intro.isNotBlank() &&
                            selectedPeople.isNotEmpty() &&
                            !isCreating,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Purple)
                    ) {
                        Icon(Icons.Outlined.Groups, null)
                        Text(if (isCreating) "  모임을 만들고 있어요" else "  모임 대화 만들기")
                    }
                    if (creationFailed) {
                        Text(
                            "모임을 만들지 못했습니다. 잠시 후 다시 시도해주세요.",
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                            color = Color(0xFFD95C55),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }

    if (showPeoplePicker) {
        ModalBottomSheet(
            onDismissRequest = { showPeoplePicker = false },
            sheetState = peopleSheetState,
            containerColor = BlockSurface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            dragHandle = null
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.88f)
                    .padding(horizontal = 20.dp)
                    .padding(top = 16.dp, bottom = 22.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("모임 인원 선택", fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
                        Text(
                            "대화한 이웃 중 함께할 사람을 선택해주세요. (최대 9명)",
                            color = SubtleInk,
                            fontSize = 11.sp
                        )
                    }
                    IconButton(onClick = { showPeoplePicker = false }) {
                        Icon(Icons.Outlined.Close, "인원 선택 닫기")
                    }
                }

                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it.take(30) },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    placeholder = { Text("닉네임으로 검색") },
                    singleLine = true,
                    shape = RoundedCornerShape(15.dp)
                )
                Spacer(Modifier.height(10.dp))
                if (inviteUsersLoading) {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Purple, strokeWidth = 3.dp)
                    }
                } else if (shownPeople.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (searchQuery.isBlank()) "초대할 수 있는 이웃이 없어요."
                            else "검색 결과가 없어요.",
                            color = SubtleInk
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(shownPeople, key = { it.candidateId }) { person ->
                            val isSelected = person.candidateId in selectedPeople
                            val canSelect = isSelected || selectedPeople.size < 9
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = canSelect) {
                                        if (isSelected) {
                                            selectedPeople.remove(person.candidateId)
                                        } else {
                                            selectedPeople.add(person.candidateId)
                                        }
                                    },
                                shape = RoundedCornerShape(15.dp),
                                color = if (isSelected) PurpleSoft else BlockSurface
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        modifier = Modifier.size(46.dp),
                                        shape = CircleShape,
                                        color = PurpleSoft
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                Icons.Outlined.Send,
                                                contentDescription = null,
                                                tint = Purple,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                    }
                                    Column(
                                        modifier = Modifier
                                            .padding(start = 12.dp)
                                            .weight(1f)
                                    ) {
                                        Text(person.nickname, fontWeight = FontWeight.Bold)
                                        Text(
                                            "대화한 이웃",
                                            color = SubtleInk,
                                            fontSize = 11.sp,
                                            maxLines = 1
                                        )
                                    }
                                    Checkbox(
                                        checked = isSelected,
                                        enabled = canSelect,
                                        onCheckedChange = { checked ->
                                            if (checked) {
                                                if (person.candidateId !in selectedPeople && selectedPeople.size < 9) {
                                                    selectedPeople.add(person.candidateId)
                                                }
                                            } else {
                                                selectedPeople.remove(person.candidateId)
                                            }
                                        }
                                    )
                                }
                            }
                            HorizontalDivider(color = LineColor.copy(alpha = 0.55f))
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { showPeoplePicker = false },
                    enabled = selectedPeople.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(27.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Purple)
                ) {
                    Text(
                        if (selectedPeople.isEmpty()) {
                            "함께할 이웃을 선택해주세요"
                        } else {
                            "선택 완료 (${selectedPeople.size}명)"
                        },
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
