package com.autoaccounting.feature.account

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.autoaccounting.ui.components.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.autoaccounting.ui.components.Checkbox
import androidx.compose.material3.MaterialTheme
import com.autoaccounting.ui.components.OutlinedButton
import com.autoaccounting.ui.components.OutlinedTextField
import com.autoaccounting.ui.components.SlidePageTransition
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.autoaccounting.ui.components.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.autoaccounting.feature.compliance.ComplianceMaterialsScreen
import kotlinx.coroutines.delay

@Composable
fun AccountScreen(
    modifier: Modifier = Modifier,
    initialState: AccountUiState = AccountUiState(),
    accountRepository: AccountRepository = FakeAccountRepository(),
    onSessionChange: (AccountSession) -> Unit = {}
) {
    var state by remember { mutableStateOf(initialState) }
    var showComplianceMaterials by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val snackbarHostState = remember { SnackbarHostState() }

    fun dispatch(action: AccountAction) {
        val nextState = when (action) {
            AccountAction.RequestSmsCode -> reduceAccountState(state, action)
                .submitSmsCodeRequestIfValid(accountRepository)
            AccountAction.SubmitLogin -> reduceAccountState(state, action)
                .submitLoginIfValid(accountRepository)
            AccountAction.SubmitRegister -> reduceAccountState(state, action)
                .submitRegisterIfValid(accountRepository)
            AccountAction.SubmitRecovery -> reduceAccountState(state, action)
                .submitRecoveryIfValid(accountRepository)
            else -> reduceAccountState(state, action)
        }
        state = nextState
        nextState.session?.let(onSessionChange)
    }

    LaunchedEffect(state.flow) {
        scrollState.scrollTo(0)
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { snackbarHostState.showSnackbar(it) }
    }

    LaunchedEffect(state.smsCountdownSeconds) {
        if (state.smsCountdownSeconds > 0) {
            delay(1_000)
            dispatch(AccountAction.TickSmsCountdown)
        }
    }

    val page = if (showComplianceMaterials) {
        AccountPage.Compliance
    } else {
        AccountPage.Flow(state.flow)
    }

    SlidePageTransition(
        targetState = page,
        modifier = modifier.fillMaxSize()
    ) { targetPage ->
        when (targetPage) {
            AccountPage.Compliance -> ComplianceMaterialsScreen(
                onBack = { showComplianceMaterials = false }
            )

            is AccountPage.Flow -> Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Transparent,
                snackbarHost = { SnackbarHost(snackbarHostState) }
            ) { innerPadding ->
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    color = Color.Transparent
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        AccountHeader()

                        when (targetPage.flow) {
                            AccountFlow.Landing -> LandingContent(
                                state = state,
                                onAction = ::dispatch,
                                onComplianceClick = { showComplianceMaterials = true }
                            )
                            AccountFlow.Login -> LoginContent(
                                state = state,
                                onAction = ::dispatch
                            )
                            AccountFlow.Register -> RegisterContent(
                                state = state,
                                onAction = ::dispatch
                            )
                            AccountFlow.Recovery -> RecoveryContent(
                                state = state,
                                onAction = ::dispatch
                            )
                            AccountFlow.LocalModeExplanation -> LocalModeExplanation(
                                onAction = ::dispatch
                            )
                        }
                    }
                }
            }
        }
    }
}

private sealed interface AccountPage {
    data class Flow(val flow: AccountFlow) : AccountPage

    data object Compliance : AccountPage
}

private fun AccountUiState.submitSmsCodeRequestIfValid(
    accountRepository: AccountRepository
): AccountUiState {
    if (phoneError != null) return this
    return when (val result = accountRepository.requestSmsCode(phone)) {
        is AccountRepositoryResult.Success -> this
        is AccountRepositoryResult.Failure -> copy(
            smsCountdownSeconds = 0,
            errorMessage = result.message
        )
    }
}

private fun AccountUiState.submitLoginIfValid(
    accountRepository: AccountRepository
): AccountUiState {
    if (phoneError != null || errorMessage != null) return this
    return when (val result = accountRepository.login(phone, password)) {
        is AccountRepositoryResult.Success -> copy(
            session = AccountSession.SignedIn(
                phone = result.value.phone,
                token = result.value.token
            )
        )
        is AccountRepositoryResult.Failure -> copy(errorMessage = result.message)
    }
}

private fun AccountUiState.submitRegisterIfValid(
    accountRepository: AccountRepository
): AccountUiState {
    if (!hasNoFieldErrors || errorMessage != null) return this
    return when (val result = accountRepository.register(phone, verificationCode, password)) {
        is AccountRepositoryResult.Success -> copy(
            session = AccountSession.SignedIn(
                phone = result.value.phone,
                token = result.value.token
            )
        )
        is AccountRepositoryResult.Failure -> copy(errorMessage = result.message)
    }
}

private fun AccountUiState.submitRecoveryIfValid(
    accountRepository: AccountRepository
): AccountUiState {
    if (!hasNoFieldErrors || errorMessage != null) return this
    return when (val result = accountRepository.recoverPassword(phone, verificationCode, password)) {
        is AccountRepositoryResult.Success -> copy(
            session = AccountSession.SignedIn(
                phone = result.value.phone,
                token = result.value.token
            )
        )
        is AccountRepositoryResult.Failure -> copy(errorMessage = result.message)
    }
}

