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
import androidx.compose.runtime.collectAsState
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
import com.apptive.slowtalk.ui.auth.AuthViewModel
import com.apptive.slowtalk.data.auth.AuthSession
import com.apptive.slowtalk.ui.letter.LetterViewModel
import com.apptive.slowtalk.ui.profile.ProfileViewModel
import com.apptive.slowtalk.ui.reflection.ReflectionViewModel
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

internal fun mergeScopedFeedPage(
    existing: List<FeedPost>,
    incoming: List<MyFeedResult>,
    append: Boolean,
): List<FeedPost> {
    val base = if (append) existing else emptyList()
    val incomingIds = incoming.mapTo(mutableSetOf()) { it.post.id }
    return base.filterNot { it.id in incomingIds } + incoming.map { it.post }
}

internal fun replaceFeedById(existing: List<FeedPost>, updated: FeedPost): List<FeedPost> =
    existing.map { if (it.id == updated.id) updated else it }

internal fun screenForOpenedCommentChat(room: ChatRoomInfo): Screen.Chat = Screen.Chat(
    title = room.name ?: "익명의 이웃",
    isGroup = room.isGroup,
    chatRoomId = room.id,
)

internal suspend fun requestCommentChat(
    commentId: String,
    openCommentChat: suspend (String) -> Result<ChatRoomInfo>,
    onCommentChatOpened: (ChatRoomInfo) -> Unit,
    onFailure: (Throwable) -> Unit,
) {
    openCommentChat(commentId).fold(
        onSuccess = onCommentChatOpened,
        onFailure = onFailure,
    )
}

