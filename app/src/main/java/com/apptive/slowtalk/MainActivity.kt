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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.apptive.slowtalk.ui.theme.SlowTalkTheme

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
    var screen by remember { mutableStateOf<Screen>(Screen.Feed) }
    var profileReturnScreen by remember { mutableStateOf<Screen>(Screen.Feed) }
    var lastBackPressTime by remember { mutableLongStateOf(0L) }
    var profileLocation by remember { mutableStateOf("서울 마포구") }
    val likedFeeds = remember { mutableStateMapOf<Int, Boolean>() }
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
                is Screen.FeedDetail, Screen.WriteFeed -> Screen.Feed
                is Screen.Chat, Screen.CreateGroup -> Screen.Conversations
                Screen.WriteLetter, Screen.LetterHistory -> Screen.LetterHome
                is Screen.LetterDetail -> Screen.LetterHistory
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
                            onOpen = { screen = Screen.Chat(it.title, it.isGroup) },
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
                            onToggleLike = { id ->
                                likedFeeds[id] = likedFeeds[id] != true
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
                onPublish = { category, title, body ->
                    feeds.add(
                        0,
                        FeedPost(
                            id = (feeds.maxOfOrNull { it.id } ?: 0) + 1,
                            category = category,
                            title = title,
                            body = body,
                            accent = Purple
                        )
                    )
                    screen = Screen.Feed
                }
            )
            is Screen.FeedDetail -> {
                val post = feeds.first { it.id == current.feedId }
                FeedDetailScreen(
                    post = post,
                    isLiked = likedFeeds[post.id] == true,
                    onToggleLike = {
                        likedFeeds[post.id] = likedFeeds[post.id] != true
                    },
                    onBack = { screen = Screen.Feed }
                )
            }
            is Screen.Chat -> ChatScreen(
                title = current.title,
                isGroup = current.isGroup,
                onBack = { screen = Screen.Conversations }
            )
            Screen.CreateGroup -> CreateGroupScreen(
                availablePeople = anonymousConversations,
                onBack = { screen = Screen.Conversations },
                onCreate = { title -> screen = Screen.Chat(title, isGroup = true) }
            )
            Screen.WriteLetter -> WriteLetterScreen(
                onHistory = { screen = Screen.LetterHistory },
                onMatched = { screen = Screen.Chat("익명의 이웃 05") },
                onTab = { screen = it.toScreen() }
            )
            Screen.LetterHistory -> LetterHistoryScreen(
                letters = letters,
                onBack = { screen = Screen.LetterHome },
                onOpen = { screen = Screen.LetterDetail(it.title) }
            )
            is Screen.LetterDetail -> LetterDetailScreen(
                letter = letters.firstOrNull { it.title == current.title } ?: letters.first(),
                onBack = { screen = Screen.LetterHistory }
            )
            Screen.Profile -> ProfileOverviewScreen(
                location = profileLocation,
                onBack = { screen = profileReturnScreen },
                onEdit = { screen = Screen.EditProfile },
                onInterests = { screen = Screen.Interests }
            )
            Screen.EditProfile -> ProfileEditScreen(
                initialLocation = profileLocation,
                onLocationChange = { profileLocation = it },
                onBack = { screen = Screen.Profile }
            )
            Screen.Interests -> InterestSettingScreen(
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