@Composable
private fun AccountHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "自动记账",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "先把微信、支付宝交易放进待确认队列，确认后再进入本地账本。",
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun LandingContent(
    state: AccountUiState,
    onAction: (AccountAction) -> Unit,
    onComplianceClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ValueCard()
        AgreementRow(
            accepted = state.agreementAccepted,
            onAcceptedChange = { onAction(AccountAction.SetAgreementAccepted(it)) }
        )
        Button(
            onClick = { onAction(AccountAction.ShowLogin) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("登录")
        }
        OutlinedButton(
            onClick = { onAction(AccountAction.ShowRegister) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("创建账号")
        }
        TextButton(
            onClick = { onAction(AccountAction.StartLocalMode) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("继续使用本地模式")
        }
        TextButton(
            onClick = onComplianceClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("隐私与合规材料")
        }
    }
}

@Composable
private fun LoginContent(
    state: AccountUiState,
    onAction: (AccountAction) -> Unit
) {
    AccountFormFrame(title = "登录账号", onBack = { onAction(AccountAction.BackToLanding) }) {
        PhoneField(state, onAction)
        PasswordField(
            value = state.password,
            error = state.passwordError,
            label = "密码",
            onValueChange = { onAction(AccountAction.UpdatePassword(it)) }
        )
        AgreementRow(
            accepted = state.agreementAccepted,
            onAcceptedChange = { onAction(AccountAction.SetAgreementAccepted(it)) }
        )
        Button(
            onClick = { onAction(AccountAction.SubmitLogin) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("登录")
        }
        TextButton(
            onClick = { onAction(AccountAction.ShowRecovery) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("忘记密码")
        }
    }
}

@Composable
private fun RegisterContent(
    state: AccountUiState,
    onAction: (AccountAction) -> Unit
) {
    AccountFormFrame(title = "创建账号", onBack = { onAction(AccountAction.BackToLanding) }) {
        PhoneField(state, onAction)
        SmsCodeRow(state, onAction)
        PasswordPairFields(state, onAction)
        AgreementRow(
            accepted = state.agreementAccepted,
            onAcceptedChange = { onAction(AccountAction.SetAgreementAccepted(it)) }
        )
        Button(
            onClick = { onAction(AccountAction.SubmitRegister) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("完成注册")
        }
    }
}

@Composable
private fun RecoveryContent(
    state: AccountUiState,
    onAction: (AccountAction) -> Unit
) {
    AccountFormFrame(title = "找回密码", onBack = { onAction(AccountAction.ShowLogin) }) {
        PhoneField(state, onAction)
        SmsCodeRow(state, onAction)
        PasswordPairFields(state, onAction)
        Button(
            onClick = { onAction(AccountAction.SubmitRecovery) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("重置密码")
        }
    }
}

@Composable
private fun LocalModeExplanation(
    onAction: (AccountAction) -> Unit
) {
    AccountFormFrame(title = "本地模式", onBack = { onAction(AccountAction.BackToLanding) }) {
        Text("本地记账可用，但云端 AI、注册设备配置和未来同步不可用。")
        Button(
            onClick = { onAction(AccountAction.ConfirmLocalMode) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("进入本地模式")
        }
    }
}

@Composable
private fun ValueCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("本地账本优先", fontWeight = FontWeight.SemiBold)
            Text("不登录也能记账；登录后可使用云端配置、设备管理和后续同步能力。")
        }
    }
}

@Composable
private fun AccountFormFrame(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Text("返回")
            }
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        content()
    }
}

@Composable
private fun PhoneField(
    state: AccountUiState,
    onAction: (AccountAction) -> Unit
) {
    OutlinedTextField(
        value = state.phone,
        onValueChange = { onAction(AccountAction.UpdatePhone(it)) },
        label = { Text("手机号") },
        singleLine = true,
        isError = state.phoneError != null,
        supportingText = { state.phoneError?.let { Text(it) } },
        modifier = Modifier
            .fillMaxWidth()
            .testTag("account-phone")
    )
}

@Composable
private fun SmsCodeRow(
    state: AccountUiState,
    onAction: (AccountAction) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.verificationCode,
            onValueChange = { onAction(AccountAction.UpdateVerificationCode(it)) },
            label = { Text("验证码") },
            singleLine = true,
            isError = state.verificationCodeError != null,
            supportingText = { state.verificationCodeError?.let { Text(it) } },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("account-code")
        )
        OutlinedButton(
            onClick = { onAction(AccountAction.RequestSmsCode) },
            enabled = state.smsCountdownSeconds == 0,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (state.smsCountdownSeconds > 0) {
                    "${state.smsCountdownSeconds} 秒后重试"
                } else {
                    "获取验证码"
                }
            )
        }
    }
}

@Composable
private fun PasswordPairFields(
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
private fun PasswordField(
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
private fun AgreementRow(
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