@Composable
private fun ApptiveApp() {
    val context = LocalContext.current
    val activity = context as? Activity
    val appScope = rememberCoroutineScope()
    val profileViewModel: ProfileViewModel = viewModel()
    val authViewModel: AuthViewModel = viewModel()
    val reflectionViewModel: ReflectionViewModel = viewModel()
    val letterViewModel: LetterViewModel = viewModel()
    val sessionTokens by AuthSession.tokens.collectAsState()
    var isLoggedIn by remember { mutableStateOf(AuthSession.isAuthenticated) }
    var screen by remember { mutableStateOf<Screen>(if (isLoggedIn) Screen.Feed else Screen.Login) }
    var profileReturnScreen by remember { mutableStateOf<Screen>(Screen.Feed) }
    var conversationIndex by remember { mutableStateOf(0) }
    var lastBackPressTime by remember { mutableLongStateOf(0L) }
    val likedFeeds = remember { mutableStateMapOf<String, Boolean>() }
    val likingFeeds = remember { mutableStateMapOf<String, Boolean>() }
    val allFeeds = remember { mutableStateListOf<FeedPost>() }
    val myFeeds = remember { mutableStateListOf<FeedPost>() }

    fun replaceFeedInBothScopes(updated: FeedPost) {
        val nextAll = replaceFeedById(allFeeds, updated)
        val nextMine = replaceFeedById(myFeeds, updated)
        allFeeds.clear()
        allFeeds.addAll(nextAll)
        myFeeds.clear()
        myFeeds.addAll(nextMine)
    }

    LaunchedEffect(sessionTokens) {
        val nextScreen = screenAfterSessionChange(screen, sessionTokens != null)
        if (nextScreen != screen) {
            isLoggedIn = false
            screen = nextScreen
        }
    }
    val letters = remember { emptyList<Letter>() }
    val mainPagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })
    val toggleFeedLike: (String) -> Unit = { feedId ->
        if (likingFeeds[feedId] != true) {
            val wasLiked = likedFeeds[feedId] == true
            val requestedLike = !wasLiked
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
                Screen.SignUp -> Screen.Login
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
            Screen.Login -> LoginScreen(
                viewModel = authViewModel,
                onLogin = {
                    isLoggedIn = true
                    screen = Screen.Feed
                },
                onSignUp = { screen = Screen.SignUp }
            )
            Screen.SignUp -> SignUpScreen(
                viewModel = authViewModel,
                onBack = { screen = Screen.Login },
                onComplete = {
                    isLoggedIn = true
                    screen = Screen.Feed
                }
            )
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
                            loadRooms = { ChatApi.getRooms() },
                            selectedIndex = conversationIndex,
                            onSelectedIndexChange = { conversationIndex = it },
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
                            allFeeds = allFeeds,
                            myFeeds = myFeeds,
                            onOpenFeed = { screen = Screen.FeedDetail(it) },
                            isLiked = { likedFeeds[it] == true },
                            onToggleLike = toggleFeedLike,
                            loadFeeds = { cursor -> FeedApi.getFeeds(cursor = cursor) },
                            onFeedsLoaded = { remoteFeeds, append ->
                                val merged = mergeScopedFeedPage(allFeeds, remoteFeeds, append)
                                allFeeds.clear()
                                allFeeds.addAll(merged)
                                remoteFeeds.forEach { item ->
                                    likedFeeds[item.post.id] = item.liked
                                }
                            },
                            loadMyFeeds = { cursor -> FeedApi.getMyFeeds(cursor = cursor) },
                            onMyFeedsLoaded = { remoteFeeds, append ->
                                val merged = mergeScopedFeedPage(myFeeds, remoteFeeds, append)
                                myFeeds.clear()
                                myFeeds.addAll(merged)
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
                onSuccess = { feedId, categoryId, category, title, body ->
                    val created = FeedPost(
                        id = feedId,
                        category = category,
                        categoryId = categoryId,
                        title = title,
                        body = body,
                        accent = Purple,
                        isMine = true,
                    )
                    allFeeds.add(0, created)
                    myFeeds.add(0, created)
                    screen = Screen.Feed
                }
            )
            is Screen.EditFeed -> {
                val post = allFeeds.firstOrNull { it.id == current.feedId }
                    ?: myFeeds.firstOrNull { it.id == current.feedId }
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
                        onSuccess = { _, categoryId, category, title, body ->
                            replaceFeedInBothScopes(
                                post.copy(
                                    category = category,
                                    categoryId = categoryId,
                                    title = title,
                                    body = body,
                                ),
                            )
                            screen = Screen.FeedDetail(post.id)
                        }
                    )
                }
            }
            is Screen.FeedDetail -> {
                val post = allFeeds.firstOrNull { it.id == current.feedId }
                    ?: myFeeds.first { it.id == current.feedId }
                FeedDetailScreen(
                    post = post,
                    isLiked = likedFeeds[post.id] == true,
                    loadFeed = { feedId -> FeedApi.getFeedDetail(feedId) },
                    onFeedLoaded = { result ->
                        replaceFeedInBothScopes(result.post)
                        likedFeeds[result.post.id] = result.liked
                    },
                    onToggleLike = { toggleFeedLike(post.id) },
                    openCommentChat = ChatApi::openFromComment,
                    onCommentChatOpened = { room ->
                        screen = screenForOpenedCommentChat(room)
                    },
                    onEdit = { screen = Screen.EditFeed(post.id) },
                    onDelete = {
                        if (FeedApi.isConfigured) {
                            appScope.launch {
                                FeedApi.deleteFeed(post.id).onSuccess {
                                    allFeeds.removeAll { it.id == post.id }
                                    myFeeds.removeAll { it.id == post.id }
                                    likedFeeds.remove(post.id)
                                    screen = Screen.Feed
                                    Toast.makeText(context, "피드가 삭제되었습니다.", Toast.LENGTH_SHORT).show()
                                }.onFailure {
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
                        if (FeedApi.isConfigured) {
                            appScope.launch {
                                FeedApi.reportFeed(post.id).onSuccess {
                                    Toast.makeText(context, "신고가 접수되었습니다.", Toast.LENGTH_SHORT).show()
                                }.onFailure {
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
                markAsRead = { chatRoomId, lastReadMessageId ->
                    ChatApi.markAsRead(chatRoomId, lastReadMessageId)
                },
                leaveRoom = { chatRoomId ->
                    ChatApi.leaveRoom(chatRoomId)
                },
                onLeft = { screen = Screen.Conversations },
                onBack = { screen = Screen.Conversations }
            )
            Screen.CreateGroup -> CreateGroupScreen(
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
                viewModel = letterViewModel,
                profileViewModel = profileViewModel,
                onBack = { screen = Screen.LetterHome },
                onHistory = { screen = Screen.LetterHistory },
                onMatched = { roomId ->
                    conversationIndex = 0
                    screen = Screen.Chat("익명의 이웃", chatRoomId = roomId)
                },
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
                viewModel = reflectionViewModel,
                onBack = { screen = Screen.LetterHome },
                onFinish = { content -> screen = Screen.ReflectionDetail(content) },
                onProfile = {
                    profileReturnScreen = Screen.WriteReflection
                    screen = Screen.Profile
                }
            )
            is Screen.ReflectionDetail -> ReflectionDetailScreen(
                viewModel = reflectionViewModel,
                content = current.title,
                title = "오늘의 회고 리포트",
                onBack = { screen = Screen.LetterHome },
                onProfile = {
                    profileReturnScreen = current
                    screen = Screen.Profile
                }
            )
            Screen.Profile -> ProfileOverviewScreen(
                viewModel = profileViewModel,
                authViewModel = authViewModel,
                onBack = { screen = profileReturnScreen },
                onEdit = { screen = Screen.EditProfile },
                onInterests = { screen = Screen.Interests },
                onLogout = {
                    isLoggedIn = false
                    screen = Screen.Login
                }
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

internal fun screenAfterSessionChange(current: Screen, isAuthenticated: Boolean): Screen =
    if (!isAuthenticated && current != Screen.Login && current != Screen.SignUp) Screen.Login
    else current
