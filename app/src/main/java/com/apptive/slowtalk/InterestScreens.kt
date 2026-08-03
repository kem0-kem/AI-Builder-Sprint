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
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.input.TextFieldValue
import com.apptive.slowtalk.ui.profile.ProfileUiState
import com.apptive.slowtalk.ui.profile.ProfileViewModel

@Composable
fun InterestSettingScreen(
    viewModel: ProfileViewModel,
    onBack: () -> Unit,
    onComplete: () -> Unit
) {
    val allInterests by viewModel.allInterests.collectAsState()
    val profileState by viewModel.uiState.collectAsState()
    val operationError by viewModel.operationError.collectAsState()
    var queryState by remember { mutableStateOf(TextFieldValue("")) }
    val selectedIds = remember { mutableStateListOf<String>() }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.fetchAllInterests()
    }

    androidx.compose.runtime.LaunchedEffect(profileState) {
        val savedIds = (profileState as? ProfileUiState.Success)
            ?.profile
            ?.interests
            ?.map { it.id }
            .orEmpty()
            .take(3)
        if (selectedIds.isEmpty()) {
            selectedIds.addAll(savedIds)
        }
    }

    fun toggle(id: String) {
        if (id in selectedIds) {
            selectedIds.remove(id)
        } else if (selectedIds.size < 3) {
            selectedIds.add(id)
        }
    }

    val filteredInterests = allInterests.filter {
        val query = queryState.text.trim()
        query.isEmpty() || it.name.contains(query, ignoreCase = true)
    }

    PaperBackground {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                Button(
                    onClick = {
                        if (selectedIds.size > 0) {
                            viewModel.updateInterests(selectedIds.toList(), onComplete)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                        .height(54.dp),
                    enabled = selectedIds.size > 0,
                    shape = RoundedCornerShape(30.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Purple,
                        contentColor = Color.White
                    )
                ) {
                    Text(
                        "선택 완료 (${selectedIds.size}/3)",
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
                            "관심사를 최대 3개 선택해주세요.\n공통의 관심사가 더 좋은 연결을 만들어줘요.",
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
                                "${selectedIds.size} / 3 선택",
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
                                value = queryState,
                                onValueChange = { queryState = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                textStyle = TextStyle(
                                    color = Ink,
                                    fontSize = 13.sp
                                ),
                                decorationBox = { innerTextField ->
                                    Box {
                                        if (queryState.text.isEmpty()) {
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
                    InterestSectionTitle("전체 관심사", sparkle = true)
                    Spacer(Modifier.height(6.dp))
                    UnifiedInterestGrid(
                        items = filteredInterests,
                        columns = 3,
                        selectedIds = selectedIds,
                        onToggle = ::toggle
                    )
                }

                if (filteredInterests.isEmpty()) {
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
                if (operationError != null) {
                    item {
                        Text(
                            operationError.orEmpty(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
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
private fun UnifiedInterestGrid(
    items: List<com.apptive.slowtalk.data.remote.InterestDto>,
    columns: Int,
    selectedIds: List<String>,
    onToggle: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(columns).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEach { interest ->
                    val isSelected = interest.interestId in selectedIds
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clickable { onToggle(interest.interestId) },
                        shape = RoundedCornerShape(24.dp),
                        color = if (isSelected) PurpleSoft else BlockSurface,
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) Purple else Purple.copy(alpha = 0.18f)
                        )
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                interest.name,
                                color = if (isSelected) Purple else Ink,
                                fontSize = if (interest.name.length >= 6) 11.sp else 12.sp,
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
