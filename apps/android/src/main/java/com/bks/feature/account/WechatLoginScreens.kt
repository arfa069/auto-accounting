package com.bks.feature.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.bks.ui.components.Button
import com.bks.ui.components.OutlinedButton
import com.bks.ui.components.OutlinedTextField
import com.bks.ui.components.TextButton
import kotlinx.coroutines.launch

@Composable
internal fun WechatLoginFlowPage(
    controller: WechatLoginController,
    avatarCache: WechatAvatarCache,
    snackbarHostState: SnackbarHostState,
    scrollState: ScrollState,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        WechatLoginFlowContent(
            controller = controller,
            avatarCache = avatarCache,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(innerPadding)
                .padding(24.dp)
        )
    }
}

@Composable
fun WechatLoginEntryButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().testTag("wechat-login-entry")
    ) {
        Text("微信登录/注册")
    }
}

@Composable
fun WechatLoginFlowContent(
    controller: WechatLoginController,
    avatarCache: WechatAvatarCache,
    modifier: Modifier = Modifier
) {
    val state = controller.state
    val coroutineScope = rememberCoroutineScope()
    Column(
        modifier = modifier.padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = controller::back, enabled = !state.operationInProgress) {
            Text("返回")
        }
        when (state.page) {
            WechatLoginPage.Preview -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    WechatAvatar(state.avatarUrl, avatarCache)
                    Column {
                        Text("确认微信资料", style = MaterialTheme.typography.titleLarge)
                        Text(state.nickname ?: "微信用户", fontWeight = FontWeight.SemiBold)
                    }
                }
                Text("本机账本不会因创建或绑定账号而改变。")
                Button(
                    onClick = { coroutineScope.launch { controller.createWechatAccount() } },
                    enabled = !state.operationInProgress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create-wechat-account")
                ) {
                    Text(if (state.operationInProgress) "处理中…" else "创建微信账号")
                }
                OutlinedButton(
                    onClick = controller::showBinding,
                    enabled = !state.operationInProgress,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("绑定已有账号")
                }
            }
            WechatLoginPage.BindExisting -> {
                Text("绑定已有账号", style = MaterialTheme.typography.titleLarge)
                Text("可使用当前密码或短信验证码。绑定成功后将签发新的登录 Session。")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BindMethodButton(
                        label = "密码验证",
                        selected = state.bindMethod == WechatBindMethod.Password,
                        onClick = { controller.selectBindMethod(WechatBindMethod.Password) },
                        modifier = Modifier.weight(1f)
                    )
                    BindMethodButton(
                        label = "短信验证",
                        selected = state.bindMethod == WechatBindMethod.Sms,
                        onClick = { controller.selectBindMethod(WechatBindMethod.Sms) },
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = state.phone,
                    onValueChange = controller::updatePhone,
                    label = { Text("用户名 / 邮箱 / 手机号") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("wechat-bind-phone")
                )
                if (state.bindMethod == WechatBindMethod.Password) {
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = controller::updatePassword,
                        label = { Text("当前密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("wechat-bind-password")
                    )
                } else {
                    OutlinedTextField(
                        value = state.code,
                        onValueChange = controller::updateCode,
                        label = { Text("验证码") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("wechat-bind-code")
                    )
                    OutlinedButton(
                        onClick = { coroutineScope.launch { controller.requestBindingSms() } },
                        enabled = !state.operationInProgress,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (state.smsRequested) "重新获取验证码" else "获取验证码")
                    }
                }
                Button(
                    onClick = { coroutineScope.launch { controller.bindExistingAccount() } },
                    enabled = !state.operationInProgress,
                    modifier = Modifier.fillMaxWidth().testTag("confirm-wechat-bind")
                ) {
                    Text(if (state.operationInProgress) "绑定中…" else "确认绑定")
                }
            }
            WechatLoginPage.Idle -> Unit
        }
        state.errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("wechat-flow-error"))
        }
    }
}

@Composable
private fun BindMethodButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
    }
}
