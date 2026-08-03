package com.apptive.slowtalk

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
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
import androidx.compose.ui.text.input.TextFieldValue
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apptive.slowtalk.ui.reflection.ReflectionUiState
import com.apptive.slowtalk.ui.reflection.ReflectionViewModel
import java.io.File
import java.io.FileOutputStream

@Composable
fun WriteReflectionScreen(
    viewModel: ReflectionViewModel,
    onBack: () -> Unit,
    onFinish: (String) -> Unit,
    onProfile: () -> Unit,
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    var textState by remember { mutableStateOf(TextFieldValue("")) }

    // Launcher for Gallery
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val file = uriToFile(context, it)
            if (file != null) {
                viewModel.performOcr(file)
            }
        }
    }

    // Launcher for Camera
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        bitmap?.let {
            val file = File(context.cacheDir, "camera_capture_${System.currentTimeMillis()}.jpg")
            val fos = FileOutputStream(file)
            it.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, fos)
            fos.close()
            viewModel.performOcr(file)
        }
    }

    LaunchedEffect(uiState) {
        if (uiState is ReflectionUiState.OcrSuccess) {
            val ocrText = (uiState as ReflectionUiState.OcrSuccess).content
            textState = TextFieldValue(ocrText)
            viewModel.resetState()
        } else if (uiState is ReflectionUiState.Success) {
            onFinish(textState.text)
            viewModel.resetState()
        }
    }
    
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
                                text = "${textState.text.length} / 1,000자",
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
                                value = textState,
                                onValueChange = { if (it.text.length <= 1000) textState = it },
                                modifier = Modifier.fillMaxSize(),
                                textStyle = TextStyle(
                                    fontSize = 16.sp,
                                    lineHeight = 32.sp,
                                    color = Ink
                                ),
                                decorationBox = { innerTextField ->
                                    if (textState.text.isEmpty()) {
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
                                IconBox(Icons.Outlined.Image, onClick = { galleryLauncher.launch("image/*") })
                                IconBox(Icons.Outlined.CameraAlt, onClick = { cameraLauncher.launch() })
                            }
                            
                            if (uiState is ReflectionUiState.Loading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp).padding(bottom = 8.dp),
                                    color = Purple,
                                    strokeWidth = 2.dp
                                )
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
                    onClick = { if (textState.text.isNotBlank()) viewModel.createReport(textState.text) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                    shape = RoundedCornerShape(30.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Purple,
                        disabledContainerColor = Purple.copy(alpha = 0.5f)
                    ),
                    enabled = textState.text.isNotBlank() && uiState !is ReflectionUiState.Loading
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
private fun IconBox(icon: ImageVector, onClick: () -> Unit = {}) {
    Surface(
        modifier = Modifier.size(54.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEEEAE5))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = SubtleInk, modifier = Modifier.size(26.dp))
        }
    }
}

private fun uriToFile(context: android.content.Context, uri: Uri): File? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val file = File(context.cacheDir, "temp_reflection_${System.currentTimeMillis()}.jpg")
        val outputStream = FileOutputStream(file)
        inputStream.copyTo(outputStream)
        inputStream.close()
        outputStream.close()
        file
    } catch (e: Exception) {
        null
    }
}

@Composable
fun ReflectionDetailScreen(
    viewModel: ReflectionViewModel,
    content: String,
    title: String,
    date: String = "2026.07.22 · 15:40",
    onBack: () -> Unit,
    onProfile: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    LaunchedEffect(Unit) {
        viewModel.fetchFeedback(content)
    }
    
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
                            Text(content, color = Ink, fontSize = 16.sp, lineHeight = 26.sp)
                            
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
                val feedbackResponse = (uiState as? ReflectionUiState.FeedbackSuccess)?.feedback
                
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
                        Text(
                            text = feedbackResponse?.summary ?: "편지를 쓰며 오늘의 감정을 정리해 드려요.",
                            fontSize = 13.sp,
                            color = SubtleInk
                        )
                    }
                }
                
                Spacer(Modifier.height(24.dp))

                if (uiState is ReflectionUiState.Loading) {
                    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Purple)
                    }
                } else if (feedbackResponse != null) {
                    feedbackResponse.feedback.forEach { item ->
                        SummaryItem(
                            icon = when(item.type) {
                                "오늘의 배움" -> Icons.Outlined.Eco
                                "내일의 다짐" -> Icons.Outlined.AutoAwesome
                                "한 줄 기록" -> Icons.Outlined.Edit
                                else -> Icons.Outlined.Lightbulb
                            },
                            label = item.type,
                            content = item.content,
                            description = ""
                        )
                    }
                }
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
