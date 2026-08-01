package com.apptive.slowtalk

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PaperBackground(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
    ) {
        /*
        Canvas(Modifier.fillMaxSize()) {
            val gap = 42.dp.toPx()
            var y = 34.dp.toPx()
            while (y < size.height) {
                drawLine(
                    color = LineColor.copy(alpha = 0.55f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f
                )
                y += gap
            }
            drawLine(
                color = Color(0xFFECA8A8).copy(alpha = 0.35f),
                start = Offset(28.dp.toPx(), 0f),
                end = Offset(28.dp.toPx(), size.height),
                strokeWidth = 1.5f
            )
        }
        */
        content()
    }
}

@Composable
fun PageHeader(
    title: String,
    subtitle: String? = null,
    onProfile: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, color = SubtleInk, fontSize = 13.sp)
            }
        }
        if (onProfile != null) {
            Surface(
                modifier = Modifier
                    .size(40.dp)
                    .clickable(onClick = onProfile),
                shape = CircleShape,
                color = Color.Transparent,
                border = androidx.compose.foundation.BorderStroke(1.3.dp, Ink)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.PersonOutline, contentDescription = "내 프로필", tint = Ink)
                }
            }
        }
    }
}

@Composable
fun AppBottomBar(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    onSelectedFeedClick: (() -> Unit)? = null,
    onSelectedLetterClick: (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        shape = RoundedCornerShape(30.dp),
        shadowElevation = 8.dp,
        color = BlockSurface
    ) {
        NavigationBar(
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            modifier = Modifier.height(74.dp),
            windowInsets = WindowInsets(0.dp)
        ) {
            BottomItem(
                selected == MainTab.CONVERSATIONS,
                Icons.Outlined.ChatBubbleOutline,
                "내 대화"
            ) { onSelect(MainTab.CONVERSATIONS) }
            val feedSelected = selected == MainTab.FEED
            BottomItem(
                selected = feedSelected,
                icon = if (feedSelected) Icons.Outlined.EditNote else Icons.Outlined.Forum,
                label = if (feedSelected) "피드쓰기" else "피드"
            ) {
                if (feedSelected && onSelectedFeedClick != null) {
                    onSelectedFeedClick()
                } else {
                    onSelect(MainTab.FEED)
                }
            }
            val letterSelected = selected == MainTab.LETTER
            val letterHistoryAction = letterSelected && onSelectedLetterClick != null
            BottomItem(
                selected = letterSelected,
                icon = if (letterHistoryAction) Icons.Outlined.History else Icons.Outlined.EditNote,
                label = if (letterHistoryAction) "이전편지" else "편지쓰기"
            ) {
                if (letterHistoryAction) {
                    onSelectedLetterClick?.invoke()
                } else {
                    onSelect(MainTab.LETTER)
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BottomItem(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = label) },
        label = { Text(label, fontSize = 11.sp) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Purple,
            selectedTextColor = Purple,
            indicatorColor = PurpleSoft,
            unselectedIconColor = Ink,
            unselectedTextColor = Ink
        )
    )
}

@Composable
fun SectionTitle(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink)
        if (action != null && onAction != null) {
            Text(
                action,
                color = Purple,
                fontSize = 13.sp,
                modifier = Modifier.clickable(onClick = onAction)
            )
        }
    }
}
