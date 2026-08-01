package com.apptive.slowtalk

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.collectAsState
import com.apptive.slowtalk.ui.profile.ProfileViewModel
import com.apptive.slowtalk.ui.profile.ProfileUiState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun WriteLetterScreen(
    onHistory: () -> Unit,
    onMatched: () -> Unit,
    onTab: (MainTab) -> Unit
) {
    var body by remember {
        mutableStateOf(
            "오늘은 평소보다 조금 느리게 걸어봤어요.\n\n늘 빠르게만 지나치던 길들이\n천천히 바라보니 이렇게 예쁘더라고요.\n\n여러분의 하루는 어땠나요?"
        )
    }
    var showMatch by remember { mutableStateOf(false) }
    var showOcr by remember { mutableStateOf(false) }
    PaperBackground {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = { AppBottomBar(MainTab.LETTER, onTab) }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("편지 쓰기", fontSize = 29.sp, fontWeight = FontWeight.ExtraBold)
                            Text("익명의 이웃에게 당신의 이야기를 들려주세요.", color = SubtleInk, fontSize = 13.sp)
                        }
                        IconButton(onClick = onHistory) { Icon(Icons.Outlined.History, "이전 편지") }
                    }
                }
                item {
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(BlockSurface),
                        elevation = CardDefaults.cardElevation(2.dp)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Edit, null, tint = Purple)
                                Text("  오늘의 편지", color = Purple, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.weight(1f))
                                Text("${body.length} / 1,000자", color = Purple, fontSize = 11.sp)
                            }
                            OutlinedTextField(
                                value = body,
                                onValueChange = { body = it.take(1000) },
                                modifier = Modifier.fillMaxWidth().height(300.dp),
                                placeholder = { Text("당신의 하루를 천천히 들려주세요.") }
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {}) {
                                    Icon(Icons.Outlined.Image, null)
                                    Text("  사진")
                                }
                                OutlinedButton(onClick = { showOcr = true }) {
                                    Icon(Icons.Outlined.CameraAlt, null)
                                    Text("  손편지 OCR")
                                }
                            }
                        }
                    }
                }
                item {
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(PurpleSoft.copy(alpha = 0.72f))
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.AutoAwesome, null, tint = Purple)
                                Text("  AI 편지 도우미", color = Purple, fontWeight = FontWeight.ExtraBold)
                                Spacer(Modifier.weight(1f))
                                Text("실시간 분석 중", color = Purple, fontSize = 10.sp)
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Verified, null, tint = Color(0xFF51BF86))
                                Column(Modifier.padding(start = 9.dp)) {
                                    Text("좋은 흐름이에요!", color = Color(0xFF23A664), fontWeight = FontWeight.Bold)
                                    Text("상대방이 부담 없이 읽기 좋은 문장입니다.", color = SubtleInk, fontSize = 11.sp)
                                }
                            }
                            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color.White)
                            Text("더 따뜻해지는 작은 팁", color = Purple, fontWeight = FontWeight.Bold)
                            Text("• 감정을 표현해주셔서 좋아요.\n• 마지막 인사도 따뜻해서 기분 좋은 편지가 될 것 같아요.", fontSize = 12.sp, lineHeight = 19.sp)
                        }
                    }
                }
                item {
                    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(BlockSurface)) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Lock, null, tint = Purple)
                                Text("  공개 범위", color = Purple, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(10.dp))
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                color = Color(0xFFF9F7F4)
                            ) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.LocationOn, null, tint = Purple)
                                    Text("  서울 마포구", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                                    Icon(Icons.Outlined.ChevronRight, null)
                                }
                            }
                            Text("익명으로 작성되어 누구에게 썼는지 알 수 없어요.", color = SubtleInk, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
                        }
                    }
                }
                item {
                    Button(
                        onClick = { showMatch = true },
                        enabled = body.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Purple)
                    ) {
                        Icon(Icons.Outlined.Send, null)
                        Text("  편지 보내기", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showOcr) {
        AlertDialog(
            onDismissRequest = { showOcr = false },
            icon = { Icon(Icons.Outlined.CameraAlt, null, tint = Purple) },
            title = { Text("손편지 가져오기") },
            text = { Text("손글씨 사진을 읽어 편지 본문으로 옮기는 OCR 데모입니다.") },
            confirmButton = {
                TextButton(onClick = {
                    body = "오늘 하루도 잘 보내셨나요?\n손으로 적은 마음을 천천히 전해봅니다.\n작은 기쁨이 오래 머무는 하루이길 바라요."
                    showOcr = false
                }) { Text("샘플 인식하기") }
            },
            dismissButton = { TextButton(onClick = { showOcr = false }) { Text("취소") } }
        )
    }

    if (showMatch) {
        AlertDialog(
            onDismissRequest = { showMatch = false },
            icon = { Icon(Icons.Outlined.MailOutline, null, tint = Purple) },
            title = { Text("편지가 전해졌어요") },
            text = { Text("관심사가 비슷한 익명의 이웃과 연결됐습니다. 편지는 첫 메시지로 전달되며 바로 대화를 시작할 수 있어요.") },
            confirmButton = {
                Button(onClick = { showMatch = false; onMatched() }) {
                    Text("대화방으로 가기")
                }
            },
            dismissButton = { TextButton(onClick = { showMatch = false }) { Text("계속 둘러보기") } }
        )
    }
}

