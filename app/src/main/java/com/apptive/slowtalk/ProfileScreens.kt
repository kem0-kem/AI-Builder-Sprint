package com.apptive.slowtalk

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ProfileOverviewScreen(
    location: String,
    onEdit: () -> Unit,
    onInterests: () -> Unit
) {
    PaperBackground {
        Scaffold(containerColor = Color.Transparent) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 22.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "내 프로필",
                        modifier = Modifier.weight(1f),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Ink
                    )
                    IconButton(onClick = onEdit) {
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription = "프로필 편집",
                            tint = Purple,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                HorizontalDivider(color = LineColor.copy(alpha = 0.55f))
                Spacer(Modifier.height(16.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(R.drawable.profile_avatar),
                        contentDescription = "지연 프로필 사진",
                        modifier = Modifier
                            .size(128.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("지연", fontSize = 27.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "따뜻한 이야기를 좋아해요.\n천천히, 서로의 하루를 나눠요.",
                        textAlign = TextAlign.Center,
                        color = Ink,
                        fontSize = 14.sp,
                        lineHeight = 21.sp
                    )
                    Spacer(Modifier.height(14.dp))
                    Surface(
                        shape = RoundedCornerShape(15.dp),
                        color = BlockSurface,
                        border = BorderStroke(1.dp, LineColor)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.LocationOn,
                                contentDescription = null,
                                tint = Purple,
                                modifier = Modifier.size(21.dp)
                            )
                            Text(
                                location,
                                modifier = Modifier.padding(start = 8.dp),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(BlockSurface),
                    elevation = CardDefaults.cardElevation(1.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OverviewStat(Icons.Outlined.MailOutline, "12", "받은 편지", Modifier.weight(1f))
                        VerticalDivider(Modifier.height(64.dp), color = LineColor)
                        OverviewStat(Icons.Outlined.Send, "8", "보낸 편지", Modifier.weight(1f))
                        VerticalDivider(Modifier.height(64.dp), color = LineColor)
                        OverviewStat(Icons.Outlined.People, "5", "매칭한 사람", Modifier.weight(1f))
                    }
                }

                Spacer(Modifier.height(20.dp))
                Text("나에 대해", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
                Spacer(Modifier.height(8.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onInterests),
                    shape = RoundedCornerShape(17.dp),
                    colors = CardDefaults.cardColors(BlockSurface),
                    elevation = CardDefaults.cardElevation(1.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.size(46.dp),
                            shape = CircleShape,
                            color = PurpleSoft
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.MailOutline, null, tint = Purple)
                            }
                        }
                        Column(
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .weight(1f)
                        ) {
                            Text("관심사", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("책 읽기, 산책, 음악 감상", color = SubtleInk, fontSize = 13.sp)
                        }
                        Icon(Icons.Outlined.ChevronRight, contentDescription = "관심사 보기", tint = SubtleInk)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun OverviewStat(
    icon: ImageVector,
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = Purple, modifier = Modifier.size(27.dp))
        Spacer(Modifier.height(7.dp))
        Text(value, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(2.dp))
        Text(label, color = SubtleInk, fontSize = 12.sp)
    }
}
