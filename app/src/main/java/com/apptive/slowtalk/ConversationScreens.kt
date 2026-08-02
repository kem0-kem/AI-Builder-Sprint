package com.apptive.slowtalk

import android.widget.Toast
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
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun ConversationListScreen(
    anonymous: List<Conversation>,
    groups: List<Conversation>,
    onOpen: (Conversation) -> Unit,
    onCreateGroup: () -> Unit,
    onProfile: () -> Unit,
    onTab: (MainTab) -> Unit,
    showBottomBar: Boolean = true
) {
    var selected by remember { mutableStateOf(0) }
    val shown = if (selected == 0) anonymous else groups
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
                                selected = selected == index,
                                onClick = { selected = index },
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
                if (selected == 1) {
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
                items(shown) { conversation ->
                    ConversationRow(conversation) { onOpen(conversation) }
                }
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
                if (item.isGroup) Text("  ${item.members}명", color = SubtleInk, fontSize = 11.sp)
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
fun ChatScreen(title: String, isGroup: Boolean, roomId: String?, onBack: () -> Unit) {
    val messages = remember {
        mutableStateListOf(
            ChatMessage(if (isGroup) "이웃 01" else title, "안녕하세요 :)\n오늘 날씨가 정말 좋네요.", "15:40", false),
            ChatMessage("나", if (isGroup) "저도 함께할게요. 천천히 걸어요 :)" else "그러게요. 바람도 선선하고 기분이 참 좋아지는 날이네요.", "15:42", true),
            ChatMessage(if (isGroup) "이웃 02" else title, if (isGroup) "좋아요! 공원 입구에서 만나요." else "저도 방금 산책하고 왔어요.\n늘 지나치던 길인데 오늘은 유난히 예쁜 꽃들이 많더라고요.", "15:45", false),
            ChatMessage("나", if (isGroup) "네, 토요일에 봬요!" else "맞아요. 그런 순간들이 하루를 조금 더 행복하게 만들어주는 것 같아요.", "15:51", true)
        )
    }
    var textState by remember { mutableStateOf(TextFieldValue("")) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(roomId) {
        messages.clear()
        if (roomId != null) {
            ChatApi.getMessages(roomId).onSuccess { messages.addAll(it) }.onFailure {
                Toast.makeText(context, "대화를 불러오지 못했습니다.", Toast.LENGTH_SHORT).show()
            }
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
                                Text(title, fontWeight = FontWeight.ExtraBold)
                                Text(if (isGroup) "5명 참여" else "온라인", color = Color(0xFF67B985), fontSize = 11.sp)
                            }
                            IconButton(onClick = {}) { Icon(Icons.Outlined.MoreVert, "더보기") }
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
                            value = textState,
                            onValueChange = { textState = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("메시지를 입력해주세요", fontSize = 12.sp) },
                            singleLine = true
                        )
                        IconButton(onClick = {
                            if (textState.text.isNotBlank()) {
                                val content = textState.text.trim()
                                messages.add(ChatMessage("나", content, "지금", true))
                                textState = TextFieldValue("")
                                if (roomId == null) {
                                    Toast.makeText(context, "서버 대화방이 아닙니다.", Toast.LENGTH_SHORT).show()
                                } else {
                                    scope.launch {
                                        ChatApi.sendMessage(roomId, content).onSuccess { message ->
                                            val last = messages.lastOrNull()
                                            if (last?.body == content && last.mine) messages.remove(last)
                                            messages.add(message)
                                        }.onFailure {
                                            messages.removeAll { it.body == content && it.mine }
                                            Toast.makeText(context, "메시지를 전송하지 못했습니다.", Toast.LENGTH_SHORT).show()
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
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text(
                        "7월 29일 수요일",
                        modifier = Modifier.fillMaxWidth(),
                        color = SubtleInk,
                        fontSize = 10.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
                items(messages) { message -> MessageBubble(message) }
            }
        }
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
    availablePeople: List<Conversation>,
    onBack: () -> Unit,
    onCreate: (String, String, List<String>) -> Unit
) {
    var titleState by remember { mutableStateOf(TextFieldValue("")) }
    var introState by remember { mutableStateOf(TextFieldValue("")) }
    val selectedPeople = remember { mutableStateListOf<String>() }
    var showPeoplePicker by remember { mutableStateOf(false) }
    val peopleSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
                            OutlinedTextField(
                                value = titleState,
                                onValueChange = { if (it.text.length <= 30) titleState = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("모임 제목을 입력해주세요.") }
                            )
                            Text("모임 소개", color = Purple, fontWeight = FontWeight.Bold)
                            OutlinedTextField(
                                value = introState,
                                onValueChange = { if (it.text.length <= 150) introState = it },
                                modifier = Modifier.fillMaxWidth().height(110.dp),
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
                            val candidateIds = availablePeople
                                .filter { it.title in selectedPeople }
                                .mapNotNull { it.inviteCandidateId }
                            onCreate(titleState.text, introState.text, candidateIds)
                        },
                        enabled = titleState.text.isNotBlank() &&
                            introState.text.isNotBlank() &&
                            selectedPeople.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Purple)
                    ) {
                        Icon(Icons.Outlined.Groups, null)
                        Text("  모임 대화 만들기")
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
                if (availablePeople.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("아직 대화한 이웃이 없어요.", color = SubtleInk)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(availablePeople) { person ->
                            val isSelected = person.title in selectedPeople
                            val canSelect = isSelected || selectedPeople.size < 9
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = canSelect) {
                                        if (isSelected) {
                                            selectedPeople.remove(person.title)
                                        } else {
                                            selectedPeople.add(person.title)
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
                                        Text(person.title, fontWeight = FontWeight.Bold)
                                        Text(
                                            person.preview,
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
                                                if (person.title !in selectedPeople && selectedPeople.size < 9) {
                                                    selectedPeople.add(person.title)
                                                }
                                            } else {
                                                selectedPeople.remove(person.title)
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
