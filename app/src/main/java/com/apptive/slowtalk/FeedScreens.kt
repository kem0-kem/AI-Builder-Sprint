package com.apptive.slowtalk

import android.widget.Toast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.SubdirectoryArrowRight
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class FeedIndex { ALL, MINE }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FeedScreen(
    allFeeds: List<FeedPost>,
    myFeeds: List<FeedPost>,
    onOpenFeed: (String) -> Unit,
    isLiked: (String) -> Boolean,
    onToggleLike: (String) -> Unit,
    loadFeeds: suspend (String?) -> Result<FeedPageResult>,
    onFeedsLoaded: (List<MyFeedResult>, Boolean) -> Unit,
    loadMyFeeds: suspend (String?) -> Result<FeedPageResult>,
    onMyFeedsLoaded: (List<MyFeedResult>, Boolean) -> Unit,
    onWrite: () -> Unit,
    onProfile: () -> Unit,
    onTab: (MainTab) -> Unit,
    showBottomBar: Boolean = true
) {
    val context = LocalContext.current
    var isRefreshing by remember { mutableStateOf(false) }
    var selectedIndex by remember { mutableStateOf(FeedIndex.ALL) }
    var isAllFeedsLoading by remember { mutableStateOf(false) }
    var allFeedsLoadFailed by remember { mutableStateOf(false) }
    var isMyFeedsLoading by remember { mutableStateOf(false) }
    var myFeedsLoadFailed by remember { mutableStateOf(false) }
    var allNextCursor by remember { mutableStateOf<String?>(null) }
    var myNextCursor by remember { mutableStateOf<String?>(null) }
    val refreshScope = rememberCoroutineScope()
    val visibleFeeds = when (selectedIndex) {
        FeedIndex.ALL -> allFeeds
        FeedIndex.MINE -> myFeeds
    }

    suspend fun refreshAllFeeds() {
        isAllFeedsLoading = true
        loadFeeds(null).fold(
            onSuccess = { page ->
                onFeedsLoaded(page.items, false)
                allNextCursor = page.nextCursor
                allFeedsLoadFailed = false
            },
            onFailure = {
                allFeedsLoadFailed = true
                Toast.makeText(context, "전체 피드를 불러오지 못했습니다. 아래로 당겨 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
            }
        )
        isAllFeedsLoading = false
    }

    suspend fun refreshMyFeeds() {
        isMyFeedsLoading = true
        loadMyFeeds(null).fold(
            onSuccess = { page ->
                onMyFeedsLoaded(page.items, false)
                myNextCursor = page.nextCursor
                myFeedsLoadFailed = false
            },
            onFailure = {
                myFeedsLoadFailed = true
                Toast.makeText(context, "내 피드를 불러오지 못했습니다. 아래로 당겨 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
            }
        )
        isMyFeedsLoading = false
    }

    LaunchedEffect(Unit) {
        refreshAllFeeds()
    }

    LaunchedEffect(selectedIndex) {
        if (selectedIndex == FeedIndex.MINE) refreshMyFeeds()
    }

    PaperBackground {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                if (showBottomBar) {
                    AppBottomBar(
                        selected = MainTab.FEED,
                        onSelect = onTab,
                        onSelectedFeedClick = onWrite
                    )
                }
            }
        ) { padding ->
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    if (!isRefreshing) {
                        refreshScope.launch {
                            isRefreshing = true
                            if (selectedIndex == FeedIndex.MINE) {
                                refreshMyFeeds()
                            } else {
                                refreshAllFeeds()
                            }
                            isRefreshing = false
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 14.dp)
                ) {
                    item {
                        PageHeader("피드", "서로의 이야기를 천천히 만나보세요.", onProfile)
                    }
                    stickyHeader {
                        FeedIndexSelector(
                            selected = selectedIndex,
                            onSelected = { selectedIndex = it }
                        )
                    }
                    if (visibleFeeds.isEmpty()) {
                        item {
                            EmptyFeedMessage(
                                mine = selectedIndex == FeedIndex.MINE,
                                loading = if (selectedIndex == FeedIndex.MINE) isMyFeedsLoading else isAllFeedsLoading,
                                loadFailed = if (selectedIndex == FeedIndex.MINE) myFeedsLoadFailed else allFeedsLoadFailed,
                                onRetry = {
                                    refreshScope.launch {
                                        if (selectedIndex == FeedIndex.MINE) refreshMyFeeds()
                                        else refreshAllFeeds()
                                    }
                                }
                            )
                        }
                    }
                    items(visibleFeeds, key = { it.id }) { post ->
                        FeedCard(
                            post = post,
                            onOpenFeed = onOpenFeed,
                            isLiked = isLiked(post.id),
                            onToggleLike = { onToggleLike(post.id) }
                        )
                    }
                    val nextCursor = if (selectedIndex == FeedIndex.MINE) myNextCursor else allNextCursor
                    if (nextCursor != null) {
                        item {
                            Button(
                                onClick = {
                                    refreshScope.launch {
                                        if (selectedIndex == FeedIndex.MINE) {
                                            isMyFeedsLoading = true
                                            loadMyFeeds(nextCursor).onSuccess { page ->
                                                onMyFeedsLoaded(page.items, true)
                                                myNextCursor = page.nextCursor
                                            }.onFailure {
                                                myFeedsLoadFailed = true
                                                Toast.makeText(context, "다음 내 피드를 불러오지 못했습니다. 더 보기를 다시 눌러 주세요.", Toast.LENGTH_SHORT).show()
                                            }
                                            isMyFeedsLoading = false
                                        } else {
                                            isAllFeedsLoading = true
                                            loadFeeds(nextCursor).onSuccess { page ->
                                                onFeedsLoaded(page.items, true)
                                                allNextCursor = page.nextCursor
                                            }.onFailure {
                                                allFeedsLoadFailed = true
                                                Toast.makeText(context, "다음 피드를 불러오지 못했습니다. 더 보기를 다시 눌러 주세요.", Toast.LENGTH_SHORT).show()
                                            }
                                            isAllFeedsLoading = false
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 22.dp, vertical = 12.dp),
                                enabled = if (selectedIndex == FeedIndex.MINE) !isMyFeedsLoading else !isAllFeedsLoading,
                                colors = ButtonDefaults.buttonColors(containerColor = Purple),
                            ) {
                                Text("더 보기")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedIndexSelector(
    selected: FeedIndex,
    onSelected: (FeedIndex) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Paper
    ) {
        Surface(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFFF3F0EE)
        ) {
            Row(Modifier.padding(4.dp)) {
                FeedIndex.entries.forEach { index ->
                    val isSelected = selected == index
                    Surface(
                        onClick = { onSelected(index) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(20.dp),
                        color = if (isSelected) BlockSurface else Color.Transparent,
                        border = if (isSelected) {
                            androidx.compose.foundation.BorderStroke(1.dp, LineColor)
                        } else {
                            null
                        },
                        shadowElevation = if (isSelected) 1.dp else 0.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 9.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (index == FeedIndex.MINE) {
                                Icon(
                                    Icons.Outlined.AccountCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(17.dp),
                                    tint = if (isSelected) Purple else SubtleInk
                                )
                                Spacer(Modifier.size(5.dp))
                            }
                            Text(
                                text = if (index == FeedIndex.ALL) "전체 피드" else "내가 쓴 피드",
                                color = if (isSelected) Purple else SubtleInk,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyFeedMessage(
    mine: Boolean,
    loading: Boolean,
    loadFailed: Boolean,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 36.dp, vertical = 54.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(34.dp),
                color = Purple,
                strokeWidth = 3.dp
            )
            Spacer(Modifier.height(14.dp))
            Text(
                if (mine) "내가 쓴 피드를 불러오고 있어요." else "관심사 피드를 불러오고 있어요.",
                color = SubtleInk,
                fontSize = 13.sp
            )
            return@Column
        }
        Surface(
            modifier = Modifier.size(52.dp),
            shape = CircleShape,
            color = PurpleSoft
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = Purple,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            when {
                loadFailed -> "피드를 불러오지 못했어요."
                mine -> "아직 내가 쓴 피드가 없어요."
                else -> "관심사와 연관된 피드가 없습니다."
            },
            color = Ink,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                loadFailed -> "서버 연결을 확인한 뒤 다시 시도해주세요."
                mine -> "피드를 작성하면 이곳에서 모아볼 수 있어요."
                else -> "관심사를 설정하거나 잠시 후 다시 확인해주세요."
            },
            color = SubtleInk,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        if (loadFailed) {
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = Purple),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text("다시 시도")
            }
        }
    }
}

@Composable
private fun FeedCard(
    post: FeedPost,
    onOpenFeed: (String) -> Unit,
    isLiked: Boolean,
    onToggleLike: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 7.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = BlockSurface),
        elevation = CardDefaults.cardElevation(3.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenFeed(post.id) }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val categoryIcon = when (post.category) {
                        "일상 이야기" -> Icons.Outlined.Eco
                        "마음과 고민" -> Icons.Outlined.FavoriteBorder
                        "취미 생활" -> Icons.Outlined.Palette
                        "질문" -> Icons.AutoMirrored.Outlined.HelpOutline
                        else -> Icons.Outlined.AutoAwesome
                    }
                    Surface(
                        modifier = Modifier.size(30.dp),
                        shape = CircleShape,
                        color = post.accent.copy(alpha = 0.15f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                categoryIcon,
                                contentDescription = null,
                                tint = post.accent,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Spacer(Modifier.size(8.dp))
                    Text(post.category, color = post.accent, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text(post.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.height(5.dp))
                Text(post.body, color = SubtleInk, lineHeight = 20.sp, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (post.isMine) "내가 쓴 익명 피드" else "익명의 이웃",
                    color = SubtleInk,
                    fontSize = 12.sp
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = LineColor)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(onClick = onToggleLike)
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = if (isLiked) "공감 취소" else "공감",
                        tint = if (isLiked) Purple else SubtleInk,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        if (isLiked) "  공감했어요" else "  공감",
                        color = if (isLiked) Purple else SubtleInk,
                        fontSize = 13.sp,
                        fontWeight = if (isLiked) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onOpenFeed(post.id) }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.ChatBubbleOutline,
                        contentDescription = "댓글 보기",
                        tint = SubtleInk,
                        modifier = Modifier.size(19.dp)
                    )
                    Text("  댓글 ${post.commentCount}", color = SubtleInk, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
fun WriteFeedScreen(
    onBack: () -> Unit,
    onProfile: () -> Unit,
    initialPost: FeedPost? = null,
    loadCategories: suspend () -> Result<List<FeedCategoryResult>>,
    requestFeedback: suspend (String, String) -> Result<FeedFeedbackResult>,
    onSubmit: suspend (String, String, String, String) -> Result<String>,
    onSuccess: (String, String, String, String, String) -> Unit
) {
    var categories by remember { mutableStateOf(feedCategoryVisuals) }
    var category by remember(initialPost?.id) {
        mutableStateOf(initialPost?.category ?: categories.first().name)
    }
    var title by remember(initialPost?.id) { mutableStateOf(initialPost?.title.orEmpty()) }
    var body by remember(initialPost?.id) { mutableStateOf(initialPost?.body.orEmpty()) }
    var aiFeedback by remember { mutableStateOf<FeedFeedbackResult?>(null) }
    var isFeedbackLoading by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }
    var submitFailed by remember { mutableStateOf(false) }
    val submitScope = rememberCoroutineScope()
    val isEditing = initialPost != null

    LaunchedEffect(initialPost?.id) {
        loadCategories().onSuccess { remoteCategories ->
            if (remoteCategories.isNotEmpty()) {
                categories = remoteCategories.map { it.toVisual() }
                category = categories.firstOrNull { it.id == initialPost?.categoryId }?.name
                    ?: categories.first().name
            }
        }
    }

    PaperBackground {
        Scaffold(containerColor = Color.Transparent) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "뒤로") }
                        Text(
                            if (isEditing) "피드 수정" else "피드 쓰기",
                            modifier = Modifier.weight(1f),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        IconButton(onClick = onProfile) {
                            Icon(
                                Icons.Outlined.AccountCircle,
                                contentDescription = "내 프로필",
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.VerifiedUser,
                            contentDescription = null,
                            tint = SubtleInk,
                            modifier = Modifier.size(19.dp)
                        )
                        Text(
                            "이 피드는 익명으로 작성되어 누구에게 썼는지 알 수 없어요.",
                            color = SubtleInk,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
                item {
                    Card(
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(BlockSurface),
                        elevation = CardDefaults.cardElevation(2.dp)
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Outlined.AutoAwesome,
                                    contentDescription = null,
                                    tint = Purple,
                                    modifier = Modifier.size(22.dp)
                                )
                                Text(
                                    "오늘의 피드",
                                    modifier = Modifier
                                        .padding(start = 9.dp)
                                        .weight(1f),
                                    color = Purple,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "${body.length} / 1,000자",
                                    color = SubtleInk,
                                    fontSize = 11.sp
                                )
                            }
                            Spacer(Modifier.height(14.dp))
                            Text("제목", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = title,
                                onValueChange = {
                                    title = it.take(60)
                                    aiFeedback = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("오늘 어떤 일이 있었나요?") },
                                singleLine = true
                            )
                            Spacer(Modifier.height(14.dp))
                            Text("본문", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = body,
                                onValueChange = {
                                    body = it.take(1000)
                                    aiFeedback = null
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(280.dp),
                                placeholder = {
                                    Text(
                                        "무엇을 느끼고 생각했나요?\n당신의 순간을 자유롭게 나눠주세요.",
                                        color = SubtleInk
                                    )
                                }
                            )
                        }
                    }
                }
                item {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(BlockSurface),
                        elevation = CardDefaults.cardElevation(2.dp)
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Outlined.FolderOpen,
                                    contentDescription = null,
                                    tint = Purple,
                                    modifier = Modifier.size(21.dp)
                                )
                                Text(
                                    "카테고리 선택",
                                    modifier = Modifier
                                        .padding(start = 8.dp)
                                        .weight(1f),
                                    color = Purple,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(category, color = SubtleInk, fontSize = 11.sp)
                            }
                            Spacer(Modifier.height(14.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                categories.forEach { item ->
                                    FeedCategoryOption(
                                        item = item,
                                        selected = category == item.name,
                                        onClick = { category = item.name },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(BlockSurface),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            Purple.copy(alpha = 0.16f)
                        ),
                        elevation = CardDefaults.cardElevation(2.dp)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.AutoAwesome, null, tint = Purple)
                                Text(
                                    "AI 도우미",
                                    modifier = Modifier
                                        .padding(start = 9.dp)
                                        .weight(1f),
                                    color = Purple,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Surface(
                                    modifier = Modifier.clickable(
                                        enabled = title.isNotBlank() &&
                                            body.isNotBlank() &&
                                            !isFeedbackLoading
                                    ) {
                                        val requestedTitle = title.trim()
                                        val requestedBody = body.trim()
                                        submitScope.launch {
                                            isFeedbackLoading = true
                                            requestFeedback(requestedTitle, requestedBody)
                                                .onSuccess {
                                                    if (
                                                        title.trim() == requestedTitle &&
                                                        body.trim() == requestedBody
                                                    ) {
                                                        aiFeedback = it
                                                    }
                                                }
                                            isFeedbackLoading = false
                                        }
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (title.isNotBlank() && body.isNotBlank()) {
                                        Purple.copy(alpha = 0.12f)
                                    } else {
                                        LineColor.copy(alpha = 0.7f)
                                    }
                                ) {
                                    Text(
                                        when {
                                            isFeedbackLoading -> "분석 중..."
                                            aiFeedback != null -> "다시 분석"
                                            else -> "분석하기"
                                        },
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                        color = if (title.isNotBlank() && body.isNotBlank()) Purple else SubtleInk,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                            Text(
                                "더 따뜻하고 진솔한 이야기를 도와드려요.",
                                modifier = Modifier.padding(start = 34.dp, top = 2.dp),
                                color = SubtleInk,
                                fontSize = 12.sp
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 14.dp),
                                color = Purple.copy(alpha = 0.12f)
                            )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Outlined.CheckCircle,
                                            contentDescription = null,
                                            tint = if (aiFeedback?.hasWarning == true) {
                                                Color(0xFFD95C55)
                                            } else {
                                                Color(0xFF4DB77A)
                                            },
                                            modifier = Modifier.size(21.dp)
                                        )
                                        Column(Modifier.padding(start = 9.dp)) {
                                            Text(
                                                aiFeedback?.warningMessage
                                                    ?: if (body.length < 30) {
                                                        "조금 더 들려주세요"
                                                    } else {
                                                        "좋은 흐름이에요!"
                                                    },
                                                color = if (aiFeedback?.hasWarning == true) {
                                                    Color(0xFFD95C55)
                                                } else if (body.length < 30) {
                                                    Purple
                                                } else {
                                                    Color(0xFF2FAE68)
                                                },
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                aiFeedback?.tips?.firstOrNull()
                                                    ?: if (body.length < 30) {
                                                        "구체적인 순간이 더해지면 이야기가 풍성해져요."
                                                    } else {
                                                        "편안하고 자연스러운 글이에요."
                                                    },
                                                color = SubtleInk,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 13.dp),
                                        color = Purple.copy(alpha = 0.12f)
                                    )
                                    Row(modifier = Modifier.fillMaxWidth()) {
                                        Column(Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    Icons.Outlined.Lightbulb,
                                                    contentDescription = null,
                                                    tint = Purple,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Text(
                                                    " 더 풍성하게 쓰는 팁",
                                                    color = Purple,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.sp
                                                )
                                            }
                                            Text(
                                                aiFeedback?.tips
                                                    ?.take(2)
                                                    ?.joinToString("\n") { "• $it" }
                                                    ?.takeIf { it.isNotBlank() }
                                                    ?: "• 구체적인 순간을 떠올려보세요.\n• 감정을 한 단어로 표현해보세요.",
                                                modifier = Modifier.padding(top = 7.dp),
                                                color = SubtleInk,
                                                fontSize = 10.sp,
                                                lineHeight = 16.sp
                                            )
                                        }
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                "✦  예시 질문",
                                                color = Purple,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp
                                            )
                                            Text(
                                                "가장 기억에 남는 순간은?\n그때 어떤 감정을 느꼈나요?",
                                                modifier = Modifier.padding(top = 7.dp),
                                                color = SubtleInk,
                                                fontSize = 10.sp,
                                                lineHeight = 16.sp
                                            )
                                        }
                                    }
                        }
                    }
                }
                item {
                    Button(
                        onClick = {
                            val selectedCategory = categories.first { it.name == category }
                            submitScope.launch {
                                isSubmitting = true
                                submitFailed = false
                                onSubmit(
                                    selectedCategory.id,
                                    category,
                                    title.trim(),
                                    body.trim()
                                ).fold(
                                    onSuccess = { feedId ->
                                        onSuccess(
                                            feedId,
                                            selectedCategory.id,
                                            category,
                                            title.trim(),
                                            body.trim(),
                                        )
                                    },
                                    onFailure = { submitFailed = true }
                                )
                                isSubmitting = false
                            }
                        },
                        enabled = title.isNotBlank() && body.isNotBlank() && !isSubmitting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Purple)
                    ) {
                        Icon(Icons.Outlined.Send, null)
                        Text(
                            when {
                                isSubmitting -> "  전송 중..."
                                isEditing -> "  수정 완료"
                                else -> "  피드 올리기 (익명)"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                if (submitFailed) {
                    item {
                        Text(
                            "피드를 저장하지 못했습니다. 서버 연결을 확인하고 다시 시도해주세요.",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            color = Color(0xFFD95C55),
                            fontSize = 12.sp
                        )
                    }
                }
                item {
                    Text(
                        "작성한 피드는 이후에도 확인할 수 있어요.",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        color = SubtleInk,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

private data class FeedCategoryVisual(
    val id: String,
    val name: String,
    val icon: ImageVector,
    val tint: Color,
    val background: Color
)

private val feedCategoryVisuals = listOf(
    FeedCategoryVisual("00000000-0000-0000-0000-000000000001", "일상 이야기", Icons.Outlined.Eco, Color(0xFF54B978), Color(0xFFEAF7ED)),
    FeedCategoryVisual("00000000-0000-0000-0000-000000000002", "마음과 고민", Icons.Outlined.FavoriteBorder, Color(0xFFE76E91), Color(0xFFFFEFF3)),
    FeedCategoryVisual("00000000-0000-0000-0000-000000000003", "취미 생활", Icons.Outlined.Palette, Purple, PurpleSoft),
    FeedCategoryVisual("00000000-0000-0000-0000-000000000004", "질문", Icons.AutoMirrored.Outlined.HelpOutline, SubtleInk, Color(0xFFF4F1ED))
)

private fun FeedCategoryResult.toVisual(): FeedCategoryVisual = when (name) {
    "일상 이야기" -> FeedCategoryVisual(id, name, Icons.Outlined.Eco, Color(0xFF54B978), Color(0xFFEAF7ED))
    "마음과 고민" -> FeedCategoryVisual(id, name, Icons.Outlined.FavoriteBorder, Color(0xFFE76E91), Color(0xFFFFEFF3))
    "취미 생활" -> FeedCategoryVisual(id, name, Icons.Outlined.Palette, Purple, PurpleSoft)
    else -> FeedCategoryVisual(id, name, Icons.AutoMirrored.Outlined.HelpOutline, SubtleInk, Color(0xFFF4F1ED))
}

@Composable
private fun FeedCategoryOption(
    item: FeedCategoryVisual,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier.size(48.dp),
            shape = CircleShape,
            color = if (selected) item.tint.copy(alpha = 0.2f) else item.background,
            border = if (selected) {
                androidx.compose.foundation.BorderStroke(1.dp, item.tint.copy(alpha = 0.7f))
            } else {
                null
            }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    item.icon,
                    contentDescription = item.name,
                    tint = item.tint,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Text(
            item.name,
            modifier = Modifier.padding(top = 6.dp),
            textAlign = TextAlign.Center,
            color = if (selected) item.tint else Ink,
            fontSize = 9.sp,
            lineHeight = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 2
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedDetailScreen(
    post: FeedPost,
    isLiked: Boolean,
    loadFeed: suspend (String) -> Result<FeedDetailResult>,
    onFeedLoaded: (FeedDetailResult) -> Unit,
    onToggleLike: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReport: () -> Unit,
    onBack: () -> Unit
) {
    var comment by remember { mutableStateOf("") }
    var displayedPost by remember(post.id) { mutableStateOf(post) }
    var comments by remember(post.id) { mutableStateOf(post.comments.toList()) }
    var replyTarget by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    val commentFocusRequester = remember { FocusRequester() }
    val refreshScope = rememberCoroutineScope()
    val context = LocalContext.current

    suspend fun refreshFeed() {
        loadFeed(post.id)
            .onSuccess { result ->
                displayedPost = result.post
                comments = result.post.comments.toList()
                onFeedLoaded(result)
            }
            .onFailure {
                Toast.makeText(context, "피드를 불러오지 못했습니다. 아래로 당겨 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
            }
    }

    LaunchedEffect(post.id) {
        refreshFeed()
    }

    LaunchedEffect(replyTarget) {
        if (replyTarget != null) {
            commentFocusRequester.requestFocus()
        }
    }

    fun commitComments(updated: List<Comment>) {
        comments = updated
        val commentCount = updated.treeCount()
        displayedPost = displayedPost.copy(
            comments = updated.toMutableList(),
            commentCount = commentCount,
        )
        post.comments.clear()
        post.comments.addAll(updated)
        onFeedLoaded(FeedDetailResult(displayedPost, isLiked))
    }

    fun editCommentAt(parentIndex: Int, replyIndex: Int?, content: String) {
        val target = if (replyIndex == null) {
            comments[parentIndex]
        } else {
            comments[parentIndex].replies[replyIndex]
        }
        val updated = comments.mapIndexed { index, parent ->
            if (index != parentIndex) {
                parent
            } else if (replyIndex == null) {
                parent.copy(message = content)
            } else {
                parent.copy(
                    replies = parent.replies.mapIndexed { childIndex, reply ->
                        if (childIndex == replyIndex) reply.copy(message = content) else reply
                    }
                )
            }
        }
        target.id?.let { commentId ->
            refreshScope.launch {
                FeedApi.updateComment(commentId, content)
                    .onSuccess { commitComments(updated) }
                    .onFailure {
                        Toast.makeText(context, "댓글 수정을 서버에 반영하지 못했습니다. 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
                    }
            }
        }
    }

    fun deleteCommentAt(parentIndex: Int, replyIndex: Int?) {
        val target = if (replyIndex == null) {
            comments[parentIndex]
        } else {
            comments[parentIndex].replies[replyIndex]
        }
        val updated = if (replyIndex == null) {
            comments.filterIndexed { index, _ -> index != parentIndex }
        } else {
            comments.mapIndexed { index, parent ->
                if (index == parentIndex) {
                    parent.copy(
                        replies = parent.replies.filterIndexed { childIndex, _ -> childIndex != replyIndex }
                    )
                } else {
                    parent
                }
            }
        }
        target.id?.let { commentId ->
            refreshScope.launch {
                FeedApi.deleteComment(commentId)
                    .onSuccess { commitComments(updated) }
                    .onFailure {
                        Toast.makeText(context, "댓글을 서버에서 삭제하지 못했습니다. 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
                    }
            }
        }
    }

    fun reportCommentAt(parentIndex: Int, replyIndex: Int?) {
        val target = if (replyIndex == null) {
            comments[parentIndex]
        } else {
            comments[parentIndex].replies[replyIndex]
        }
        target.id?.let { commentId ->
            refreshScope.launch {
                FeedApi.reportComment(commentId)
                    .onSuccess {
                        Toast.makeText(context, "댓글 신고가 접수되었습니다.", Toast.LENGTH_SHORT).show()
                    }
                    .onFailure {
                        Toast.makeText(context, "댓글 신고를 서버에 전송하지 못했습니다. 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
                    }
            }
        }
    }

    fun assignCommentId(parentIndex: Int, replyIndex: Int?, commentId: String) {
        val updated = comments.mapIndexed { index, parent ->
            if (index != parentIndex) {
                parent
            } else if (replyIndex == null) {
                parent.copy(id = commentId)
            } else {
                parent.copy(
                    replies = parent.replies.mapIndexed { childIndex, reply ->
                        if (childIndex == replyIndex) reply.copy(id = commentId) else reply
                    }
                )
            }
        }
        commitComments(updated)
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("피드를 삭제할까요?", fontWeight = FontWeight.Bold) },
            text = { Text("삭제한 피드는 다시 복구할 수 없어요.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD95C55))
                ) {
                    Text("삭제")
                }
            },
            dismissButton = {
                Button(
                    onClick = { showDeleteDialog = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = SubtleInk
                    )
                ) {
                    Text("취소")
                }
            }
        )
    }

    if (showReportDialog) {
        AlertDialog(
            onDismissRequest = { showReportDialog = false },
            title = { Text("이 피드를 신고할까요?", fontWeight = FontWeight.Bold) },
            text = { Text("운영 정책에 따라 내용을 확인할 수 있도록 신고가 접수됩니다.") },
            confirmButton = {
                Button(
                    onClick = {
                        showReportDialog = false
                        onReport()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Purple)
                ) {
                    Text("신고")
                }
            },
            dismissButton = {
                Button(
                    onClick = { showReportDialog = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = SubtleInk
                    )
                ) {
                    Text("취소")
                }
            }
        )
    }

    PaperBackground {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .padding(14.dp),
                    shape = RoundedCornerShape(28.dp),
                    shadowElevation = 5.dp,
                    color = BlockSurface
                ) {
                    Column {
                        if (replyTarget != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 18.dp, end = 6.dp, top = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Outlined.SubdirectoryArrowRight,
                                    contentDescription = null,
                                    tint = Purple,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    "${replyTarget?.second.orEmpty()}에게 답글 작성 중",
                                    modifier = Modifier
                                        .padding(start = 6.dp)
                                        .weight(1f),
                                    color = Purple,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                IconButton(onClick = { replyTarget = null }) {
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = "답글 취소",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            HorizontalDivider(color = LineColor)
                        }
                        Row(
                            modifier = Modifier.padding(start = 14.dp, end = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = comment,
                                onValueChange = { comment = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(commentFocusRequester),
                                placeholder = {
                                    Text(
                                        if (replyTarget != null) {
                                            "${replyTarget?.second.orEmpty()}에게 답글을 입력하세요."
                                        } else {
                                            "댓글을 입력하세요."
                                        }
                                    )
                                },
                                singleLine = true
                            )
                            IconButton(
                                onClick = {
                                    val message = comment.trim()
                                    if (message.isNotBlank()) {
                                        val targetIndex = replyTarget?.first
                                        val parentCommentId = targetIndex?.let { comments[it].id }
                                        refreshScope.launch {
                                            FeedApi.createComment(post.id, message, parentCommentId)
                                                .onSuccess { created ->
                                                    val newComment = Comment(
                                                        author = "나",
                                                        message = created.content,
                                                        time = "지금",
                                                        isMine = created.isMine,
                                                        id = created.id,
                                                    )
                                                    val updated = if (targetIndex == null) {
                                                        comments + newComment
                                                    } else {
                                                        comments.mapIndexed { index, item ->
                                                            if (index == targetIndex) {
                                                                item.copy(replies = item.replies + newComment)
                                                            } else item
                                                        }
                                                    }
                                                    commitComments(updated)
                                                    comment = ""
                                                    replyTarget = null
                                                }
                                                .onFailure {
                                                    Toast.makeText(
                                                        context,
                                                        "댓글을 서버에 등록하지 못했습니다.",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.Outlined.Send,
                                    if (replyTarget != null) "답글 전송" else "댓글 전송",
                                    tint = Purple
                                )
                            }
                        }
                    }
                }
            }
        ) { padding ->
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    if (!isRefreshing) {
                        refreshScope.launch {
                            isRefreshing = true
                            refreshFeed()
                            isRefreshing = false
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "뒤로") }
                            Text("피드", fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
                            Spacer(Modifier.weight(1f))
                            Box {
                                IconButton(onClick = { menuExpanded = true }) {
                                    Icon(Icons.Outlined.MoreVert, "더보기")
                                }
                                DropdownMenu(
                                    expanded = menuExpanded,
                                    onDismissRequest = { menuExpanded = false }
                                ) {
                                    if (displayedPost.isMine) {
                                        DropdownMenuItem(
                                            text = { Text("수정") },
                                            leadingIcon = {
                                                Icon(Icons.Outlined.Edit, contentDescription = null)
                                            },
                                            onClick = {
                                                menuExpanded = false
                                                onEdit()
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("삭제", color = Color(0xFFD95C55)) },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Outlined.DeleteOutline,
                                                    contentDescription = null,
                                                    tint = Color(0xFFD95C55)
                                                )
                                            },
                                            onClick = {
                                                menuExpanded = false
                                                showDeleteDialog = true
                                            }
                                        )
                                    } else {
                                        DropdownMenuItem(
                                            text = { Text("신고") },
                                            leadingIcon = {
                                                Icon(Icons.Outlined.Report, contentDescription = null)
                                            },
                                            onClick = {
                                                menuExpanded = false
                                                showReportDialog = true
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item {
                        FeedDetailCard(
                            post = displayedPost,
                            commentCount = displayedPost.commentCount,
                            isLiked = isLiked,
                            onToggleLike = onToggleLike
                        )
                    }
                    item {
                        Text(
                            "댓글 ${displayedPost.commentCount}",
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    items(comments.size) { index ->
                        CommentThread(
                            comment = comments[index],
                            onReply = { author -> replyTarget = index to author },
                            onEditRoot = { content -> editCommentAt(index, null, content) },
                            onDeleteRoot = { deleteCommentAt(index, null) },
                            onReportRoot = { reportCommentAt(index, null) },
                            onEditReply = { replyIndex, content ->
                                editCommentAt(index, replyIndex, content)
                            },
                            onDeleteReply = { replyIndex -> deleteCommentAt(index, replyIndex) },
                            onReportReply = { replyIndex -> reportCommentAt(index, replyIndex) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentThread(
    comment: Comment,
    onReply: (String) -> Unit,
    onEditRoot: (String) -> Unit,
    onDeleteRoot: () -> Unit,
    onReportRoot: () -> Unit,
    onEditReply: (Int, String) -> Unit,
    onDeleteReply: (Int) -> Unit,
    onReportReply: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CommentCard(
            comment = comment,
            modifier = Modifier.fillMaxWidth(),
            onReply = { onReply(comment.author) },
            onEdit = onEditRoot,
            onDelete = onDeleteRoot,
            onReport = onReportRoot
        )
        comment.replies.forEachIndexed { replyIndex, reply ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    Icons.Outlined.SubdirectoryArrowRight,
                    contentDescription = null,
                    tint = SubtleInk,
                    modifier = Modifier
                        .padding(top = 13.dp, end = 5.dp)
                        .size(22.dp)
                )
                CommentCard(
                    comment = reply,
                    modifier = Modifier.weight(1f),
                    onReply = { onReply(reply.author) },
                    onEdit = { content -> onEditReply(replyIndex, content) },
                    onDelete = { onDeleteReply(replyIndex) },
                    onReport = { onReportReply(replyIndex) },
                    isReply = true
                )
            }
        }
    }
}

@Composable
private fun CommentCard(
    comment: Comment,
    modifier: Modifier,
    onReply: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: () -> Unit,
    onReport: () -> Unit,
    isReply: Boolean = false
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    var editedContent by remember(comment.message) { mutableStateOf(comment.message) }

    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("댓글 수정", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = editedContent,
                    onValueChange = { editedContent = it.take(500) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val content = editedContent.trim()
                        if (content.isNotEmpty()) {
                            showEditDialog = false
                            onEdit(content)
                        }
                    },
                    enabled = editedContent.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Purple)
                ) {
                    Text("수정 완료")
                }
            },
            dismissButton = {
                Button(
                    onClick = { showEditDialog = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = SubtleInk
                    )
                ) {
                    Text("취소")
                }
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("댓글을 삭제할까요?", fontWeight = FontWeight.Bold) },
            text = { Text("삭제한 댓글은 다시 복구할 수 없어요.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD95C55))
                ) {
                    Text("삭제")
                }
            },
            dismissButton = {
                Button(
                    onClick = { showDeleteDialog = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = SubtleInk
                    )
                ) {
                    Text("취소")
                }
            }
        )
    }

    if (showReportDialog) {
        AlertDialog(
            onDismissRequest = { showReportDialog = false },
            title = { Text("이 댓글을 신고할까요?", fontWeight = FontWeight.Bold) },
            text = { Text("운영 정책에 따라 확인할 수 있도록 신고가 접수됩니다.") },
            confirmButton = {
                Button(
                    onClick = {
                        showReportDialog = false
                        onReport()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Purple)
                ) {
                    Text("신고")
                }
            },
            dismissButton = {
                Button(
                    onClick = { showReportDialog = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = SubtleInk
                    )
                ) {
                    Text("취소")
                }
            }
        )
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(
            if (comment.isMine || isReply) PurpleSoft else BlockSurface
        ),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    comment.author,
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Bold
                )
                IconButton(
                    onClick = onReply,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        Icons.Outlined.ChatBubbleOutline,
                        contentDescription = "${comment.author}에게 답글",
                        tint = SubtleInk,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            Icons.Outlined.MoreVert,
                            contentDescription = "댓글 더보기",
                            tint = SubtleInk,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        if (comment.isMine) {
                            DropdownMenuItem(
                                text = { Text("수정") },
                                leadingIcon = {
                                    Icon(Icons.Outlined.Edit, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    editedContent = comment.message
                                    showEditDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("삭제", color = Color(0xFFD95C55)) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.DeleteOutline,
                                        contentDescription = null,
                                        tint = Color(0xFFD95C55)
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    showDeleteDialog = true
                                }
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("신고") },
                                leadingIcon = {
                                    Icon(Icons.Outlined.Report, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    showReportDialog = true
                                }
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(comment.message, lineHeight = 21.sp)
            Spacer(Modifier.height(7.dp))
            Text(comment.time, color = SubtleInk, fontSize = 12.sp)
        }
    }
}

@Composable
private fun FeedDetailCard(
    post: FeedPost,
    commentCount: Int,
    isLiked: Boolean,
    onToggleLike: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(BlockSurface),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("익명", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
            Spacer(Modifier.height(18.dp))
            Text(post.category, color = post.accent, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Text(post.title, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(10.dp))
            Text(post.body, fontSize = 16.sp, lineHeight = 25.sp)
            HorizontalDivider(Modifier.padding(vertical = 18.dp), color = LineColor)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(onClick = onToggleLike)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = if (isLiked) "공감 취소" else "공감",
                        tint = if (isLiked) Purple else Ink,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        if (isLiked) "  공감했어요" else "  공감",
                        color = if (isLiked) Purple else Ink,
                        fontWeight = FontWeight.Medium
                    )
                }
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.ChatBubbleOutline, null, modifier = Modifier.size(20.dp))
                    Text("  댓글 $commentCount", fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