@Composable
fun LetterHistoryScreen(
    letters: List<Letter>,
    loadLetters: suspend (String?) -> Result<List<Letter>>,
    onBack: () -> Unit,
    onOpen: (Letter) -> Unit
) {
    var filter by remember { mutableStateOf(0) }
    var remoteLetters by remember { mutableStateOf<List<Letter>?>(null) }
    val requestedType = when (filter) {
        1 -> "SENT"
        2 -> "RECEIVED"
        else -> null
    }
    LaunchedEffect(filter) {
        remoteLetters = null
        loadLetters(requestedType).onSuccess { remoteLetters = it }
    }
    val visible = (remoteLetters ?: letters).filter {
        filter == 0 || (filter == 1 && !it.received) || (filter == 2 && it.received)
    }
    PaperBackground {
        Scaffold(containerColor = Color.Transparent) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "뒤로") }
                        Column {
                            Text("이전 편지", fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
                            Text("지금까지 나눈 편지를 돌아보세요.", color = SubtleInk, fontSize = 12.sp)
                        }
                    }
                    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("전체", "보낸 편지", "받은 편지").forEachIndexed { index, label ->
                            androidx.compose.material3.FilterChip(
                                selected = filter == index,
                                onClick = { filter = index },
                                label = { Text(label) }
                            )
                        }
                    }
                }
                items(visible) { letter ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(letter) },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(BlockSurface),
                        elevation = CardDefaults.cardElevation(2.dp)
                    ) {
                        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                Modifier.size(42.dp),
                                shape = CircleShape,
                                color = if (letter.received) PurpleSoft else Peach.copy(alpha = 0.28f)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.MailOutline, null, tint = if (letter.received) Purple else Color(0xFFE87962))
                                }
                            }
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(if (letter.received) "받은 편지" else "보낸 편지", color = if (letter.received) Purple else Color(0xFFE87962), fontSize = 10.sp)
                                Text(letter.title, fontWeight = FontWeight.Bold)
                                Text(letter.preview, color = SubtleInk, fontSize = 11.sp, maxLines = 2)
                                Text(letter.date, color = SubtleInk, fontSize = 9.sp)
                            }
                            Icon(Icons.Outlined.ChevronRight, null)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LetterDetailScreen(
    letter: Letter,
    loadLetter: suspend (Int) -> Result<Letter>,
    onBack: () -> Unit
) {
    var displayedLetter by remember(letter.id, letter.title) { mutableStateOf(letter) }
    LaunchedEffect(letter.id) {
        letter.id?.let { id ->
            loadLetter(id).onSuccess { displayedLetter = it }
        }
    }
    PaperBackground {
        Scaffold(containerColor = Color.Transparent) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "뒤로") }
                    Text("이전 편지", fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
                }
                Text(if (displayedLetter.received) "받은 편지" else "보낸 편지", color = Purple, fontWeight = FontWeight.Bold)
                Text(displayedLetter.title, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(vertical = 8.dp))
                Text(displayedLetter.date, color = SubtleInk, fontSize = 12.sp)
                Spacer(Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(PurpleSoft.copy(alpha = 0.6f))
                ) {
                    Text(
                        displayedLetter.content.ifBlank { displayedLetter.preview },
                        modifier = Modifier.padding(24.dp),
                        fontSize = 15.sp,
                        lineHeight = 27.sp
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditScreen(
    viewModel: ProfileViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val provinces by viewModel.provinces.collectAsState()
    val districts by viewModel.districts.collectAsState()
    val subDistricts by viewModel.subDistricts.collectAsState()
    
    val currentProfile = (uiState as? ProfileUiState.Success)?.profile

    var nickname by remember { mutableStateOf(currentProfile?.nickname ?: "지연") }
    var intro by remember { mutableStateOf(currentProfile?.bio ?: "") }
    
    // 지역 정보 파싱 (기존 로직 유지하되 서버 데이터 우선)
    val serverLocation = currentProfile?.let { 
        buildString {
            append(it.region.province)
            append(" ")
            append(it.region.district)
            it.region.subDistrict?.let { sub -> append(" "); append(sub) }
        }
    } ?: "서울특별시 마포구"

    val initialParts = remember(serverLocation) { serverLocation.split(" ") }
    
    var location by remember(serverLocation) { mutableStateOf(serverLocation) }
    var showLocation by remember { mutableStateOf(false) }
    var selectedProvince by remember(serverLocation) { mutableStateOf(initialParts.getOrNull(0) ?: "서울특별시") }
    var selectedDistrict by remember(serverLocation) { mutableStateOf(initialParts.getOrNull(1) ?: "마포구") }
    var selectedNeighborhood by remember(serverLocation) { mutableStateOf(initialParts.getOrNull(2)) }
    
    var expandedLevel by remember { mutableStateOf<ResidenceLevel?>(null) }
    val locationSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // API 호출 연동
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.fetchProvinces()
    }

    androidx.compose.runtime.LaunchedEffect(selectedProvince) {
        if (selectedProvince.isNotEmpty()) {
            viewModel.fetchDistricts(selectedProvince)
        }
    }

    androidx.compose.runtime.LaunchedEffect(selectedProvince, selectedDistrict) {
        if (selectedProvince.isNotEmpty() && selectedDistrict.isNotEmpty()) {
            viewModel.fetchSubDistricts(selectedProvince, selectedDistrict)
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
                        Text("프로필 편집", Modifier.weight(1f), fontWeight = FontWeight.ExtraBold, fontSize = 21.sp)
                        Text(
                            "저장",
                            modifier = Modifier.clickable {
                                viewModel.updateProfile(
                                    nickname = nickname,
                                    bio = intro,
                                    interest = currentProfile?.interest ?: "",
                                    province = selectedProvince,
                                    district = selectedDistrict,
                                    subDistrict = selectedNeighborhood
                                )
                                onBack()
                            },
                            color = Purple,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        androidx.compose.foundation.Image(
                            painter = painterResource(R.drawable.profile_avatar),
                            contentDescription = "지연 프로필 사진",
                            modifier = Modifier.size(108.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                        Text("따뜻한 이웃", color = SubtleInk, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                item {
                    Text("닉네임", fontWeight = FontWeight.Bold)
                    OutlinedTextField(nickname, { nickname = it.take(10) }, Modifier.fillMaxWidth(), singleLine = true)
                }
                item {
                    Text("소개", fontWeight = FontWeight.Bold)
                    OutlinedTextField(intro, { intro = it.take(100) }, Modifier.fillMaxWidth().height(110.dp))
                }
                item {
                    Text("거주지", fontWeight = FontWeight.Bold)
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { showLocation = true },
                        shape = RoundedCornerShape(14.dp),
                        color = BlockSurface
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.LocationOn, null, tint = Purple)
                            Text(location, Modifier.padding(start = 8.dp).weight(1f))
                            Icon(Icons.Outlined.ExpandMore, "거주지 설정 열기", tint = SubtleInk)
                        }
                    }
                }
                item {
                    Text("통계", fontWeight = FontWeight.Bold)
                    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(BlockSurface)) {
                        Row(
                            Modifier.fillMaxWidth().padding(18.dp),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            Stat(Icons.Outlined.MailOutline, "12", "받은 편지")
                            Stat(Icons.Outlined.Send, "8", "보낸 편지")
                            Stat(Icons.Outlined.People, "5", "매칭한 사람")
                        }
                    }
                }
            }
        }
    }

    if (showLocation) {
        ModalBottomSheet(
            onDismissRequest = {
                expandedLevel = null
                showLocation = false
            },
            sheetState = locationSheetState,
            containerColor = BlockSurface,
            shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
            dragHandle = null
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (expandedLevel != null) {
                            Modifier.fillMaxHeight(0.96f)
                        } else {
                            Modifier
                        }
                    )
                    .padding(horizontal = 24.dp)
                    .padding(top = 16.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.size(48.dp))
                    Text(
                        "거주지 설정",
                        modifier = Modifier.weight(1f),
                        fontSize = 21.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    IconButton(
                        onClick = {
                            expandedLevel = null
                            showLocation = false
                        }
                    ) {
                        Icon(Icons.Outlined.Close, "거주지 설정 닫기", tint = SubtleInk)
                    }
                }
                Text(
                    "현재 거주하는 지역을 설정해주세요.",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = SubtleInk,
                    fontSize = 12.sp
                )

                if (expandedLevel == ResidenceLevel.PROVINCE) {
                    ResidenceOptionsAbove(
                        modifier = Modifier.weight(1f),
                        options = provinces,
                        selected = selectedProvince,
                        onSelect = { province ->
                            selectedProvince = province
                            selectedDistrict = ""
                            selectedNeighborhood = null
                            expandedLevel = null
                        }
                    )
                }
                ResidenceSelectRow(
                    label = "시 · 도",
                    value = selectedProvince,
                    expanded = expandedLevel == ResidenceLevel.PROVINCE,
                    onClick = {
                        expandedLevel = if (expandedLevel == ResidenceLevel.PROVINCE) {
                            null
                        } else {
                            ResidenceLevel.PROVINCE
                        }
                    }
                )

                if (expandedLevel == ResidenceLevel.DISTRICT) {
                    ResidenceOptionsAbove(
                        modifier = Modifier.weight(1f),
                        options = districts,
                        selected = selectedDistrict,
                        onSelect = { district ->
                            selectedDistrict = district
                            selectedNeighborhood = null
                            expandedLevel = null
                        }
                    )
                }
                ResidenceSelectRow(
                    label = "시 · 군 · 구",
                    value = selectedDistrict,
                    expanded = expandedLevel == ResidenceLevel.DISTRICT,
                    onClick = {
                        expandedLevel = if (expandedLevel == ResidenceLevel.DISTRICT) {
                            null
                        } else {
                            ResidenceLevel.DISTRICT
                        }
                    }
                )

                if (expandedLevel == ResidenceLevel.NEIGHBORHOOD) {
                    ResidenceOptionsAbove(
                        modifier = Modifier.weight(1f),
                        options = listOf("선택 안 함") + subDistricts,
                        selected = selectedNeighborhood ?: "선택 안 함",
                        onSelect = { neighborhood ->
                            selectedNeighborhood = neighborhood.takeUnless { it == "선택 안 함" }
                            expandedLevel = null
                        }
                    )
                }
                ResidenceSelectRow(
                    label = "동 · 읍 · 면 (선택)",
                    value = selectedNeighborhood ?: "선택 안 함",
                    expanded = expandedLevel == ResidenceLevel.NEIGHBORHOOD,
                    onClick = {
                        expandedLevel = if (expandedLevel == ResidenceLevel.NEIGHBORHOOD) {
                            null
                        } else {
                            ResidenceLevel.NEIGHBORHOOD
                        }
                    }
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Verified,
                        contentDescription = null,
                        tint = SubtleInk,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        "설정한 거주지는 다른 사용자에게 공개되지 않아요.",
                        modifier = Modifier.padding(start = 7.dp),
                        color = SubtleInk,
                        fontSize = 11.sp
                    )
                }

                Button(
                    onClick = {
                        val updatedLocation = buildResidenceLabel(
                            selectedProvince,
                            selectedDistrict,
                            selectedNeighborhood
                        )
                        location = updatedLocation
                        expandedLevel = null
                        showLocation = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Purple)
                ) {
                    Text("저장", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
            }
        }
    }
}

