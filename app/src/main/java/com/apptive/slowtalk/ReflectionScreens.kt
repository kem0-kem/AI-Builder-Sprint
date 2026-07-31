package com.apptive.slowtalk

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AssignmentTurnedIn
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun WriteReflectionScreen(
    onBack: () -> Unit,
    onFinish: (String) -> Unit,
    onProfile: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    
    PaperBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 24.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "뒤로가기",
                    modifier = Modifier
                        .size(28.dp)
                        .clickable(onClick = onBack),
                    tint = Ink
                )
                Text(
                    text = "회고 리포트 작성",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp)
                )
                Surface(
                    modifier = Modifier
                        .size(40.dp)
                        .clickable(onClick = onProfile),
                    shape = CircleShape,
                    color = Color.Transparent,
                    border = androidx.compose.foundation.BorderStroke(1.2.dp, Ink)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.PersonOutline, contentDescription = "내 프로필", tint = Ink)
                    }
                }
            }
            
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            ) {
                Text(
                    text = "오늘 하루를 돌아보며, 당신의 생각과 감정을 자유롭게 적어주세요.",
                    fontSize = 14.sp,
                    color = SubtleInk,
                    lineHeight = 20.sp
                )
                
                Spacer(Modifier.height(24.dp))
                
                // Reflection Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = BlockSurface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEEEAE5))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Edit,
                                contentDescription = null,
                                tint = Purple,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "회고 리포트",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Purple,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${text.length} / 1,000자",
                                fontSize = 14.sp,
                                color = Purple.copy(alpha = 0.7f)
                            )
                        }
                        
                        Spacer(Modifier.height(16.dp))
                        
                        Box(modifier = Modifier.weight(1f)) {
                            // Lined Background for TextField
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val lineHeight = 32.sp.toPx()
                                var currentY = 0f
                                while (currentY < size.height) {
                                    drawLine(
                                        color = LineColor.copy(alpha = 0.6f),
                                        start = Offset(0f, currentY),
                                        end = Offset(size.width, currentY),
                                        strokeWidth = 1f
                                    )
                                    currentY += lineHeight
                                }
                            }
                            
                            BasicTextField(
                                value = text,
                                onValueChange = { if (it.length <= 1000) text = it },
                                modifier = Modifier.fillMaxSize(),
                                textStyle = TextStyle(
                                    fontSize = 16.sp,
                                    lineHeight = 32.sp,
                                    color = Ink
                                ),
                                decorationBox = { innerTextField ->
                                    if (text.isEmpty()) {
                                        Text(
                                            "오늘 하루는 어땠나요?\n편안하게 작성해 보세요.",
                                            fontSize = 16.sp,
                                            lineHeight = 32.sp,
                                            color = SubtleInk.copy(alpha = 0.5f)
                                        )
                                    }
                                    innerTextField()
                                }
                            )
                        }
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                IconBox(Icons.Outlined.Image)
                                IconBox(Icons.Outlined.CameraAlt)
                            }
                            
                            // Envelope Decoration placeholder
                            Icon(
                                imageVector = Icons.Outlined.AutoAwesome,
                                contentDescription = null,
                                tint = Purple.copy(alpha = 0.2f),
                                modifier = Modifier.size(80.dp)
                            )
                        }
                    }
                }
                
                Spacer(Modifier.height(24.dp))
                
                Button(
                    onClick = { if (text.isNotBlank()) onFinish(text) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                    shape = RoundedCornerShape(30.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Purple,
                        disabledContainerColor = Purple.copy(alpha = 0.5f)
                    ),
                    enabled = text.isNotBlank()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("작성 완료", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
                
                Spacer(Modifier.height(12.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = SubtleInk
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "작성된 내용은 본인만 볼 수 있어요.",
                        fontSize = 13.sp,
                        color = SubtleInk
                    )
                }
            }
        }
    }
}

