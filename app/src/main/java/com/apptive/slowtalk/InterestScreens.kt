package com.apptive.slowtalk

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Checkroom
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Paid
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Park
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class RecommendedInterest(
    val label: String,
    val icon: ImageVector
)

private val recommendedInterests = listOf(
    RecommendedInterest("여행", Icons.Outlined.Flight),
    RecommendedInterest("음악", Icons.Outlined.MusicNote),
    RecommendedInterest("영화", Icons.Outlined.Movie),
    RecommendedInterest("책", Icons.Outlined.MenuBook),
    RecommendedInterest("카페", Icons.Outlined.LocalCafe),
    RecommendedInterest("산책", Icons.Outlined.Park),
    RecommendedInterest("운동", Icons.Outlined.FitnessCenter),
    RecommendedInterest("사진", Icons.Outlined.PhotoCamera),
    RecommendedInterest("글쓰기", Icons.Outlined.Edit),
    RecommendedInterest("요리", Icons.Outlined.Restaurant),
    RecommendedInterest("반려동물", Icons.Outlined.Pets),
    RecommendedInterest("드라마", Icons.Outlined.Tv),
    RecommendedInterest("전시·미술", Icons.Outlined.Palette),
    RecommendedInterest("게임", Icons.Outlined.SportsEsports),
    RecommendedInterest("패션", Icons.Outlined.Checkroom),
    RecommendedInterest("IT·기술", Icons.Outlined.Computer),
    RecommendedInterest("재테크", Icons.Outlined.Paid),
    RecommendedInterest("자기계발", Icons.Outlined.Spa),
    RecommendedInterest("명상", Icons.Outlined.SelfImprovement),
    RecommendedInterest("독서모임", Icons.Outlined.Groups)
)

private val allInterests = listOf(
    "인테리어", "공예", "보드게임", "캠핑", "등산",
    "수영", "자전거", "테니스", "배드민턴", "골프",
    "러닝", "요가", "헬스", "필라테스", "댄스",
    "팟캐스트", "유튜브", "블로그", "일러스트", "디자인",
    "외국어", "심리학", "역사", "경제", "정치",
    "환경·제로웨이스트", "봉사활동", "종교", "반려식물", "별보기",
    "만화·웹툰", "애니메이션", "코딩", "스타트업", "프로그래밍",
    "차·자동차", "바리스타", "와인", "맥주", "전통문화"
)

@Composable
fun InterestSettingScreen(
    onBack: () -> Unit,
    onComplete: (List<String>) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }

    fun toggle(label: String) {
        if (label in selected) {
            selected.remove(label)
        } else if (selected.size < 3) {
            selected.add(label)
        }
    }

    val normalizedQuery = query.trim()
    val visibleRecommended = recommendedInterests.filter {
        normalizedQuery.isEmpty() || it.label.contains(normalizedQuery, ignoreCase = true)
    }
    val visibleAll = allInterests.filter {
        normalizedQuery.isEmpty() || it.contains(normalizedQuery, ignoreCase = true)
    }

    PaperBackground {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                Button(
                    onClick = {
                        if (selected.size == 3) {
                            onComplete(selected.toList())
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                        .height(54.dp),
                    shape = RoundedCornerShape(30.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Purple,
                        contentColor = Color.White
                    )
                ) {
                    Text(
                        "선택 완료 (${selected.size}/3)",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 18.dp,
                    end = 18.dp,
                    bottom = 12.dp
                ),
                verticalArrangement = Arrangement.spacedBy(11.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.Outlined.ArrowBack,
                                contentDescription = "뒤로",
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Text(
                            "관심사 설정",
                            modifier = Modifier.weight(1f),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Surface(
                            modifier = Modifier
                                .size(36.dp)
                                .clickable(onClick = onBack),
                            shape = CircleShape,
                            color = Color.Transparent,
                            border = BorderStroke(1.2.dp, Ink)
                        ) {
                            androidx.compose.foundation.layout.Box(
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Outlined.PersonOutline,
                                    contentDescription = "프로필로 돌아가기"
                                )
                            }
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "관심사를 3개 선택해주세요.\n공통의 관심사가 더 좋은 연결을 만들어줘요.",
                            modifier = Modifier.weight(1f),
                            color = Ink,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = PurpleSoft
                        ) {
                            Text(
                                "${selected.size} / 3 선택",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                color = Purple,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                item {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        color = BlockSurface,
                        border = BorderStroke(1.dp, LineColor)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.Search,
                                contentDescription = null,
                                tint = SubtleInk,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.size(8.dp))
                            BasicTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                textStyle = TextStyle(
                                    color = Ink,
                                    fontSize = 13.sp
                                ),
                                decorationBox = { innerTextField ->
                                    Box {
                                        if (query.isEmpty()) {
                                            Text(
                                                "관심사 검색",
                                                color = SubtleInk,
                                                fontSize = 13.sp
                                            )
                                        }
                                        innerTextField()
                                    }
                                }
                            )
                        }
                    }
                }

                item {
                    InterestSectionTitle("추천 관심사")
                    Spacer(Modifier.height(6.dp))
                    InterestGrid(
                        items = visibleRecommended.map { it.label },
                        icons = visibleRecommended.associate { it.label to it.icon },
                        columns = 4,
                        selected = selected,
                        onToggle = ::toggle
                    )
                }

                item {
                    InterestSectionTitle("전체 관심사", sparkle = false)
                    Spacer(Modifier.height(6.dp))
                    InterestGrid(
                        items = visibleAll,
                        columns = 3,
                        selected = selected,
                        onToggle = ::toggle
                    )
                }

                if (visibleRecommended.isEmpty() && visibleAll.isEmpty()) {
                    item {
                        Text(
                            "검색 결과가 없어요.",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 28.dp),
                            textAlign = TextAlign.Center,
                            color = SubtleInk
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InterestSectionTitle(
    title: String,
    sparkle: Boolean = true
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (sparkle) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = Purple,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.size(7.dp))
        }
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun InterestGrid(
    items: List<String>,
    icons: Map<String, ImageVector> = emptyMap(),
    columns: Int,
    selected: List<String>,
    onToggle: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(columns).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEach { item ->
                    val isSelected = item in selected
                    val hasIcon = icons[item] != null
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clickable { onToggle(item) },
                        shape = RoundedCornerShape(24.dp),
                        color = if (isSelected) PurpleSoft else BlockSurface,
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) Purple else Purple.copy(alpha = 0.18f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            icons[item]?.let {
                                Icon(
                                    it,
                                    contentDescription = null,
                                    tint = Purple,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.size(6.dp))
                            }
                            Text(
                                item,
                                color = if (isSelected) Purple else Ink,
                                fontSize = when {
                                    hasIcon && item.length >= 5 -> 11.sp
                                    hasIcon -> 12.sp
                                    item.length >= 8 -> 10.sp
                                    item.length >= 6 -> 11.sp
                                    else -> 12.sp
                                },
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                repeat(columns - rowItems.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