private enum class ResidenceLevel {
    PROVINCE,
    DISTRICT,
    NEIGHBORHOOD
}

private val residenceDistricts = linkedMapOf(
    "서울특별시" to listOf("마포구", "서대문구", "은평구", "종로구", "중구", "용산구", "성동구", "광진구", "강남구", "송파구"),
    "부산광역시" to listOf("해운대구", "수영구", "부산진구", "동래구", "남구", "북구"),
    "대구광역시" to listOf("중구", "동구", "서구", "남구", "북구", "수성구", "달서구"),
    "인천광역시" to listOf("중구", "동구", "미추홀구", "연수구", "남동구", "부평구", "서구"),
    "광주광역시" to listOf("동구", "서구", "남구", "북구", "광산구"),
    "대전광역시" to listOf("동구", "중구", "서구", "유성구", "대덕구"),
    "울산광역시" to listOf("중구", "남구", "동구", "북구", "울주군"),
    "세종특별자치시" to listOf("세종시"),
    "경기도" to listOf("수원시", "성남시", "고양시", "용인시", "부천시", "안양시", "남양주시", "화성시"),
    "강원특별자치도" to listOf("춘천시", "원주시", "강릉시", "속초시", "동해시", "홍천군"),
    "충청북도" to listOf("청주시", "충주시", "제천시", "음성군", "진천군"),
    "충청남도" to listOf("천안시", "공주시", "보령시", "아산시", "서산시", "당진시"),
    "전북특별자치도" to listOf("전주시", "군산시", "익산시", "정읍시", "남원시"),
    "전라남도" to listOf("목포시", "여수시", "순천시", "나주시", "광양시"),
    "경상북도" to listOf("포항시", "경주시", "김천시", "안동시", "구미시"),
    "경상남도" to listOf("창원시", "진주시", "통영시", "사천시", "김해시", "양산시"),
    "제주특별자치도" to listOf("제주시", "서귀포시")
)

