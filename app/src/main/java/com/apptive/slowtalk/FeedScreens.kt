package com.apptive.slowtalk

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.SubdirectoryArrowRight
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    feeds: List<FeedPost>,
    onOpenFeed: (Int) -> Unit,
    isLiked: (Int) -> Boolean,
    onToggleLike: (Int) -> Unit,
    onWrite: () -> Unit,
    onProfile: () -> Unit,
    onTab: (MainTab) -> Unit,
    showBottomBar: Boolean = true
) {
    var isRefreshing by remember { mutableStateOf(false) }
    val refreshScope = rememberCoroutineScope()

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
                            delay(700)
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
                    items(feeds) { post ->
                        FeedCard(
                            post = post,
                            onOpenFeed = onOpenFeed,
                            isLiked = isLiked(post.id),
                            onToggleLike = { onToggleLike(post.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedCard(
    post: FeedPost,
    onOpenFeed: (Int) -> Unit,
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
                    Surface(
                        modifier = Modifier.size(30.dp),
                        shape = CircleShape,
                        color = post.accent.copy(alpha = 0.15f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Outlined.AutoAwesome,
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
                Text("익명의 이웃", color = SubtleInk, fontSize = 12.sp)
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
                    Text("  댓글 ${post.comments.size}", color = SubtleInk, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
fun WriteFeedScreen(
    onBack: () -> Unit,
    onProfile: () -> Unit,
    onPublish: (String, String, String) -> Unit
) {
    val categories = feedCategoryVisuals
    var category by remember { mutableStateOf(categories.first().name) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }

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
                            "피드 쓰기",
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
                                onValueChange = { title = it.take(60) },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("오늘 어떤 일이 있었나요?") },
                                singleLine = true
                            )
                            Spacer(Modifier.height(14.dp))
                            Text("본문", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = body,
                                onValueChange = { body = it.take(1000) },
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
                                    shape = RoundedCornerShape(12.dp),
                                    color = Purple.copy(alpha = 0.1f)
                                ) {
                                    Text(
                                        "실시간 분석 중",
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                        color = Purple,
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
                                            tint = Color(0xFF4DB77A),
                                            modifier = Modifier.size(21.dp)
                                        )
                                        Column(Modifier.padding(start = 9.dp)) {
                                            Text(
                                                if (body.length < 30) {
                                                    "조금 더 들려주세요"
                                                } else {
                                                    "좋은 흐름이에요!"
                                                },
                                                color = if (body.length < 30) Purple else Color(0xFF2FAE68),
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                if (body.length < 30) {
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
                                                "• 구체적인 순간을 떠올려보세요.\n• 감정을 한 단어로 표현해보세요.",
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
                        onClick = { onPublish(category, title.trim(), body.trim()) },
                        enabled = title.isNotBlank() && body.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Purple)
                    ) {
                        Icon(Icons.Outlined.Send, null)
                        Text("  피드 올리기 (익명)", fontWeight = FontWeight.Bold)
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
    val name: String,
    val icon: ImageVector,
    val tint: Color,
    val background: Color
)

private val feedCategoryVisuals = listOf(
    FeedCategoryVisual("일상 이야기", Icons.Outlined.Eco, Color(0xFF54B978), Color(0xFFEAF7ED)),
    FeedCategoryVisual("취미 생활", Icons.Outlined.Palette, Purple, PurpleSoft),
    FeedCategoryVisual("마음과 고민", Icons.Outlined.FavoriteBorder, Color(0xFFE76E91), Color(0xFFFFEFF3)),
    FeedCategoryVisual("배움과 성장", Icons.Outlined.School, Color(0xFF5C95E8), Color(0xFFEDF4FF)),
    FeedCategoryVisual("여행과 경험", Icons.Outlined.Flight, Color(0xFF3DBCC1), Color(0xFFEAF9F9)),
    FeedCategoryVisual("기타", Icons.Outlined.MoreHoriz, SubtleInk, Color(0xFFF4F1ED))
)

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
    onToggleLike: () -> Unit,
    onBack: () -> Unit
) {
    var comment by remember { mutableStateOf("") }
    var comments by remember { mutableStateOf(post.comments.toList()) }
    var replyTarget by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }
    val commentFocusRequester = remember { FocusRequester() }
    val refreshScope = rememberCoroutineScope()

    LaunchedEffect(replyTarget) {
        if (replyTarget != null) {
            commentFocusRequester.requestFocus()
        }
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
                                        val updatedComments = if (targetIndex == null) {
                                            comments + Comment("나", message, "지금", true)
                                        } else {
                                            comments.mapIndexed { index, item ->
                                                if (index == targetIndex) {
                                                    item.copy(
                                                        replies = item.replies + Comment(
                                                            author = "글쓴이",
                                                            message = message,
                                                            time = "지금",
                                                            isMine = true
                                                        )
                                                    )
                                                } else {
                                                    item
                                                }
                                            }
                                        }
                                        comments = updatedComments
                                        post.comments.clear()
                                        post.comments.addAll(updatedComments)
                                        comment = ""
                                        replyTarget = null
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
                            delay(700)
                            comments = post.comments.toList()
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
                            IconButton(onClick = {}) { Icon(Icons.Outlined.MoreVert, "더보기") }
                        }
                    }
                    item {
                        FeedDetailCard(
                            post = post,
                            commentCount = comments.size,
                            isLiked = isLiked,
                            onToggleLike = onToggleLike
                        )
                    }
                    item {
                        Text(
                            "댓글 ${comments.size}",
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    items(comments.size) { index ->
                        CommentThread(
                            comment = comments[index],
                            onReply = { author -> replyTarget = index to author }
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
    onReply: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CommentCard(
            comment = comment,
            modifier = Modifier.fillMaxWidth(),
            onReply = { onReply(comment.author) }
        )
        comment.replies.forEach { reply ->
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
    isReply: Boolean = false
) {
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
                IconButton(
                    onClick = {},
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = "댓글 더보기",
                        tint = SubtleInk,
                        modifier = Modifier.size(20.dp)
                    )
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