@Composable
private fun IconBox(icon: ImageVector) {
    Surface(
        modifier = Modifier.size(54.dp),
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEEEAE5))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = SubtleInk, modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
fun ReflectionDetailScreen(
    title: String,
    date: String = "2026.07.22 · 15:40",
    onBack: () -> Unit,
    onProfile: () -> Unit
) {
    val scrollState = rememberScrollState()
    
    PaperBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "뒤로가기",
                    modifier = Modifier
                        .size(28.dp)
                        .clickable(onClick = onBack),
                    tint = Ink
                )
                Text(
                    text = title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp)
                )
                Text(
                    text = date,
                    fontSize = 12.sp,
                    color = SubtleInk,
                    modifier = Modifier.padding(end = 12.dp)
                )
                Surface(
                    modifier = Modifier
                        .size(40.dp)
                        .clickable(onClick = onProfile),
                    shape = CircleShape,
                    color = Color.Transparent,
                    border = androidx.compose.foundation.BorderStroke(1.2.dp, Ink)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.PersonOutline, contentDescription = "내 프로필", tint = Ink)
                    }
                }
            }
            
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            ) {
                // Message Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F3EF).copy(alpha = 0.6f)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEEEAE5))
                ) {
                    Box(Modifier.padding(24.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("안녕하세요.", color = Ink, fontSize = 16.sp)
                            Text(
                                "오늘은 평소보다 조금 느리게 걸어봤어요.\n그 길에서 작은 행복들을 많이 발견했어요.",
                                color = Ink,
                                fontSize = 16.sp,
                                lineHeight = 26.sp
                            )
                            Text(
                                "늘 빠르게만 지나치던 것들이\n천천히 바라보니 이렇게 예쁘더라고요.",
                                color = Ink,
                                fontSize = 16.sp,
                                lineHeight = 26.sp
                            )
                            Text("여러분의 하루는 어땠나요?", color = Ink, fontSize = 16.sp)
                            Text("오늘도 따뜻한 하루 보내세요. 😊", color = Ink, fontSize = 16.sp)
                            
                            Text(
                                "- 익명의 이웃 드림",
                                color = SubtleInk,
                                fontSize = 15.sp,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                        
                        // Decorative envelope at the bottom right
                        Icon(
                            imageVector = Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            tint = Purple.copy(alpha = 0.15f),
                            modifier = Modifier
                                .size(90.dp)
                                .align(Alignment.BottomEnd)
                        )
                    }
                }
                
                Spacer(Modifier.height(40.dp))
                
                // Summary Title
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(48.dp),
                        shape = CircleShape,
                        color = Peach.copy(alpha = 0.15f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Outlined.AssignmentTurnedIn,
                                contentDescription = null,
                                tint = Ink,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("오늘의 회고 리포트", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
                        Text("편지를 쓰며 오늘의 감정을 정리해 드려요.", fontSize = 13.sp, color = SubtleInk)
                    }
                }
                
                Spacer(Modifier.height(24.dp))
                
                // Summary Items
                SummaryItem(
                    icon = Icons.Outlined.FavoriteBorder,
                    label = "느낀 감정",
                    content = "평온함, 감사함, 여유로움",
                    description = "작은 것들을 발견하며 마음이 따뜻해졌어요."
                )
                SummaryItem(
                    icon = Icons.Outlined.Eco,
                    label = "오늘의 배움",
                    content = "천천히 걸을 때 더 많은 것을 볼 수 있다는 것.",
                    description = "속도보다 마음의 여유가 더 중요하다는 걸 느꼈어요."
                )
                SummaryItem(
                    icon = Icons.Outlined.AutoAwesome,
                    label = "내일의 다짐",
                    content = "잠시 멈춰 주변을 바라보는 시간을 가지기.",
                    description = "작은 행복을 놓치지 않고 감사하는 하루 보내기."
                )
                SummaryItem(
                    icon = Icons.Outlined.Edit,
                    label = "한 줄 기록",
                    content = "빠르게 가는 것도 좋지만,",
                    description = "천천히 가면 더 오래 기억되는 하루가 된다."
                )
            }
        }
    }
}

@Composable
private fun SummaryItem(
    icon: ImageVector,
    label: String,
    content: String,
    description: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BlockSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEEEAE5))
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Purple,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(label, fontSize = 13.sp, color = Purple.copy(alpha = 0.8f))
                Spacer(Modifier.height(4.dp))
                Text(content, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.height(2.dp))
                Text(description, fontSize = 14.sp, color = SubtleInk)
            }
        }
    }
}
