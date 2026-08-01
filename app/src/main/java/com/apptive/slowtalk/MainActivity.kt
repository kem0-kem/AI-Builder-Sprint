package com.apptive.slowtalk

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apptive.slowtalk.ui.profile.ProfileViewModel
import com.apptive.slowtalk.ui.theme.SlowTalkTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SlowTalkTheme {
                ApptiveApp()
            }
        }
    }
}

@Composable
private fun ApptiveApp() {
    val context = LocalContext.current
    val activity = context as? Activity
    val appScope = rememberCoroutineScope()
    val profileViewModel: ProfileViewModel = viewModel()
    var screen by remember { mutableStateOf<Screen>(Screen.Feed) }
    var profileReturnScreen by remember { mutableStateOf<Screen>(Screen.Feed) }
    var lastBackPressTime by remember { mutableLongStateOf(0L) }
    val likedFeeds = remember { mutableStateMapOf<Int, Boolean>() }
    val likingFeeds = remember { mutableStateMapOf<Int, Boolean>() }
    val feeds = remember {
        mutableStateListOf(
            FeedPost(
                1,
                "일상 이야기",
                "오늘은 조금 천천히 걸어봤어요",
                "매일 지나치던 길인데 천천히 걸으니 보이지 않던 풍경들이 눈에 들어왔어요.",
                mutableListOf(
                    Comment(
                        "익명3",
                        "저도 도저히 머리가 안 돌아갈 땐 산책을 즐겨해요",
                        "12:42",
                        replies = listOf(
                            Comment("글쓴이", "그럴 때 산책이 정말 큰 도움이 되더라고요", "17:58", true)
                        )
                    )
                ),
                Purple
            ),
            FeedPost(
                2,
                "마음과 고민",
                "새로운 시작이 조금 두렵습니다",
                "기대되는 마음도 있지만 잘할 수 있을지 걱정돼요. 여러분은 시작 앞에서 어떤가요?",
                mutableListOf(
                    Comment("익명1", "새로운 시작은 누구에게나 떨리는 것 같아요.", "어제"),
                    Comment("익명2", "천천히 해도 괜찮아요.", "어제"),
                    Comment("익명4", "응원할게요!", "어제"),
                    Comment("글쓴이", "따뜻한 말 고마워요.", "어제", true)
                ),
                Color(0xFFEC7168)
            ),
            FeedPost(
                3,
                "취미 생활",
                "요즘 그림을 배우고 있어요",
                "잘 그리는 것보다 내 마음을 천천히 표현하는 시간이 좋아서 계속해 보려고 합니다.",
                mutableListOf(Comment("익명2", "멋진 취미네요. 오래 이어가길 바라요.", "2일 전")),
                Color(0xFF8A70D8)
            )
        )
    }
    val anonymousConversations = remember {
        listOf(
            Conversation("익명의 이웃 01", "오늘 하루는 어떻게 보내셨나요?", "방금 전", unread = true),
            Conversation("익명의 이웃 02", "저도 그런 하루를 보낸 적이 있어요.", "어제"),
            Conversation("익명의 이웃 03", "당신의 이야기를 들려줘서 고마워요.", "3일 전")
        )
    }
    val groupConversations = remember {
        listOf(
            Conversation("저녁 산책 모임", "이번 주 토요일 저녁 7시 어떠세요?", "방금 전", true, isGroup = true, members = 5),
            Conversation("그림 초보 모임", "준비물은 연필과 작은 스케치북이에요.", "어제", isGroup = true, members = 8),
            Conversation("함께 읽는 독서 모임", "다음 책은 투표로 정해봐요.", "2일 전", isGroup = true, members = 6),
            Conversation("동네 카페 탐방", "이번에는 조용한 카페로 가요.", "3일 전", isGroup = true, members = 4)
        )
    }
    val letters = remember {
        listOf(
            Letter("천천히 걸었던 하루", "오늘은 평소보다 조금 느리게 걸어봤어요.", "2026.07.22 · 15:40", true),
            Letter("안녕하세요, 반가워요", "용기내어 편지를 써요.", "2026.07.21 · 21:10", false),
            Letter("좋아하는 것들", "저는 책 읽는 시간을 정말 좋아해요.", "2026.07.20 · 18:25", true),
            Letter("주말 잘 보내셨나요?", "저는 오늘 따뜻한 커피 한 잔을 마셨어요.", "2026.07.19 · 14:05", false)
        )
    }
    val mainPagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })
    val toggleFeedLike: (Int) -> Unit = { feedId ->
        if (likingFeeds[feedId] != true) {
            val wasLiked = likedFeeds[feedId] == true
            val requestedLike = !wasLiked
            likedFeeds[feedId] = requestedLike
            likingFeeds[feedId] = true
            appScope.launch {
                FeedApi.setFeedLiked(feedId, requestedLike)
                    .onSuccess { serverLiked -> likedFeeds[feedId] = serverLiked }
                    .onFailure {
                        likedFeeds[feedId] = wasLiked
                        Toast.makeText(
                            context,
                            if (requestedLike) "공감하지 못했습니다." else "공감을 취소하지 못했습니다.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                likingFeeds.remove(feedId)
            }
        }
    }

    LaunchedEffect(mainPagerState.settledPage) {
        if (screen.isMainTab()) {
            screen = mainPagerState.settledPage.toMainScreen()
        }
    }

    LaunchedEffect(screen) {
        val targetPage = screen.mainPageIndex()
        if (targetPage != null && mainPagerState.currentPage != targetPage) {
            mainPagerState.animateScrollToPage(targetPage)
        }
    }

    BackHandler {
        if (screen.isMainTab()) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastBackPressTime <= 2_000L) {
                activity?.finish()
            } else {
                lastBackPressTime = now
                Toast.makeText(context, "뒤로가기를 한 번 더 누르면 종료됩니다.", Toast.LENGTH_SHORT).show()
            }
        } else {
            screen = when (screen) {
                is Screen.FeedDetail, is Screen.EditFeed, Screen.WriteFeed -> Screen.Feed
                is Screen.Chat, Screen.CreateGroup -> Screen.Conversations
                Screen.WriteLetter, Screen.LetterHistory -> Screen.LetterHome
                is Screen.LetterDetail -> Screen.LetterHistory
                Screen.WriteReflection, is Screen.ReflectionDetail -> Screen.LetterHome
                Screen.Profile -> profileReturnScreen
                Screen.EditProfile -> Screen.Profile
                Screen.Interests -> Screen.Profile
                else -> Screen.Feed
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .safeDrawingPadding()
    ) {
        when (val current = screen) {
            Screen.Feed, Screen.Conversations, Screen.LetterHome -> Scaffold(
                containerColor = Color.Transparent,
                bottomBar = {
                    AppBottomBar(
                        selected = when (current) {
                            Screen.Conversations -> MainTab.CONVERSATIONS
                            Screen.LetterHome -> MainTab.LETTER
                            else -> MainTab.FEED
                        },
                        onSelect = { screen = it.toScreen() },
                        onSelectedFeedClick = { screen = Screen.WriteFeed },
                        onSelectedLetterClick = { screen = Screen.LetterHistory }
                    )
                }
            ) { mainPadding ->
                HorizontalPager(
                    state = mainPagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(mainPadding)
                ) { page ->
                    when (page) {
                        0 -> ConversationListScreen(
                            anonymous = anonymousConversations,
                            groups = groupConversations,
                            loadRooms = { ChatApi.getRooms() },
                            onOpen = {
                                screen = Screen.Chat(it.title, it.isGroup, it.chatRoomId)
                            },
                            onCreateGroup = { screen = Screen.CreateGroup },
                            onProfile = {
                                profileReturnScreen = Screen.Conversations
                                screen = Screen.Profile
                            },
                            onTab = { screen = it.toScreen() },
                            showBottomBar = false
                        )
                        1 -> FeedScreen(
                            feeds = feeds,
                            onOpenFeed = { screen = Screen.FeedDetail(it) },
                            isLiked = { likedFeeds[it] == true },
                            onToggleLike = toggleFeedLike,
                            loadMyFeeds = { FeedApi.getMyFeeds() },
                            onMyFeedsLoaded = { remoteFeeds ->
                                feeds.removeAll { it.isMine }
                                feeds.addAll(remoteFeeds.map { it.post })
                                remoteFeeds.forEach { item ->
                                    likedFeeds[item.post.id] = item.liked
                                }
                            },
                            onWrite = { screen = Screen.WriteFeed },
                            onProfile = {
                                profileReturnScreen = Screen.Feed
                                screen = Screen.Profile
                            },
                            onTab = { screen = it.toScreen() },
                            showBottomBar = false
                        )
                        else -> LetterHomeScreen(
                            onWrite = { screen = Screen.WriteLetter },
                            onHistory = { screen = Screen.LetterHistory },
                            onReflection = { screen = Screen.WriteReflection },
                            onProfile = {
                                profileReturnScreen = Screen.LetterHome
                                screen = Screen.Profile
                            },
                            onTab = { screen = it.toScreen() },
                            showBottomBar = false
                        )
                    }
                }
            }
            Screen.WriteFeed -> WriteFeedScreen(
                onBack = { screen = Screen.Feed },
                onProfile = {
                    profileReturnScreen = Screen.WriteFeed
                    screen = Screen.Profile
                },
                loadCategories = { FeedApi.getFeedCategories() },
                requestFeedback = { title, body -> FeedApi.getFeedFeedback(title, body) },
                onSubmit = { categoryId, _, title, body ->
                    FeedApi.createFeed(categoryId, title, body)
                },
                onSuccess = { feedId, category, title, body ->
                    feeds.add(
                        0,
                        FeedPost(
                            id = feedId,
                            category = category,
                            title = title,
                            body = body,
                            accent = Purple,
                            isMine = true
                        )
                    )
                    screen = Screen.Feed
                }
            )
            is Screen.EditFeed -> {
                val post = feeds.firstOrNull { it.id == current.feedId }
                if (post == null) {
                    screen = Screen.Feed
                } else {
                    WriteFeedScreen(
                        initialPost = post,
                        onBack = { screen = Screen.FeedDetail(post.id) },
                        onProfile = {
                            profileReturnScreen = current
                            screen = Screen.Profile
                        },
                        loadCategories = { FeedApi.getFeedCategories() },
                        requestFeedback = { title, body -> FeedApi.getFeedFeedback(title, body) },
                        onSubmit = { categoryId, _, title, body ->
                            FeedApi.updateFeed(
                                feedId = post.id,
                                categoryId = categoryId,
                                title = title,
                                content = body
                            ).map { post.id }
                        },
                        onSuccess = { _, category, title, body ->
                            val index = feeds.indexOfFirst { it.id == post.id }
                            if (index >= 0) {
                                feeds[index] = post.copy(
                                    category = category,
                                    title = title,
                                    body = body
                                )
                            }
                            screen = Screen.FeedDetail(post.id)
                        }
                    )
                }
            }
            is Screen.FeedDetail -> {
                val post = feeds.first { it.id == current.feedId }
                FeedDetailScreen(
                    post = post,
                    isLiked = likedFeeds[post.id] == true,
                    loadFeed = { feedId -> FeedApi.getFeedDetail(feedId) },
                    onFeedLoaded = { result ->
                        val index = feeds.indexOfFirst { it.id == result.post.id }
                        if (index >= 0) feeds[index] = result.post
                        likedFeeds[result.post.id] = result.liked
                    },
                    onToggleLike = { toggleFeedLike(post.id) },
                    onEdit = { screen = Screen.EditFeed(post.id) },
                    onDelete = {
                        feeds.removeAll { it.id == post.id }
                        likedFeeds.remove(post.id)
                        screen = Screen.Feed
                        Toast.makeText(context, "피드가 삭제되었습니다.", Toast.LENGTH_SHORT).show()
                        if (FeedApi.isConfigured) {
                            appScope.launch {
                                FeedApi.deleteFeed(post.id).onFailure {
                                    Toast.makeText(
                                        context,
                                        "서버에서 피드를 삭제하지 못했습니다.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    },
                    onReport = {
                        Toast.makeText(context, "신고가 접수되었습니다.", Toast.LENGTH_SHORT).show()
                        if (FeedApi.isConfigured) {
                            appScope.launch {
                                FeedApi.reportFeed(post.id).onFailure {
                                    Toast.makeText(
                                        context,
                                        "신고를 서버에 전송하지 못했습니다.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    },
                    onBack = { screen = Screen.Feed }
                )
            }
            is Screen.Chat -> ChatScreen(
                title = current.title,
                isGroup = current.isGroup,
                chatRoomId = current.chatRoomId,
                onBack = { screen = Screen.Conversations }
            )
            Screen.CreateGroup -> CreateGroupScreen(
                availablePeople = anonymousConversations,
                loadInviteUsers = { keyword -> MeetingApi.getInviteUsers(keyword) },
                createMeeting = { title, description, inviteUserIds ->
                    MeetingApi.createMeeting(title, description, inviteUserIds)
                },
                onBack = { screen = Screen.Conversations },
                onCreated = { title, chatRoomId ->
                    screen = Screen.Chat(title, isGroup = true, chatRoomId = chatRoomId)
                }
            )
            Screen.WriteLetter -> WriteLetterScreen(
                onHistory = { screen = Screen.LetterHistory },
                onMatched = { screen = Screen.Chat("익명의 이웃 05") },
                onTab = { screen = it.toScreen() }
            )
            Screen.LetterHistory -> LetterHistoryScreen(
                letters = letters,
                loadLetters = { type -> LetterApi.getLetters(type) },
                onBack = { screen = Screen.LetterHome },
                onOpen = { screen = Screen.LetterDetail(it.id, it.title) }
            )
            is Screen.LetterDetail -> LetterDetailScreen(
                letter = letters.firstOrNull {
                    (current.letterId != null && it.id == current.letterId) || it.title == current.title
                } ?: Letter(
                    title = current.title,
                    preview = "편지 내용을 불러오고 있어요.",
                    date = "",
                    received = false,
                    id = current.letterId,
                    content = ""
                ),
                loadLetter = { letterId -> LetterApi.getLetter(letterId) },
                onBack = { screen = Screen.LetterHistory }
            )
            Screen.WriteReflection -> WriteReflectionScreen(
                onBack = { screen = Screen.LetterHome },
                onFinish = { screen = Screen.ReflectionDetail("천천히 걸었던 하루") },
                onProfile = {
                    profileReturnScreen = Screen.WriteReflection
                    screen = Screen.Profile
                }
            )
            is Screen.ReflectionDetail -> ReflectionDetailScreen(
                title = current.title,
                onBack = { screen = Screen.LetterHome },
                onProfile = {
                    profileReturnScreen = current
                    screen = Screen.Profile
                }
            )
            Screen.Profile -> ProfileOverviewScreen(
                viewModel = profileViewModel,
                onBack = { screen = profileReturnScreen },
                onEdit = { screen = Screen.EditProfile },
                onInterests = { screen = Screen.Interests }
            )
            Screen.EditProfile -> ProfileEditScreen(
                viewModel = profileViewModel,
                onBack = { screen = Screen.Profile }
            )
            Screen.Interests -> InterestSettingScreen(
                viewModel = profileViewModel,
                onBack = { screen = Screen.Profile },
                onComplete = { screen = Screen.Profile }
            )
        }
    }
}

private fun MainTab.toScreen(): Screen = when (this) {
    MainTab.CONVERSATIONS -> Screen.Conversations
    MainTab.FEED -> Screen.Feed
    MainTab.LETTER -> Screen.LetterHome
}

private fun Screen.isMainTab(): Boolean =
    this is Screen.Conversations || this is Screen.Feed || this is Screen.LetterHome

private fun Screen.mainPageIndex(): Int? = when (this) {
    Screen.Conversations -> 0
    Screen.Feed -> 1
    Screen.LetterHome -> 2
    else -> null
}

private fun Int.toMainScreen(): Screen = when (this) {
    0 -> Screen.Conversations
    1 -> Screen.Feed
    else -> Screen.LetterHome
}
