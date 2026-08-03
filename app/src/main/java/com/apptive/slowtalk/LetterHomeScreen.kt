package com.apptive.slowtalk

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AssignmentTurnedIn
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
fun LetterHomeScreen(
    onWrite: () -> Unit,
    onHistory: () -> Unit,
    onReflection: () -> Unit,
    onProfile: () -> Unit,
    onTab: (MainTab) -> Unit,
    showBottomBar: Boolean = true
) {
    PaperBackground {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                if (showBottomBar) {
                    AppBottomBar(
                        selected = MainTab.LETTER,
                        onSelect = onTab,
                        onSelectedLetterClick = onHistory
                    )
                }
            }
        ) { scaffoldPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(scaffoldPadding),
                contentPadding = PaddingValues(start = 24.dp, top = 10.dp, end = 24.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                item {
                    LetterHomeHeader(onProfile = onProfile)
                }
                item {
                    Text(
                        text = "오늘은 어떤 이야기를\n남겨볼까요?",
                        color = Ink,
                        fontSize = 24.sp,
                        lineHeight = 33.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.4).sp,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                    )
                }
                item {
                    TodayLetterCard(onWrite = onWrite)
                }
                item {
                    ReflectionReportCard(onClick = onReflection)
                }
            }
        }
    }
}

@Composable
private fun LetterHomeHeader(onProfile: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "오늘의 편지",
            color = Ink,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        Surface(
            modifier = Modifier
                .size(42.dp)
                .clickable(onClick = onProfile),
            shape = CircleShape,
            color = BlockSurface,
            border = androidx.compose.foundation.BorderStroke(1.3.dp, Ink)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.PersonOutline,
                    contentDescription = "내 프로필",
                    tint = Ink,
                    modifier = Modifier.size(25.dp)
                )
            }
        }
    }
}

@Composable
private fun TodayLetterCard(onWrite: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .clickable(onClick = onWrite),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F0FF)),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Box(Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(R.drawable.letter_home_illustration),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(26.dp))
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(22.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Purple.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = "오늘의 편지",
                        color = Purple,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "요즘 마음에 머무는\n생각을 천천히 적어보세요.",
                    color = Ink,
                    fontSize = 18.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onWrite,
                    modifier = Modifier
                        .width(142.dp)
                        .height(52.dp),
                    shape = RoundedCornerShape(17.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Purple)
                ) {
                    Text("편지 쓰기", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ReflectionReportCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = BlockSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Peach.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(52.dp),
                shape = CircleShape,
                color = Peach.copy(alpha = 0.28f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.AssignmentTurnedIn,
                        contentDescription = null,
                        tint = Ink,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "오늘의 회고 리포트",
                    color = Ink,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "편지를 쓰면 오늘의 감정을 정리해 드려요",
                    color = SubtleInk,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = "이전 편지 보기",
                tint = Ink
            )
        }
    }
}
