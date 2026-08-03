package com.apptive.slowtalk

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apptive.slowtalk.ui.letter.LetterUiState
import com.apptive.slowtalk.ui.letter.LetterViewModel
import com.apptive.slowtalk.ui.profile.ProfileUiState
import com.apptive.slowtalk.ui.profile.ProfileViewModel
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WriteLetterScreen(
    viewModel: LetterViewModel,
    profileViewModel: ProfileViewModel,
    onBack: () -> Unit,
    onHistory: () -> Unit,
    onMatched: (String) -> Unit,
    onTab: (MainTab) -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val body by viewModel.content.collectAsState()
    val aiFeedback by viewModel.aiFeedback.collectAsState()
    val isFeedbackLoading by viewModel.isFeedbackLoading.collectAsState()
    
    val profileState by profileViewModel.uiState.collectAsState()
    val provinces by profileViewModel.provinces.collectAsState()
    val districts by profileViewModel.districts.collectAsState()
    val subDistricts by profileViewModel.subDistricts.collectAsState()

    val currentProfile = (profileState as? ProfileUiState.Success)?.profile
    
    var matchEnabled by remember { mutableStateOf(true) }
    var showLocationSheet by remember { mutableStateOf(false) }
    var selectedProvince by remember { mutableStateOf(currentProfile?.region?.province?.name.orEmpty()) }
    var selectedDistrict by remember { mutableStateOf(currentProfile?.region?.district?.name.orEmpty()) }
    var selectedNeighborhood by remember { mutableStateOf(currentProfile?.region?.subDistrict?.name) }
    var showCompletionDialog by remember { mutableStateOf(false) }
    var matchedChatRoomId by remember { mutableStateOf<String?>(null) }
    
    var expandedLevel by remember { mutableStateOf<ResidenceLevel?>(null) }
    val locationSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // OCR Launchers
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val file = uriToFile(context, it)
            if (file != null) viewModel.performOcr(file)
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        bitmap?.let {
            val file = File(context.cacheDir, "letter_capture_${System.currentTimeMillis()}.jpg")
            val fos = FileOutputStream(file)
            it.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, fos)
            fos.close()
            viewModel.performOcr(file)
        }
    }

    LaunchedEffect(uiState) {
        if (uiState is LetterUiState.Success) {
            matchedChatRoomId = (uiState as LetterUiState.Success).chatRoomId
            showCompletionDialog = true
            viewModel.resetState()
        }
    }

    LaunchedEffect(currentProfile) {
        currentProfile?.let {
            selectedProvince = it.region?.province?.name.orEmpty()
            selectedDistrict = it.region?.district?.name.orEmpty()
            selectedNeighborhood = it.region?.subDistrict?.name
        }
    }

    LaunchedEffect(Unit) {
        profileViewModel.fetchProvinces()
    }

    LaunchedEffect(selectedProvince) {
        if (selectedProvince.isNotEmpty()) profileViewModel.fetchDistricts(selectedProvince)
    }

    LaunchedEffect(selectedProvince, selectedDistrict) {
        if (selectedProvince.isNotEmpty() && selectedDistrict.isNotEmpty()) {
            profileViewModel.fetchSubDistricts(selectedProvince, selectedDistrict)
        }
    }

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
                        IconButton(onClick = onBack) { 
                            Icon(Icons.Outlined.ArrowBack, "뒤로") 
                        }
                        Column(Modifier.weight(1f)) {
                            Text("편지 쓰기", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                            Text("익명의 이웃에게 당신의 이야기를 들려주세요.", color = SubtleInk, fontSize = 12.sp)
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
                        Box(Modifier.fillMaxWidth()) {
                            // Background illustration
                            Image(
                                painter = painterResource(R.drawable.letter_home_illustration),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(140.dp)
                                    .align(Alignment.BottomEnd)
                                    .padding(end = 12.dp, bottom = 12.dp),
                                alpha = 0.15f
                            )
                            
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Edit, null, tint = Purple)
                                    Text("  오늘의 편지", color = Purple, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.weight(1f))
                                    Text("${body.length} / 1,000자", color = Purple.copy(alpha = 0.7f), fontSize = 11.sp)
                                }
                                OutlinedTextField(
                                    value = body,
                                    onValueChange = { viewModel.updateContent(it.take(1000)) },
                                    modifier = Modifier.fillMaxWidth().height(260.dp),
                                    placeholder = { Text("당신의 하루를 천천히 들려주세요.", fontSize = 14.sp) },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color.Transparent,
                                        unfocusedBorderColor = Color.Transparent,
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent
                                    )
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    IconBox(Icons.Outlined.Image, onClick = { galleryLauncher.launch("image/*") })
                                    IconBox(Icons.Outlined.CameraAlt, onClick = { cameraLauncher.launch() })
                                    
                                    if (uiState is LetterUiState.Loading) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp).align(Alignment.CenterVertically),
                                            color = Purple,
                                            strokeWidth = 2.dp
                                        )
                                    }
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
                                Surface(
                                    modifier = Modifier.clickable(
                                        enabled = body.isNotBlank() && !isFeedbackLoading,
                                        onClick = viewModel::analyzeContent
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (body.isNotBlank()) {
                                        Purple.copy(alpha = 0.12f)
                                    } else {
                                        Color.White.copy(alpha = 0.55f)
                                    }
                                ) {
                                    Text(
                                        when {
                                            isFeedbackLoading -> "분석 중..."
                                            aiFeedback != null -> "다시 분석"
                                            else -> "분석하기"
                                        },
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        color = if (body.isNotBlank()) Purple else SubtleInk,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            
                            val feedback = aiFeedback
                            val warning = feedback?.warning
                            if (feedback == null) {
                                Text(
                                    "편지 내용을 입력한 뒤 분석하기를 눌러주세요.",
                                    color = SubtleInk,
                                    fontSize = 12.sp
                                )
                            } else if (warning?.exists == true) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.WarningAmber, null, tint = Color(0xFFE98175))
                                    Text(
                                        text = warning.message ?: "주의가 필요한 표현이 있습니다.",
                                        color = Color(0xFFE98175),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Verified, null, tint = Color(0xFF51BF86))
                                    Column(Modifier.padding(start = 9.dp)) {
                                        Text("좋은 흐름이에요!", color = Color(0xFF23A664), fontWeight = FontWeight.Bold)
                                        Text(
                                            feedback.summary ?: "주의가 필요한 표현이 발견되지 않았습니다.",
                                            color = SubtleInk,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }

                            if (feedback != null) {
                                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color.White)
                                Text("더 따뜻해지는 작은 팁", color = Purple, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                feedback.displayTips.forEach { tip ->
                                    Text("• $tip", fontSize = 12.sp, lineHeight = 19.sp)
                                }

                                Spacer(Modifier.height(14.dp))
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color.White.copy(alpha = 0.5f)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Outlined.MailOutline, null, tint = Purple, modifier = Modifier.size(16.dp))
                                        Text(
                                            " 따뜻한 공감 표현 예시",
                                            modifier = Modifier.weight(1f),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text("더보기", fontSize = 11.sp, color = SubtleInk, modifier = Modifier.clickable { })
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(BlockSurface)) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Lock, null, tint = Purple)
                                Text("  공개 범위 및 전송 설정", color = Purple, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(12.dp))
                            
                            // Location Selector
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showLocationSheet = true },
                                shape = RoundedCornerShape(14.dp),
                                color = Color(0xFFF9F7F4)
                            ) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.LocationOn, null, tint = Purple)
                                    val locationText = buildResidenceLabel(selectedProvince, selectedDistrict, selectedNeighborhood)
                                    Text("  $locationText", Modifier.weight(1f), fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                    Icon(Icons.Outlined.ChevronRight, null, tint = SubtleInk)
                                }
                            }
                            
                            Spacer(Modifier.height(14.dp))
                            
                            // Match Toggle
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { matchEnabled = !matchEnabled },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (matchEnabled) Icons.Outlined.Verified else Icons.Outlined.RadioButtonUnchecked,
                                    null,
                                    tint = if (matchEnabled) Purple else SubtleInk
                                )
                                Column(Modifier.padding(start = 10.dp)) {
                                    Text("이웃에게 편지 보내기", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text("체크 해제 시 매칭 없이 기록만 남겨요.", color = SubtleInk, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
                item {
                    Button(
                        onClick = { 
                            viewModel.createLetter(
                                match = matchEnabled,
                                province = selectedProvince,
                                district = selectedDistrict,
                                subDistrict = selectedNeighborhood
                            )
                        },
                        enabled = body.isNotBlank() && uiState !is LetterUiState.Loading,
                        modifier = Modifier.fillMaxWidth().height(58.dp),
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Purple)
                    ) {
                        Icon(Icons.Outlined.Send, null)
                        Text(
                            text = if (matchEnabled) "  편지 보내기" else "  편지 기록하기",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                    }
                }
            }
        }
    }

    if (showLocationSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                expandedLevel = null
                showLocationSheet = false
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
                    IconButton(onClick = { showLocationSheet = false }) {
                        Icon(Icons.Outlined.Close, null, tint = SubtleInk)
                    }
                }
                
                if (expandedLevel == ResidenceLevel.PROVINCE) {
                    ResidenceOptionsAbove(
                        modifier = Modifier.weight(1f),
                        options = provinces,
                        selected = selectedProvince,
                        onSelect = { 
                            selectedProvince = it
                            selectedDistrict = ""
                            selectedNeighborhood = null
                            expandedLevel = null
                        }
                    )
                }
                ResidenceSelectRow("시 · 도", selectedProvince, expandedLevel == ResidenceLevel.PROVINCE) {
                    expandedLevel = if (expandedLevel == ResidenceLevel.PROVINCE) null else ResidenceLevel.PROVINCE
                }

                if (expandedLevel == ResidenceLevel.DISTRICT) {
                    ResidenceOptionsAbove(
                        modifier = Modifier.weight(1f),
                        options = districts,
                        selected = selectedDistrict,
                        onSelect = { 
                            selectedDistrict = it
                            selectedNeighborhood = null
                            expandedLevel = null
                        }
                    )
                }
                ResidenceSelectRow("시 · 군 · 구", selectedDistrict, expandedLevel == ResidenceLevel.DISTRICT) {
                    expandedLevel = if (expandedLevel == ResidenceLevel.DISTRICT) null else ResidenceLevel.DISTRICT
                }

                if (expandedLevel == ResidenceLevel.NEIGHBORHOOD) {
                    ResidenceOptionsAbove(
                        modifier = Modifier.weight(1f),
                        options = listOf("선택 안 함") + subDistricts,
                        selected = selectedNeighborhood ?: "선택 안 함",
                        onSelect = { 
                            selectedNeighborhood = it.takeUnless { it == "선택 안 함" }
                            expandedLevel = null
                        }
                    )
                }
                ResidenceSelectRow("동 · 읍 · 면 (선택)", selectedNeighborhood ?: "선택 안 함", expandedLevel == ResidenceLevel.NEIGHBORHOOD) {
                    expandedLevel = if (expandedLevel == ResidenceLevel.NEIGHBORHOOD) null else ResidenceLevel.NEIGHBORHOOD
                }

                Button(
                    onClick = { showLocationSheet = false },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Purple)
                ) {
                    Text("확인", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (showCompletionDialog) {
        AlertDialog(
            onDismissRequest = { showCompletionDialog = false },
            icon = { Icon(if (matchEnabled) Icons.Outlined.MailOutline else Icons.Outlined.History, null, tint = Purple) },
            title = { Text(if (matchEnabled) "편지가 전해졌어요" else "편지가 저장되었어요") },
            text = { 
                Text(
                    if (matchEnabled) "관심사가 비슷한 익명의 이웃과 연결됐습니다. 편지는 첫 메시지로 전달되며 바로 대화를 시작할 수 있어요."
                    else "편지가 전송되지 않고 내 보관함에 안전하게 저장되었습니다."
                ) 
            },
            confirmButton = {
                Button(onClick = { 
                    showCompletionDialog = false
                    val roomId = matchedChatRoomId
                    if (matchEnabled && roomId != null) onMatched(roomId) else onHistory()
                }) {
                    Text(if (matchEnabled) "대화방으로 가기" else "보관함으로 가기")
                }
            },
            dismissButton = { 
                TextButton(onClick = { 
                    showCompletionDialog = false
                    onHistory() 
                }) { 
                    Text("닫기") 
                } 
            }
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
    loadLetter: suspend (String) -> Result<Letter>,
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
    val operationError by viewModel.operationError.collectAsState()
    
    val currentProfile = (uiState as? ProfileUiState.Success)?.profile

    var nickname by remember { mutableStateOf(currentProfile?.nickname.orEmpty()) }
    var intro by remember { mutableStateOf(currentProfile?.bio ?: "") }
    
    // 지역 정보 파싱 (기존 로직 유지하되 서버 데이터 우선)
    val serverLocation = currentProfile?.let { 
        buildString {
            append(it.region?.province?.name.orEmpty())
            append(" ")
            append(it.region?.district?.name.orEmpty())
            it.region?.subDistrict?.name?.let { sub -> append(" "); append(sub) }
        }
    }.orEmpty()

    val initialParts = remember(serverLocation) { serverLocation.split(" ") }
    
    var location by remember(serverLocation) { mutableStateOf(serverLocation) }
    var showLocation by remember { mutableStateOf(false) }
    var selectedProvince by remember(serverLocation) { mutableStateOf(initialParts.getOrNull(0).orEmpty()) }
    var selectedDistrict by remember(serverLocation) { mutableStateOf(initialParts.getOrNull(1).orEmpty()) }
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
                                    province = selectedProvince,
                                    district = selectedDistrict,
                                    subDistrict = selectedNeighborhood,
                                    onSuccess = onBack,
                                )
                            },
                            color = Purple,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                if (operationError != null) {
                    item {
                        Text(
                            operationError.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                        )
                    }
                }
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        androidx.compose.foundation.Image(
                            painter = painterResource(R.drawable.profile_avatar),
                            contentDescription = "프로필 사진",
                            modifier = Modifier.size(108.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                        Text("따뜻한 이웃", color = SubtleInk, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                item {
                    Text("닉네임", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = nickname,
                        onValueChange = { nickname = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                item {
                    Text("소개", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = intro,
                        onValueChange = { intro = it },
                        modifier = Modifier.fillMaxWidth().height(110.dp)
                    )
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

@Composable
private fun IconBox(icon: ImageVector, onClick: () -> Unit = {}) {
    Surface(
        modifier = Modifier.size(48.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, LineColor)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = SubtleInk, modifier = Modifier.size(22.dp))
        }
    }
}

private fun uriToFile(context: android.content.Context, uri: Uri): File? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val file = File(context.cacheDir, "temp_letter_${System.currentTimeMillis()}.jpg")
        val outputStream = FileOutputStream(file)
        inputStream.copyTo(outputStream)
        inputStream.close()
        outputStream.close()
        file
    } catch (e: Exception) {
        null
    }
}
