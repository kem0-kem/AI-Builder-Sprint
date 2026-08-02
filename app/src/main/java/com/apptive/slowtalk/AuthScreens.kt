package com.apptive.slowtalk

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import com.apptive.slowtalk.ui.auth.AuthUiState
import com.apptive.slowtalk.ui.auth.AuthViewModel

@Composable
fun LoginScreen(
    viewModel: AuthViewModel,
    onLogin: () -> Unit,
    onSignUp: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var emailState by remember { mutableStateOf(TextFieldValue("")) }
    var passwordState by remember { mutableStateOf(TextFieldValue("")) }
    var passwordVisible by remember { mutableStateOf(false) }

    PaperBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(60.dp))
            
            Image(
                painter = painterResource(id = R.drawable.letter_home_illustration),
                contentDescription = null,
                modifier = Modifier.size(180.dp)
            )
            
            Text(
                "따뜻한 하루",
                fontSize = 32.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Purple,
                modifier = Modifier.padding(top = 16.dp)
            )
            
            Text(
                "천천히, 서로의 하루를 나눠요.",
                fontSize = 14.sp,
                color = SubtleInk,
                modifier = Modifier.padding(top = 8.dp)
            )
            
            Spacer(Modifier.height(40.dp))
            
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = BlockSurface),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = emailState,
                        onValueChange = { emailState = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("이메일을 입력해주세요", color = SubtleInk.copy(alpha = 0.6f)) },
                        leadingIcon = { Icon(Icons.Outlined.MailOutline, null, tint = Purple) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent
                        ),
                        singleLine = true
                    )
                    
                    HorizontalDivider(color = LineColor.copy(alpha = 0.5f), modifier = Modifier.padding(horizontal = 12.dp))
                    
                    OutlinedTextField(
                        value = passwordState,
                        onValueChange = { passwordState = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("비밀번호를 입력해주세요", color = SubtleInk.copy(alpha = 0.6f)) },
                        leadingIcon = { Icon(Icons.Outlined.Lock, null, tint = Purple) },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                                    contentDescription = if (passwordVisible) "비밀번호 숨기기" else "비밀번호 표시",
                                    tint = SubtleInk
                                )
                            }
                        },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent
                        ),
                        singleLine = true
                    )
                }
            }

            if (uiState is AuthUiState.Error) {
                Text(
                    text = (uiState as AuthUiState.Error).message,
                    color = Color.Red,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            
            Spacer(Modifier.height(32.dp))
            
            Button(
                onClick = { 
                    viewModel.login(emailState.text.trim(), passwordState.text, onLogin)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                enabled = uiState !is AuthUiState.Loading,
                colors = ButtonDefaults.buttonColors(containerColor = Purple)
            ) {
                Text(if (uiState is AuthUiState.Loading) "로그인 중..." else "로그인", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            
            Spacer(Modifier.height(40.dp))
            
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = LineColor)
                Text(" 또는 ", modifier = Modifier.padding(horizontal = 8.dp), color = SubtleInk, fontSize = 12.sp)
                HorizontalDivider(modifier = Modifier.weight(1f), color = LineColor)
            }
            
            Spacer(Modifier.height(24.dp))
            
            Row {
                Text("계정이 없으신가요? ", color = SubtleInk, fontSize = 14.sp)
                Text(
                    "회원가입 >",
                    color = Purple,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(onClick = onSignUp)
                )
            }
            
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
fun SignUpScreen(
    viewModel: AuthViewModel,
    onBack: () -> Unit,
    onComplete: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var nameState by remember { mutableStateOf(TextFieldValue("")) }
    var emailState by remember { mutableStateOf(TextFieldValue("")) }
    var passwordState by remember { mutableStateOf(TextFieldValue("")) }
    var confirmPasswordState by remember { mutableStateOf(TextFieldValue("")) }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }
    var showPasswordErrorDialog by remember { mutableStateOf(false) }
    var showEmailExistsDialog by remember { mutableStateOf(false) }
    var showEmailAvailableDialog by remember { mutableStateOf(false) }
    var showUsernameExistsDialog by remember { mutableStateOf(false) }
    var showUsernameAvailableDialog by remember { mutableStateOf(false) }
    
    var emailCheckResult by remember { mutableStateOf<Boolean?>(null) }
    var usernameCheckResult by remember { mutableStateOf<Boolean?>(null) }

    PaperBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.padding(start = 8.dp, top = 8.dp)
                ) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "뒤로", tint = Ink)
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "회원 가입",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Ink,
                    modifier = Modifier.padding(top = 8.dp)
                )
                
                Text(
                    "따뜻한 이야기를 나누며,\n천천히, 서로의 하루를 나눠요.",
                    fontSize = 14.sp,
                    color = SubtleInk,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp)
                )
                
                Image(
                    painter = painterResource(id = R.drawable.letter_home_illustration),
                    contentDescription = null,
                    modifier = Modifier.size(160.dp).padding(vertical = 16.dp)
                )
                
                Spacer(Modifier.height(8.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AuthInputField(
                        value = nameState,
                        onValueChange = { 
                            nameState = it 
                            usernameCheckResult = null
                        },
                        placeholder = "아이디를 입력해주세요",
                        leadingIcon = Icons.Outlined.PersonOutline,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (nameState.text.isNotBlank()) {
                                viewModel.checkUsername(nameState.text.trim()) { available ->
                                    usernameCheckResult = available
                                    if (available) {
                                        showUsernameAvailableDialog = true
                                    } else {
                                        showUsernameExistsDialog = true
                                    }
                                }
                            }
                        },
                        modifier = Modifier.height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Purple)
                    ) {
                        Text("중복 확인", fontSize = 12.sp)
                    }
                }
                
                Spacer(Modifier.height(12.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AuthInputField(
                        value = emailState,
                        onValueChange = { 
                            emailState = it 
                            emailCheckResult = null
                        },
                        placeholder = "이메일을 입력해주세요",
                        leadingIcon = Icons.Outlined.MailOutline,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (emailState.text.isNotBlank()) {
                                viewModel.checkEmail(emailState.text.trim()) { available ->
                                    emailCheckResult = available
                                    if (available) {
                                        showEmailAvailableDialog = true
                                    } else {
                                        showEmailExistsDialog = true
                                    }
                                }
                            }
                        },
                        modifier = Modifier.height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Purple)
                    ) {
                        Text("중복 확인", fontSize = 12.sp)
                    }
                }
                
                Spacer(Modifier.height(12.dp))
                
                AuthInputField(
                    value = passwordState,
                    onValueChange = { passwordState = it },
                    placeholder = "비밀번호를 입력해주세요",
                    leadingIcon = Icons.Outlined.Lock,
                    isPassword = true,
                    passwordVisible = passwordVisible,
                    onVisibilityToggle = { passwordVisible = !passwordVisible }
                )
                
                Text(
                    "비밀번호는 8자 이상 입력해주세요.",
                    color = SubtleInk,
                    fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp)
                )
                
                Spacer(Modifier.height(12.dp))
                
                AuthInputField(
                    value = confirmPasswordState,
                    onValueChange = { confirmPasswordState = it },
                    placeholder = "비밀번호를 다시 입력해주세요",
                    leadingIcon = Icons.Outlined.Lock,
                    isPassword = true,
                    passwordVisible = confirmPasswordVisible,
                    onVisibilityToggle = { confirmPasswordVisible = !confirmPasswordVisible }
                )
                
                Spacer(Modifier.height(40.dp))
                
                val isFieldsFilled = nameState.text.trim().isNotEmpty() && 
                                    emailState.text.trim().isNotEmpty() && 
                                    passwordState.text.length >= 8 &&
                                    confirmPasswordState.text.isNotEmpty() &&
                                    emailCheckResult == true &&
                                    usernameCheckResult == true

                Button(
                    onClick = { 
                        if (passwordState.text == confirmPasswordState.text) {
                            viewModel.signup(nameState.text.trim(), emailState.text.trim(), passwordState.text, onComplete)
                        } else {
                            showPasswordErrorDialog = true
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Purple,
                        disabledContainerColor = Purple.copy(alpha = 0.5f)
                    ),
                    enabled = isFieldsFilled && uiState !is AuthUiState.Loading
                ) {
                    Text(if (uiState is AuthUiState.Loading) "가입 중..." else "회원 가입", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }

                if (uiState is AuthUiState.Error) {
                    Text(
                        text = (uiState as AuthUiState.Error).message,
                        color = Color.Red,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                
                Spacer(Modifier.height(40.dp))
            }
        }
    }

    if (showEmailAvailableDialog) {
        AlertDialog(
            onDismissRequest = { showEmailAvailableDialog = false },
            title = {
                Text(
                    text = "사용 가능",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = {
                Text(
                    text = "사용 가능한 이메일입니다.",
                    fontSize = 15.sp,
                    color = SubtleInk,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    TextButton(onClick = { showEmailAvailableDialog = false }) {
                        Text("확인", color = Purple, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            },
            containerColor = BlockSurface,
            shape = RoundedCornerShape(24.dp)
        )
    }

    if (showUsernameAvailableDialog) {
        AlertDialog(
            onDismissRequest = { showUsernameAvailableDialog = false },
            title = {
                Text(
                    text = "사용 가능",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = {
                Text(
                    text = "사용 가능한 아이디입니다.",
                    fontSize = 15.sp,
                    color = SubtleInk,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    TextButton(onClick = { showUsernameAvailableDialog = false }) {
                        Text("확인", color = Purple, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            },
            containerColor = BlockSurface,
            shape = RoundedCornerShape(24.dp)
        )
    }

    if (showUsernameExistsDialog) {
        AlertDialog(
            onDismissRequest = { showUsernameExistsDialog = false },
            title = {
                Text(
                    text = "이미 사용 중인 아이디에요",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = {
                Text(
                    text = "이미 사용 중인 아이디입니다.\n다른 아이디를 입력해주세요.",
                    fontSize = 15.sp,
                    color = SubtleInk,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    TextButton(onClick = { showUsernameExistsDialog = false }) {
                        Text("확인", color = Purple, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            },
            containerColor = BlockSurface,
            shape = RoundedCornerShape(24.dp)
        )
    }

    if (showEmailExistsDialog) {
        AlertDialog(
            onDismissRequest = { showEmailExistsDialog = false },
            title = {
                Text(
                    text = "이미 가입된 이메일이에요",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = {
                Text(
                    text = "이미 계정이 존재하는 이메일입니다.\n다른 이메일을 사용하거나 로그인해 주세요.",
                    fontSize = 15.sp,
                    color = SubtleInk,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    TextButton(onClick = { 
                        showEmailExistsDialog = false
                        onBack() // 로그인 화면으로 이동 제안
                    }) {
                        Text("로그인하러 가기", color = Purple, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            },
            dismissButton = {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    TextButton(onClick = { showEmailExistsDialog = false }) {
                        Text("다시 입력", color = SubtleInk, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            },
            containerColor = BlockSurface,
            shape = RoundedCornerShape(24.dp)
        )
    }

    if (showPasswordErrorDialog) {
        AlertDialog(
            onDismissRequest = { showPasswordErrorDialog = false },
            title = {
                Text(
                    text = "비밀번호 불일치",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = {
                Text(
                    text = "입력하신 두 비밀번호가 일치하지 않습니다.\n다시 한번 확인해주세요.",
                    fontSize = 15.sp,
                    color = SubtleInk,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    TextButton(
                        onClick = { showPasswordErrorDialog = false },
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Text(
                            "확인",
                            color = Purple,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                }
            },
            containerColor = BlockSurface,
            shape = RoundedCornerShape(24.dp)
        )
    }
}

@Composable
private fun AuthInputField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    passwordVisible: Boolean = false,
    onVisibilityToggle: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = BlockSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, LineColor.copy(alpha = 0.6f))
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(placeholder, color = SubtleInk.copy(alpha = 0.5f), fontSize = 14.sp) },
            leadingIcon = { Icon(leadingIcon, null, tint = Purple, modifier = Modifier.size(20.dp)) },
            trailingIcon = if (isPassword && onVisibilityToggle != null) {
                {
                    IconButton(onClick = onVisibilityToggle) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                            contentDescription = null,
                            tint = SubtleInk.copy(alpha = 0.7f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            } else null,
            visualTransformation = if (isPassword && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                cursorColor = Purple
            ),
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp)
        )
    }
}
