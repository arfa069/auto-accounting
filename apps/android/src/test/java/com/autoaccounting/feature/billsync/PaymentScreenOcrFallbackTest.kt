package com.autoaccounting.feature.billsync

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentScreenOcrFallbackTest {
    @Test
    fun manualOcrFallbackAcceptsUnreadableWechatApplicationWindowsRegardlessOfActivity() {
        val historyDetailWindow = WechatWindowEvidence(
            activityClassName = "com.tencent.mm.plugin.wallet_core.ui.WalletOrderInfoUI",
            isApplicationWindow = true
        )

        assertTrue(
            shouldAttemptManualWechatOcrFallback(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "",
                sdkInt = Build.VERSION_CODES.R,
                windowEvidence = historyDetailWindow
            )
        )
        assertFalse(
            shouldAttemptManualWechatOcrFallback(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "交易成功 ¥10.40",
                sdkInt = Build.VERSION_CODES.R,
                windowEvidence = historyDetailWindow
            )
        )
        assertTrue(
            shouldAttemptManualWechatOcrFallback(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "",
                sdkInt = Build.VERSION_CODES.R,
                windowEvidence = historyDetailWindow.copy(
                    activityClassName = "com.tencent.mm.ui.LauncherUI"
                )
            )
        )
        assertFalse(
            shouldAttemptManualWechatOcrFallback(
                packageName = BillSyncSource.Alipay.packageName,
                pageText = "",
                sdkInt = Build.VERSION_CODES.R,
                windowEvidence = historyDetailWindow
            )
        )
        assertFalse(
            shouldAttemptManualWechatOcrFallback(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "",
                sdkInt = Build.VERSION_CODES.Q,
                windowEvidence = historyDetailWindow
            )
        )
        assertFalse(
            shouldAttemptManualWechatOcrFallback(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "",
                sdkInt = Build.VERSION_CODES.R,
                windowEvidence = historyDetailWindow.copy(isApplicationWindow = false)
            )
        )
    }

    @Test
    fun manualOcrResultRequiresExactStatusPairAndOneUnambiguousAmount() {
        val prepared = requireNotNull(
            prepareManualWechatOcrResultText(
                "当前状态\n支付成功\n¥10.40"
            )
        )
        val parsed = BillPageParser().parse(
            source = BillSyncSource.WeChat,
            pageText = prepared,
            fallbackTransactionTimeText = "2026-07-17 01:30"
        )

        assertEquals(1, parsed.size)
        assertEquals(1_040L, parsed.single().amountMinor)
        assertEquals("微信支付", parsed.single().merchantTitle)
        assertTrue(parsed.single().merchantTitleFromFallback)
        assertTrue(hasCurrentStatusPaymentSuccessPair("当前状态：支付成功"))
        assertTrue(hasCurrentStatusPaymentSuccessPair("当前状态\n支付成功"))
        assertEquals(
            null,
            prepareManualWechatOcrResultText("当前状态\n支付成功")
        )
        assertEquals(
            null,
            prepareManualWechatOcrResultText("当前状态\n支付成功\n¥10.40\n¥20.00")
        )
        assertTrue(
            prepareManualWechatOcrResultText(
                "当前状态：支付成功\n原价 ¥20.00\n实付 ¥10.40"
            ) != null
        )
        assertEquals(
            null,
            prepareManualWechatOcrResultText("成功\n¥10.40\n交易详情")
        )
        assertEquals(
            null,
            prepareManualWechatOcrResultText("当前状态\n商品\n支付成功\n¥10.40")
        )
        listOf(
            "确认支付",
            "立即支付",
            "收银台",
            "支付密码",
            "待支付",
            "处理中",
            "支付失败",
            "已取消"
        ).forEach { deniedText ->
            assertEquals(
                deniedText,
                null,
                prepareManualWechatOcrResultText(
                    "当前状态\n支付成功\n¥10.40\n$deniedText"
                )
            )
        }
    }

    @Test
    fun ocrFallbackRequiresUnreadableWechatApplicationWindowOnAndroidElevenOrLater() {
        val applicationWindow = WechatWindowEvidence(
            activityClassName = WECHAT_MERCHANT_PAYMENT_ACTIVITY_CLASS,
            isApplicationWindow = true
        )
        assertTrue(
            shouldAttemptWechatOcrFallback(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "",
                sdkInt = Build.VERSION_CODES.R,
                windowEvidence = applicationWindow
            )
        )
        assertFalse(
            shouldAttemptWechatOcrFallback(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "支付成功 ¥1.00",
                sdkInt = Build.VERSION_CODES.R,
                windowEvidence = applicationWindow
            )
        )
        assertFalse(
            shouldAttemptWechatOcrFallback(
                packageName = BillSyncSource.Alipay.packageName,
                pageText = "",
                sdkInt = Build.VERSION_CODES.R,
                windowEvidence = applicationWindow
            )
        )
        assertFalse(
            shouldAttemptWechatOcrFallback(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "",
                sdkInt = Build.VERSION_CODES.Q,
                windowEvidence = applicationWindow
            )
        )
        assertFalse(
            shouldAttemptWechatOcrFallback(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "",
                sdkInt = Build.VERSION_CODES.R,
                windowEvidence = applicationWindow.copy(isApplicationWindow = false)
            )
        )
    }

    @Test
    fun ocrFallbackDoesNotCaptureWhileScreenIsOffOrLocked() {
        assertTrue(
            isScreenReadyForWechatOcr(
                screenInteractive = true,
                keyguardLocked = false
            )
        )
        assertFalse(
            isScreenReadyForWechatOcr(
                screenInteractive = false,
                keyguardLocked = false
            )
        )
        assertFalse(
            isScreenReadyForWechatOcr(
                screenInteractive = true,
                keyguardLocked = true
            )
        )
    }

    @Test
    fun ocrFallbackAcceptsOnlyBlankOrGenericWechatNodeText() {
        val applicationWindow = WechatWindowEvidence(
            activityClassName = WECHAT_MERCHANT_PAYMENT_ACTIVITY_CLASS,
            isApplicationWindow = true
        )
        assertTrue(
            shouldAttemptWechatOcrFallback(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "返回",
                sdkInt = Build.VERSION_CODES.R,
                windowEvidence = applicationWindow
            )
        )
        assertTrue(
            shouldAttemptWechatOcrFallback(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "返回",
                sdkInt = Build.VERSION_CODES.R,
                windowEvidence = WechatWindowEvidence(
                    activityClassName = WECHAT_TRANSFER_RESULT_ACTIVITY_CLASS,
                    isApplicationWindow = true
                )
            )
        )
        assertFalse(
            shouldAttemptWechatOcrFallback(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "支付成功\n中国电信\n¥6.99\n返回商家",
                sdkInt = Build.VERSION_CODES.R,
                windowEvidence = applicationWindow
            )
        )
        assertFalse(
            shouldAttemptWechatOcrFallback(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "聊天\n测试消息\n发送消息\n返回",
                sdkInt = Build.VERSION_CODES.R,
                windowEvidence = applicationWindow
            )
        )
        assertFalse(
            shouldAttemptWechatOcrFallback(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "返回",
                sdkInt = Build.VERSION_CODES.R,
                windowEvidence = applicationWindow.copy(
                    activityClassName = "com.tencent.mm.plugin.webview.ui.tools.WebViewUI"
                )
            )
        )
        assertFalse(
            shouldAttemptWechatOcrFallback(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "返回",
                sdkInt = Build.VERSION_CODES.R,
                windowEvidence = applicationWindow.copy(isApplicationWindow = false)
            )
        )
        assertTrue(
            shouldAttemptWechatOcrFallback(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "返回",
                sdkInt = Build.VERSION_CODES.R,
                windowEvidence = applicationWindow.copy(isApplicationWindow = false),
                hasRecentPaymentNotification = true
            )
        )
    }

    @Test
    fun prominentAmountSelectionIgnoresSmallStatusAndPromotionText() {
        val selected = selectProminentPaymentAmountLine(
            lines = listOf(
                OcrLineObservation("3.59 KB/s", 22),
                OcrLineObservation("测试商户", 46),
                OcrLineObservation("12.34", 118),
                OcrLineObservation("2元 免费领", 34)
            ),
            imageHeight = 2_400
        )

        assertEquals(2, selected)
    }

    @Test
    fun amountSelectionRejectsSmallOrEquallyProminentNumericLines() {
        assertEquals(
            null,
            selectProminentPaymentAmountLine(
                lines = listOf(OcrLineObservation("12.34", 20)),
                imageHeight = 2_400
            )
        )
        assertEquals(
            null,
            selectProminentPaymentAmountLine(
                lines = listOf(
                    OcrLineObservation("12.34", 80),
                    OcrLineObservation("56.78", 70)
                ),
                imageHeight = 2_400
            )
        )
    }

    @Test
    fun amountNormalizationRepairsLetterOAndDecimalSpacing() {
        assertEquals("¥0.05", normalizeOcrAmountLine("￥ O . O5"))
        assertEquals("¥0.05", normalizeOcrAmountLine("未知符号 O . O5"))
        assertEquals("¥20", normalizeOcrAmountLine("2O"))
        assertEquals(null, normalizeOcrAmountLine("3.58 KB/s"))
        assertEquals(null, normalizeOcrAmountLine("100%"))
    }

    @Test
    fun wechatMerchantOcrUsesTrustedWindowOrRequiresRecentNotification() {
        val pageText = "支付成功\n中国电信\n¥6.99\n返回商家"

        val trustedWindow = decideWechatOcrCapture(
            pageText = pageText,
            windowEvidence = WechatWindowEvidence(
                activityClassName = WECHAT_MERCHANT_PAYMENT_ACTIVITY_CLASS,
                isApplicationWindow = true
            )
        )
        val untrustedWindow = decideWechatOcrCapture(
            pageText = pageText,
            windowEvidence = WechatWindowEvidence(
                activityClassName = "com.tencent.mm.ui.LauncherUI",
                isApplicationWindow = true
            )
        )
        val nonApplicationWindow = decideWechatOcrCapture(
            pageText = pageText,
            windowEvidence = WechatWindowEvidence(
                activityClassName = WECHAT_MERCHANT_PAYMENT_ACTIVITY_CLASS,
                isApplicationWindow = false
            )
        )
        val incompleteSignature = decideWechatOcrCapture(
            pageText = "支付成功\n中国电信\n¥6.99",
            windowEvidence = WechatWindowEvidence(
                activityClassName = WECHAT_MERCHANT_PAYMENT_ACTIVITY_CLASS,
                isApplicationWindow = true
            )
        )
        val ambiguousCompletion = decideWechatOcrCapture(
            pageText = "付款成功\n中国电信\n¥6.99",
            windowEvidence = WechatWindowEvidence(
                activityClassName = WECHAT_MERCHANT_PAYMENT_ACTIVITY_CLASS,
                isApplicationWindow = true
            )
        )
        val completedTransfer = decideWechatOcrCapture(
            pageText = "转账成功\n测试对象\n¥0.01",
            windowEvidence = WechatWindowEvidence(
                activityClassName = WECHAT_TRANSFER_RESULT_ACTIVITY_CLASS,
                isApplicationWindow = true
            )
        )

        assertTrue(trustedWindow.shouldCapture)
        assertEquals(AutomaticCaptureVerification.Standard, trustedWindow.verification)
        assertTrue(untrustedWindow.shouldCapture)
        assertEquals(
            AutomaticCaptureVerification.RequireRecentNotification,
            untrustedWindow.verification
        )
        assertEquals(
            AutomaticCaptureVerification.RequireRecentNotification,
            nonApplicationWindow.verification
        )
        assertFalse(incompleteSignature.shouldCapture)
        assertFalse(ambiguousCompletion.shouldCapture)
        assertTrue(completedTransfer.shouldCapture)
        assertEquals(AutomaticCaptureVerification.Standard, completedTransfer.verification)
        assertTrue(
            wechatOcrPaymentFingerprint("转账成功\n测试对象\n¥0.01") != null
        )
    }

    @Test
    fun wechatReceivedRedPacketOcrRequiresCompletedReceiptPageSignature() {
        val windowEvidence = WechatWindowEvidence(
            activityClassName =
                "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyNewDetailUI",
            isApplicationWindow = true
        )
        val completedReceipt = decideWechatOcrCapture(
            pageText = """
                Yellen的红包
                恭喜发财，大吉大利
                4.00元
                已存入零钱，可用于发红包
                回复表情到聊天
            """.trimIndent(),
            windowEvidence = windowEvidence
        )
        val missingCompletion = decideWechatOcrCapture(
            pageText = "Yellen的红包\n4.00元\n回复表情到聊天",
            windowEvidence = windowEvidence
        )
        val missingAmount = decideWechatOcrCapture(
            pageText = "Yellen的红包\n已存入零钱\n回复表情到聊天",
            windowEvidence = windowEvidence
        )
        val chatMessage = decideWechatOcrCapture(
            pageText = "聊天\nYellen的红包\n4.00元\n已存入零钱\n发送消息",
            windowEvidence = windowEvidence
        )
        val sendInitiation = decideWechatOcrCapture(
            pageText = "发红包\n金额 ¥3.50\n塞钱进红包",
            windowEvidence = windowEvidence
        )

        assertTrue(completedReceipt.shouldCapture)
        assertFalse(missingCompletion.shouldCapture)
        assertFalse(missingAmount.shouldCapture)
        assertFalse(chatMessage.shouldCapture)
        assertFalse(sendInitiation.shouldCapture)
        val fingerprint = requireNotNull(
            wechatOcrPaymentFingerprint(
                "Yellen的红包\n4.00元\n已存入零钱\n回复表情到聊天"
            )
        )
        assertEquals("Yellen", fingerprint.merchantTitle)
        assertEquals(400L, fingerprint.amountMinor)
        assertEquals("收入", fingerprint.transactionKindLabel)
        val guard = PaymentScreenOcrSessionGuard()
        guard.markProcessed(fingerprint)
        guard.resetCurrentFingerprint()
        assertFalse(guard.shouldProcess(fingerprint))
        assertTrue(
            guard.shouldProcess(
                fingerprint,
                hasNewMatchingNotification = true
            )
        )
        assertEquals(AutomaticCaptureVerification.Standard, completedReceipt.verification)
        assertTrue(isVerifiedWechatOcrResultActivity(windowEvidence.activityClassName))
        assertTrue(
            shouldAttemptWechatOcrFallback(
                packageName = BillSyncSource.WeChat.packageName,
                pageText = "返回\n更多",
                sdkInt = Build.VERSION_CODES.R,
                windowEvidence = windowEvidence
            )
        )
    }

    @Test
    fun wechatSentRedPacketOcrUsesStableFingerprintAcrossClaimStatusChanges() {
        val windowEvidence = WechatWindowEvidence(
            activityClassName =
                "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyNewDetailUI",
            isApplicationWindow = true
        )
        val waitingText = """
            Arfa😘的红包
            恭喜发财，大吉大利
            红包金额3.00元，等待对方领取
            未领取的红包，将于24小时后发起退款
        """.trimIndent()
        val claimedText = """
            Arfa😘的红包
            恭喜发财，大吉大利
            1个红包共3.00元
            Yellen
            3.00元
            11:22
        """.trimIndent()

        val waiting = decideWechatOcrCapture(waitingText, windowEvidence)
        val claimed = decideWechatOcrCapture(claimedText, windowEvidence)
        val preparation = decideWechatOcrCapture(
            "发红包\n金额 ¥3.00\n塞钱进红包",
            windowEvidence
        )
        val paymentPassword = decideWechatOcrCapture(
            "微信红包\n¥3.00\n支付密码\n零钱",
            windowEvidence
        )
        val incompleteClaimedDetail = decideWechatOcrCapture(
            "Arfa😘的红包\n1个红包共3.00元",
            windowEvidence
        )

        assertTrue(waiting.shouldCapture)
        assertTrue(claimed.shouldCapture)
        assertEquals(AutomaticCaptureVerification.Standard, waiting.verification)
        assertEquals(AutomaticCaptureVerification.Standard, claimed.verification)
        assertFalse(preparation.shouldCapture)
        assertFalse(paymentPassword.shouldCapture)
        assertFalse(incompleteClaimedDetail.shouldCapture)
        val waitingFingerprint = requireNotNull(wechatOcrPaymentFingerprint(waitingText))
        val claimedFingerprint = requireNotNull(wechatOcrPaymentFingerprint(claimedText))
        assertEquals(waitingFingerprint, claimedFingerprint)
        val guard = PaymentScreenOcrSessionGuard()
        guard.markProcessed(waitingFingerprint)
        guard.resetCurrentFingerprint()
        assertFalse(guard.shouldProcess(claimedFingerprint))
        assertTrue(
            guard.shouldProcess(
                claimedFingerprint,
                hasNewMatchingNotification = true
            )
        )
        val differentAmountFingerprint = requireNotNull(
            wechatOcrPaymentFingerprint(
                "Arfa😘的红包\n红包金额4.00元，等待对方领取\n" +
                    "未领取的红包，将于24小时后发起退款"
            )
        )
        assertTrue(guard.shouldProcess(differentAmountFingerprint))
        guard.markProcessed(differentAmountFingerprint)
        assertFalse(guard.shouldProcess(claimedFingerprint))
        assertEquals("红包", waitingFingerprint.merchantTitle)
        assertEquals(300L, waitingFingerprint.amountMinor)
        assertEquals("支出", waitingFingerprint.transactionKindLabel)
    }

    @Test
    fun successfulOcrSurfaceIsProcessedOnceUntilTransactionFingerprintChangesOrResets() {
        val guard = PaymentScreenOcrSessionGuard()
        val firstTransaction = requireNotNull(
            wechatOcrPaymentFingerprint("支付成功\n中国电信\n¥6.99\n返回商家")
        )
        val nextTransaction = requireNotNull(
            wechatOcrPaymentFingerprint("支付成功\n便利店\n¥12.00\n返回商家")
        )

        assertTrue(guard.shouldProcess(firstTransaction))
        guard.markProcessed(firstTransaction)
        assertFalse(guard.shouldProcess(firstTransaction))
        assertTrue(guard.shouldProcess(nextTransaction))

        guard.resetCurrentFingerprint()
        assertTrue(guard.shouldProcess(firstTransaction))
    }

    @Test
    fun sentRedPacketSessionGuardBoundsRememberedFingerprints() {
        val guard = PaymentScreenOcrSessionGuard()
        val fingerprints = (1..65).map { amount ->
            requireNotNull(
                wechatOcrPaymentFingerprint(
                    "Arfa的红包\n红包金额$amount.00元，等待对方领取\n" +
                        "未领取的红包，将于24小时后发起退款"
                )
            )
        }

        fingerprints.forEach(guard::markProcessed)
        guard.resetCurrentFingerprint()

        assertTrue(guard.shouldProcess(fingerprints.first()))
        assertFalse(guard.shouldProcess(fingerprints.last()))
    }

    @Test
    fun wechatOcrFingerprintIgnoresStatusBarChangesButTracksTransactionFields() {
        val first = wechatOcrPaymentFingerprint(
            "21:12\n0.99 KB/s\n支付成功\n中国电信\n¥6.99\n返回商家"
        )
        val sameTransaction = wechatOcrPaymentFingerprint(
            "21:14\n1.20 KB/s\n支付成功\n中国电信\n¥6.99\n返回商家"
        )
        val differentTransaction = wechatOcrPaymentFingerprint(
            "21:15\n支付成功\n便利店\n¥12.00\n返回商家"
        )

        assertEquals(first, sameTransaction)
        assertTrue(first != differentTransaction)
    }
}