private val residenceNeighborhoodData = mapOf(
    "서울특별시|마포구" to listOf("공덕동", "아현동", "도화동", "용강동", "대흥동", "서교동", "합정동", "망원동", "연남동", "상암동"),
    "서울특별시|서대문구" to listOf("충현동", "천연동", "신촌동", "연희동", "홍제동", "홍은동", "남가좌동", "북가좌동"),
    "서울특별시|은평구" to listOf("녹번동", "불광동", "갈현동", "구산동", "대조동", "응암동", "역촌동", "진관동"),
    "부산광역시|해운대구" to listOf("우동", "중동", "좌동", "송정동", "반여동", "반송동", "재송동"),
    "대전광역시|유성구" to listOf("진잠동", "온천동", "노은동", "신성동", "전민동", "구즉동", "관평동"),
    "경기도|수원시" to listOf("장안구", "권선구", "팔달구", "영통구"),
    "경기도|성남시" to listOf("수정구", "중원구", "분당구"),
    "제주특별자치도|제주시" to listOf("일도동", "이도동", "삼도동", "용담동", "건입동", "화북동", "아라동", "노형동")
)

private fun residenceNeighborhoods(province: String, district: String): List<String> =
    residenceNeighborhoodData["$province|$district"]
        ?: listOf("중앙동", "동부동", "서부동", "남부동", "북부동")

