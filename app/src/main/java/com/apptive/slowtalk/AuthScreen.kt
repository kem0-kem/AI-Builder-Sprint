package com.apptive.slowtalk

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apptive.slowtalk.data.auth.UsernameContract
import com.apptive.slowtalk.data.repository.AuthRepository
import kotlinx.coroutines.launch

@Composable
fun AuthScreen(
    repository: AuthRepository,
    onAuthenticated: () -> Unit
) {
    var isSignup by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var nickname by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var isLoading by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (email.isBlank() || password.isBlank() || (isSignup && nickname.isBlank())) {
            message = "필수 항목을 모두 입력해 주세요."
            return
        }
        if (isSignup && password.length < 8) {
            message = "비밀번호는 8자 이상이어야 합니다."
            return
        }
        if (isSignup && username.isNotBlank() && !UsernameContract.isValid(username)) {
            message = "아이디는 영문 소문자, 숫자, 밑줄 3~30자로 입력해 주세요."
            return
        }
        isLoading = true
        message = null
        scope.launch {
            val result = if (isSignup) {
                repository.signup(email, password, nickname, username)
            } else {
                repository.login(email, password)
            }
            isLoading = false
            result.onSuccess { onAuthenticated() }
                .onFailure { message = it.message ?: "인증 요청을 처리하지 못했습니다." }
        }
    }

    PaperBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center
        ) {
            Text("천천히, 마음을 나누는 곳", fontSize = 26.sp, color = Ink)
            Spacer(Modifier.height(8.dp))
            Text(if (isSignup) "새 계정을 만들어 시작하세요." else "계속하려면 로그인해 주세요.", color = SubtleInk)
            Spacer(Modifier.height(28.dp))
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("이메일") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next)
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("비밀번호") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done)
            )
            if (isSignup) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("닉네임") },
                    singleLine = true
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = UsernameContract.normalize(it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("아이디 (선택)") },
                    supportingText = { Text("영문 소문자, 숫자, 밑줄 3~30자") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done)
                )
                TextButton(
                    enabled = username.isNotBlank() && UsernameContract.isValid(username) && !isLoading,
                    onClick = {
                        scope.launch {
                            isLoading = true
                            message = repository.checkUsername(username).fold(
                                onSuccess = { if (it) "사용할 수 있는 아이디입니다." else "이미 사용 중인 아이디입니다." },
                                onFailure = { it.message ?: "아이디를 확인하지 못했습니다." }
                            )
                            isLoading = false
                        }
                    }
                ) { Text("아이디 중복 확인") }
            }
            message?.let {
                Text(it, modifier = Modifier.padding(top = 12.dp), color = if (it == "사용할 수 있는 아이디입니다.") Purple else Ink)
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = ::submit,
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.padding(3.dp), strokeWidth = 2.dp)
                } else {
                    Text(if (isSignup) "회원가입" else "로그인")
                }
            }
            TextButton(
                onClick = {
                    isSignup = !isSignup
                    message = null
                },
                modifier = Modifier.align(Alignment.CenterHorizontally),
                enabled = !isLoading
            ) {
                Text(if (isSignup) "이미 계정이 있어요" else "처음이신가요? 회원가입")
            }
        }
    }
}
