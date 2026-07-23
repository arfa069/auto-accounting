package com.autoaccounting.feature.account

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.autoaccounting.BuildConfig
import com.tencent.mm.opensdk.constants.ConstantsAPI
import com.tencent.mm.opensdk.modelbase.BaseReq
import com.tencent.mm.opensdk.modelbase.BaseResp
import com.tencent.mm.opensdk.modelmsg.SendAuth
import com.tencent.mm.opensdk.openapi.IWXAPIEventHandler
import com.tencent.mm.opensdk.openapi.WXAPIFactory

abstract class WechatCallbackActivity : Activity(), IWXAPIEventHandler {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleWechatIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWechatIntent(intent)
    }

    override fun onReq(request: BaseReq?) {
        finish()
    }

    override fun onResp(response: BaseResp?) {
        if (response?.type != ConstantsAPI.COMMAND_SENDAUTH || response !is SendAuth.Resp) {
            finish()
            return
        }
        val error = when (response.errCode) {
            BaseResp.ErrCode.ERR_OK -> WechatSdkAuthError.None
            BaseResp.ErrCode.ERR_USER_CANCEL -> WechatSdkAuthError.Cancelled
            BaseResp.ErrCode.ERR_AUTH_DENIED -> WechatSdkAuthError.Denied
            else -> WechatSdkAuthError.Other
        }
        val delivery = WechatAuthCallbackProcessor(WechatAuthStateStore(applicationContext)).process(
            WechatSdkAuthResponse(
                code = response.code,
                state = response.state,
                error = error
            )
        )
        if (delivery != null) {
            startActivity(WechatAuthCallbackIntent.create(this, delivery))
        }
        finish()
    }

    private fun handleWechatIntent(callbackIntent: Intent?) {
        val appId = BuildConfig.AUTO_ACCOUNTING_WECHAT_APP_ID
        if (appId.isBlank()) {
            finish()
            return
        }
        val api = WXAPIFactory.createWXAPI(this, appId, false)
        if (!api.handleIntent(callbackIntent, this)) {
            finish()
        }
    }
}