private fun buildResidenceLabel(
    province: String,
    district: String,
    neighborhood: String?
): String {
    return listOfNotNull(officialProvinceLabel(province), district, neighborhood).joinToString(" ")
}

private fun officialProvinceLabel(province: String): String = province

@Composable
private fun ResidenceSelectRow(
    label: String,
    value: String,
    expanded: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = BlockSurface,
        border = BorderStroke(1.dp, LineColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, modifier = Modifier.width(118.dp), color = SubtleInk, fontSize = 13.sp)
            Text(
                value,
                modifier = Modifier.weight(1f),
                color = if (value == "선택 안 함") SubtleInk else Ink,
                fontWeight = FontWeight.Medium
            )
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "$label 목록 닫기" else "$label 목록 열기",
                tint = SubtleInk
            )
        }
    }
}

@Composable
private fun ResidenceOptionsAbove(
    modifier: Modifier = Modifier,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(BlockSurface),
        border = BorderStroke(1.dp, LineColor),
        elevation = CardDefaults.cardElevation(5.dp)
    ) {
        LazyColumn {
            items(options) { option ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(option) },
                    color = if (option == selected) PurpleSoft else BlockSurface
                ) {
                    Text(
                        option,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                        color = if (option == selected) Purple else Ink,
                        fontWeight = if (option == selected) FontWeight.Bold else FontWeight.Normal
                    )
                }
                if (option != options.last()) {
                    HorizontalDivider(color = LineColor.copy(alpha = 0.7f))
                }
            }
        }
    }
}

@Composable
private fun Stat(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Purple)
        Text(value, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
        Text(label, color = SubtleInk, fontSize = 10.sp)
    }
}
