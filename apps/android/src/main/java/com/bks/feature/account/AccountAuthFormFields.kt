package com.bks.feature.account

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.bks.ui.components.Checkbox
import com.bks.ui.components.OutlinedButton
import com.bks.ui.components.OutlinedTextField

@Composable
internal fun PhoneField(
    state: AccountUiState,
    onAction: (AccountAction) -> Unit
) {
    OutlinedTextField(
        value = state.phone,
        onValueChange = { onAction(AccountAction.UpdatePhone(it)) },
        label = { Text("用户名 / 邮箱 / 手机号") },
        singleLine = true,
        isError = state.phoneError != null,
        supportingText = { state.phoneError?.let { Text(it) } },
        modifier = Modifier
            .fillMaxWidth()
            .testTag("account-phone")
    )
}

@Composable
internal fun SmsCodeRow(
    state: AccountUiState,
    onAction: (AccountAction) -> Unit
) {
    if (!state.requiresVerificationCode) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        OutlinedTextField(
            value = state.verificationCode,
            onValueChange = { onAction(AccountAction.UpdateVerificationCode(it)) },
            label = { Text("验证码") },
            singleLine = true,
            isError = state.verificationCodeError != null,
            supportingText = { state.verificationCodeError?.let { Text(it) } },
            modifier = Modifier
                .weight(2f)
                .testTag("account-code")
        )
        OutlinedButton(
            onClick = { onAction(AccountAction.RequestSmsCode) },
            enabled = state.smsCountdownSeconds == 0 && !state.operationInProgress,
            modifier = Modifier
                .weight(1f)
                .testTag("account-code-request")
        ) {
            Text(
                if (state.operationInProgress) {
                    "处理中…"
                } else if (state.smsCountdownSeconds > 0) {
                    "${state.smsCountdownSeconds} 秒后重试"
                } else {
                    "获取验证码"
                },
                maxLines = 1
            )
        }
    }
}

@Composable
internal fun PasswordPairFields(
    state: AccountUiState,
    onAction: (AccountAction) -> Unit
) {
    PasswordField(
        value = state.password,
        error = state.passwordError,
        label = "设置密码",
        onValueChange = { onAction(AccountAction.UpdatePassword(it)) }
    )
    PasswordField(
        value = state.confirmPassword,
        error = state.confirmPasswordError,
        label = "确认密码",
        testTag = "account-confirm-password",
        onValueChange = { onAction(AccountAction.UpdateConfirmPassword(it)) }
    )
}

@Composable
internal fun PasswordField(
    value: String,
    error: String?,
    label: String,
    testTag: String = "account-password",
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        isError = error != null,
        supportingText = { error?.let { Text(it) } },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
    )
}

@Composable
internal fun AgreementRow(
    accepted: Boolean,
    onAcceptedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("agreement-toggle")
            .clickable { onAcceptedChange(!accepted) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = accepted,
            onCheckedChange = onAcceptedChange
        )
        Spacer(Modifier.width(8.dp))
        Text("我已阅读并同意用户协议和隐私政策")
    }
}
