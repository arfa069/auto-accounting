package com.autoaccounting.feature.account

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.autoaccounting.ui.components.Button
import com.autoaccounting.ui.components.OutlinedButton
import com.autoaccounting.ui.components.TextButton

internal sealed interface AccountPage {
    data class Flow(val flow: AccountFlow) : AccountPage

    data object Compliance : AccountPage
    data object Wechat : AccountPage
}

internal fun currentAccountPage(
    showComplianceMaterials: Boolean,
    wechatController: WechatLoginController?,
    flow: AccountFlow
): AccountPage = when {
    showComplianceMaterials -> AccountPage.Compliance
    wechatController?.state?.page?.let { it != WechatLoginPage.Idle } == true -> AccountPage.Wechat
    else -> AccountPage.Flow(flow)
}

internal class AccountFlowPageArgs(
    val state: AccountUiState,
    val onAction: (AccountAction) -> Unit,
    val wechatEnabled: Boolean,
    val onWechatClick: () -> Unit,
    val onComplianceClick: () -> Unit
)

@Composable
internal fun AccountFlowPageContent(
    args: AccountFlowPageArgs,
    snackbarHostState: SnackbarHostState,
    scrollState: ScrollState,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
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

                when (args.state.flow) {
                    AccountFlow.Landing -> LandingContent(
                        state = args.state,
                        onAction = args.onAction,
                        wechatEnabled = args.wechatEnabled,
                        onWechatClick = args.onWechatClick,
                        onComplianceClick = args.onComplianceClick
                    )
                    AccountFlow.Login -> LoginContent(
                        state = args.state,
                        onAction = args.onAction,
                        wechatEnabled = args.wechatEnabled,
                        onWechatClick = args.onWechatClick
                    )
                    AccountFlow.Register -> RegisterContent(
                        state = args.state,
                        onAction = args.onAction,
                        wechatEnabled = args.wechatEnabled,
                        onWechatClick = args.onWechatClick
                    )
                    AccountFlow.Recovery -> RecoveryContent(
                        state = args.state,
                        onAction = args.onAction
                    )
                    AccountFlow.LocalModeExplanation -> LocalModeExplanation(
                        onAction = args.onAction
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "本地记账",
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
    wechatEnabled: Boolean,
    onWechatClick: () -> Unit,
    onComplianceClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ValueCard()
        AgreementRow(
            accepted = state.agreementAccepted,
            onAcceptedChange = { onAction(AccountAction.SetAgreementAccepted(it)) }
        )
        if (wechatEnabled) {
            WechatLoginEntryButton(
                onClick = onWechatClick,
                enabled = !state.operationInProgress
            )
        }
        Button(
            onClick = { onAction(AccountAction.ShowLogin) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state.operationInProgress) "登录中…" else "登录")
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
    onAction: (AccountAction) -> Unit,
    wechatEnabled: Boolean,
    onWechatClick: () -> Unit
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
            enabled = !state.operationInProgress,
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
        if (wechatEnabled) {
            WechatLoginEntryButton(
                onClick = onWechatClick,
                enabled = !state.operationInProgress
            )
        }
    }
}

@Composable
private fun RegisterContent(
    state: AccountUiState,
    onAction: (AccountAction) -> Unit,
    wechatEnabled: Boolean,
    onWechatClick: () -> Unit
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
            enabled = !state.operationInProgress,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state.operationInProgress) "注册中…" else "完成注册")
        }
        if (wechatEnabled) {
            WechatLoginEntryButton(
                onClick = onWechatClick,
                enabled = !state.operationInProgress
            )
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
            enabled = !state.operationInProgress,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state.operationInProgress) "提交中…" else "重置密码")
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
            Text("不登录也能记账；登录后可管理账号与注销状态，本机账本始终保留在本机。")
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
