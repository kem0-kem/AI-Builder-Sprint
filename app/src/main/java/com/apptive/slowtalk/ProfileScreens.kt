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
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.apptive.slowtalk.ui.auth.AuthViewModel
import com.apptive.slowtalk.ui.profile.ProfileUiState
import com.apptive.slowtalk.ui.profile.ProfileViewModel

@Composable
fun ProfileOverviewScreen(
    viewModel: ProfileViewModel,
    authViewModel: AuthViewModel,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onInterests: () -> Unit,
    onLogout: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.fetchProfile()
    }

    PaperBackground {
        Scaffold(containerColor = Color.Transparent) { padding ->
            when (val state = uiState) {
                is ProfileUiState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("로딩 중...")
                    }
                }
                is ProfileUiState.Success -> {
                    val profile = state.profile
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(horizontal = 22.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        // Header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.Outlined.ArrowBack, "뒤로")
                            }
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
                                    "프로필 편집",
                                    tint = Purple,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }

                        HorizontalDivider(color = LineColor.copy(alpha = 0.55f))
                        Spacer(Modifier.height(16.dp))

                        // Profile Info
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                modifier = Modifier.size(128.dp),
                                shape = CircleShape,
                                color = Color(0xFFF0F1F4)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Outlined.PersonOutline,
                                        contentDescription = "${profile.nickname} 프로필 사진",
                                        tint = Color(0xFF62666B),
                                        modifier = Modifier.size(78.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(profile.nickname, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                profile.bio.orEmpty(),
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
                                        null,
                                        tint = Purple,
                                        modifier = Modifier.size(21.dp)
                                    )
                                    val locationText = buildString {
                                        append(profile.region?.province?.name.orEmpty())
                                        append(" ")
                                        append(profile.region?.district?.name.orEmpty())
                                        profile.region?.subDistrict?.name?.let {
                                            append(" ")
                                            append(it)
                                        }
                                    }
                                    Text(
                                        locationText,
                                        modifier = Modifier.padding(start = 8.dp),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(20.dp))
                        // Statistics
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
                                OverviewStat(Icons.Outlined.MailOutline, (profile.statistics?.receivedLetters ?: 0).toString(), "받은 편지", Modifier.weight(1f))
                                VerticalDivider(Modifier.height(64.dp), color = LineColor)
                                OverviewStat(Icons.Outlined.Send, (profile.statistics?.sentLetters ?: 0).toString(), "보낸 편지", Modifier.weight(1f))
                                VerticalDivider(Modifier.height(64.dp), color = LineColor)
                                OverviewStat(Icons.Outlined.People, (profile.statistics?.matchCount ?: 0).toString(), "매칭한 사람", Modifier.weight(1f))
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
                                    Text(profile.interest, color = SubtleInk, fontSize = 13.sp)
                                }
                                Icon(Icons.Outlined.ChevronRight, "관심사 보기", tint = SubtleInk)
                            }
                        }
                        
                        Spacer(Modifier.height(40.dp))
                        
                        Button(
                            onClick = {
                                authViewModel.logout {
                                    onLogout()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Purple,
                                contentColor = Color.White
                            )
                        ) {
                            Text("로그아웃", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        
                        Spacer(Modifier.height(40.dp))
                    }
                }
                is ProfileUiState.Error -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("에러: ${state.message}")
                    }
                }
                else -> {}
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
