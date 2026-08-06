package com.autoaccounting.feature.billsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BillPageParserTest {
    @Test
    fun parsesWechatBillPageText() {
        val entries = BillPageParser().parse(
            source = BillSyncSource.WeChat,
            pageText = """
                2026-07-08 12:20 午餐 支出 ¥35.90 微信零钱
                2026-07-08 21:10 退款到账 退款 ¥25.90 微信零钱
            """.trimIndent()
        )

        assertEquals(2, entries.size)
        assertEquals("午餐", entries[0].merchantTitle)
        assertEquals(3590L, entries[0].amountMinor)
        assertEquals("支出", entries[0].transactionKindLabel)
        assertEquals("微信零钱", entries[0].fundingAccountLabel)
        assertEquals("退款到账", entries[1].merchantTitle)
        assertEquals("退款", entries[1].transactionKindLabel)
    }

    @Test
    fun parsesAlipayBillPageText() {
        val entries = BillPageParser().parse(
            source = BillSyncSource.Alipay,
            pageText = "2026-07-08 08:10 地铁出行 支出 6.00元 支付宝余额"
        )

        assertEquals(1, entries.size)
        assertEquals("支付宝", entries[0].sourceLabel)
        assertEquals("地铁出行", entries[0].merchantTitle)
        assertEquals(600L, entries[0].amountMinor)
        assertEquals("支付宝余额", entries[0].fundingAccountLabel)
    }

    @Test
    fun parsesAlipayPaymentMessageBoxRecord() {
        val entries = BillPageParser().parse(
            source = BillSyncSource.Alipay,
            pageText = """
                支付信息
                消息盒子
                支付成功
                商户：便利店
                金额
                ¥20.00
                付款方式
                支付宝余额
                交易时间
                2026-07-10 09:12
            """.trimIndent()
        )

        assertEquals(1, entries.size)
        assertEquals("支付宝", entries.single().sourceLabel)
        assertEquals("便利店", entries.single().merchantTitle)
        assertEquals(2000L, entries.single().amountMinor)
        assertEquals("支出", entries.single().transactionKindLabel)
        assertEquals("支付宝余额", entries.single().fundingAccountLabel)
        assertEquals("2026-07-10 09:12", entries.single().transactionTimeText)
    }

    @Test
    fun parsesWechatPaymentRecordSurface() {
        val entries = BillPageParser().parse(
            source = BillSyncSource.WeChat,
            pageText = """
                微信支付
                账单详情
                当前状态
                支付成功
                收款方
                早餐店
                金额
                ¥7.50
                付款方式
                零钱
                交易时间
                2026年7月10日 08:05
            """.trimIndent()
        )

        assertEquals(1, entries.size)
        assertEquals("早餐店", entries.single().merchantTitle)
        assertEquals(750L, entries.single().amountMinor)
        assertEquals("支出", entries.single().transactionKindLabel)
        assertEquals("零钱", entries.single().fundingAccountLabel)
        assertEquals("2026-07-10 08:05", entries.single().transactionTimeText)
    }

    @Test
    fun parsesMerchantQrPaymentRecordSurface() {
        val entries = BillPageParser().parse(
            source = BillSyncSource.Alipay,
            pageText = """
                账单详情
                扫码支付
                交易成功
                收款方：门店
                交易金额
                ¥12.34
                交易时间
                2026-07-10 11:12
            """.trimIndent()
        )

        assertEquals(1, entries.size)
        assertEquals("门店", entries.single().merchantTitle)
        assertEquals(1234L, entries.single().amountMinor)
        assertEquals("支出", entries.single().transactionKindLabel)
    }

    @Test
    fun alipayPaymentResultPrefersMerchantOverProductName() {
        val entry = BillPageParser().parse(
            source = BillSyncSource.Alipay,
            pageText = """
                支付成功
                商品
                套餐名称
                收款方
                真实商户
                金额
                ¥20.00
                付款方式
                支付宝余额
            """.trimIndent(),
            fallbackTransactionTimeText = "2026-07-31 12:00"
        ).single()

        assertEquals("真实商户", entry.merchantTitle)
        assertEquals("支付宝余额", entry.fundingAccountLabel)
        assertFalse(entry.merchantTitleFromFallback)
        assertFalse(entry.fundingAccountFromFallback)
    }

    @Test
    fun missingMerchantLabelDoesNotConsumePaymentMethodAsMerchant() {
        val entry = BillPageParser().parse(
            source = BillSyncSource.Alipay,
            pageText = """
                支付成功
                收款方
                付款方式
                支付宝余额
                金额
                ¥20.00
            """.trimIndent(),
            fallbackTransactionTimeText = "2026-07-31 12:00"
        ).single()

        assertEquals("支付宝支付", entry.merchantTitle)
        assertEquals("支付宝余额", entry.fundingAccountLabel)
        assertTrue(entry.merchantTitleFromFallback)
        assertFalse(entry.fundingAccountFromFallback)
    }

    @Test
    fun parsesAlipayBillDetailHeaderAsMerchant() {
        val entries = BillPageParser().parse(
            source = BillSyncSource.Alipay,
            pageText = """
                账单详情
                便利店订单
                支出 ¥20.00
                交易成功
                支付时间
                2026-07-10 09:12
                付款方式
                支付宝余额
            """.trimIndent()
        )

        assertEquals(1, entries.size)
        assertEquals("便利店订单", entries.single().merchantTitle)
        assertEquals(2000L, entries.single().amountMinor)
        assertEquals("支出", entries.single().transactionKindLabel)
    }

    @Test
    fun parsesWechatTransferAndRedPacketRecordSurfaces() {
        val transfer = BillPageParser().parse(
            source = BillSyncSource.WeChat,
            pageText = """
                转账记录
                转账给测试对象 ¥0.01
                付款方式 零钱
                交易时间 2026-07-10 10:01
            """.trimIndent()
        )
        val redPacket = BillPageParser().parse(
            source = BillSyncSource.WeChat,
            pageText = """
                红包记录
                收到测试对象的红包
                ¥0.66
                交易时间 2026-07-10 10:02
            """.trimIndent()
        )

        assertEquals(1, transfer.size)
        assertEquals("测试对象", transfer.single().merchantTitle)
        assertEquals(1L, transfer.single().amountMinor)
        assertEquals("支出", transfer.single().transactionKindLabel)
        assertEquals(1, redPacket.size)
        assertEquals("测试对象", redPacket.single().merchantTitle)
        assertEquals(66L, redPacket.single().amountMinor)
        assertEquals("收入", redPacket.single().transactionKindLabel)
    }

    @Test
    fun parsesWechatTransferSuccessWaitingForRecipient() {
        val entries = BillPageParser().parse(
            source = BillSyncSource.WeChat,
            pageText = """
                支付成功
                待测试对象确认收款
                ¥0.05
                完成
            """.trimIndent(),
            fallbackTransactionTimeText = "2026-07-10 20:31"
        )

        assertEquals(1, entries.size)
        assertEquals("测试对象", entries.single().merchantTitle)
        assertEquals("支出", entries.single().transactionKindLabel)
    }

    @Test
    fun parsesWechatReceivedRedPacketSuccessPage() {
        val entries = BillPageParser().parse(
            source = BillSyncSource.WeChat,
            pageText = """
                Yellen的红包
                恭喜发财，大吉大利
                4.00元
                已存入零钱，可用于发红包
                回复表情到聊天
            """.trimIndent(),
            fallbackTransactionTimeText = "2026-07-14 20:51"
        )

        assertEquals(1, entries.size)
        assertEquals("Yellen", entries.single().merchantTitle)
        assertEquals(400L, entries.single().amountMinor)
        assertEquals("收入", entries.single().transactionKindLabel)
        assertEquals("微信零钱", entries.single().fundingAccountLabel)
        assertEquals("2026-07-14 20:51", entries.single().transactionTimeText)
    }

    @Test
    fun parsesWechatSentRedPacketBeforeAndAfterRecipientClaimsIt() {
        val waiting = BillPageParser().parse(
            source = BillSyncSource.WeChat,
            pageText = """
                Arfa😘的红包
                恭喜发财，大吉大利
                红包金额3.00元，等待对方领取
                未领取的红包，将于24小时后发起退款
            """.trimIndent(),
            fallbackTransactionTimeText = "2026-07-14 11:21"
        )
        val claimed = BillPageParser().parse(
            source = BillSyncSource.WeChat,
            pageText = """
                Arfa😘的红包
                恭喜发财，大吉大利
                1个红包共3.00元
                Yellen
                3.00元
                11:22
            """.trimIndent(),
            fallbackTransactionTimeText = "2026-07-14 11:23"
        )

        listOf(waiting.single(), claimed.single()).forEach { entry ->
            assertEquals("红包", entry.merchantTitle)
            assertEquals(300L, entry.amountMinor)
            assertEquals("支出", entry.transactionKindLabel)
            assertEquals("微信零钱", entry.fundingAccountLabel)
        }
    }

    @Test
    fun ignoresWechatRedPacketChatTextWithoutCompletedReceiptSignature() {
        val chatText = """
            聊天
            Yellen的红包
            4.00元
            已存入零钱
            发送消息
        """.trimIndent()
        val entries = BillPageParser().parse(
            source = BillSyncSource.WeChat,
            pageText = chatText,
            fallbackTransactionTimeText = "2026-07-14 20:51"
        )

        assertTrue(entries.isEmpty())
        assertEquals(
            BillSyncPageObservation.Ignored,
            observeBillSyncPage(BillSyncSource.WeChat, chatText)
        )
    }

    @Test
    fun ignoresUnsupportedOrPaymentInitiationSurfaces() {
        val chat = BillPageParser().parse(
            source = BillSyncSource.WeChat,
            pageText = "聊天\n消息\n微信支付助手\n今晚吃什么\n¥20.00\n2026-07-10 12:00"
        )
        val cashier = BillPageParser().parse(
            source = BillSyncSource.Alipay,
            pageText = "支付宝\n收银台\n立即付款\n确认支付\n¥20.00\n2026-07-10 12:00"
        )

        assertTrue(chat.isEmpty())
        assertTrue(cashier.isEmpty())
    }

    @Test
    fun ignoresAlipayHomeRecentPaymentMessages() {
        val homeText = """
            支付宝 首页
            扫一扫 收付款 出行 卡包
            花呗 手机营业厅 余额宝 转账
            最近消息 25条新消息
            aitoken-小店 付款成功 ¥85.00 1小时前
            拼多多平台商户 付款成功 ¥5.39 2小时前
            首页 理财 消息 我的
        """.trimIndent()

        assertTrue(BillPageParser().parse(BillSyncSource.Alipay, homeText).isEmpty())
        assertEquals(
            BillSyncPageObservation.Ignored,
            observeBillSyncPage(BillSyncSource.Alipay, homeText)
        )
    }

    @Test
    fun ignoresBalanceAmountWhenTransactionAmountIsAlsoVisible() {
        val entries = BillPageParser().parse(
            source = BillSyncSource.Alipay,
            pageText = """
                支付信息
                支付成功
                商户：便利店
                金额 ¥0.01
                余额 ¥5.00
                交易时间 2026-07-10 09:12
            """.trimIndent()
        )

        assertEquals(1, entries.size)
        assertEquals(1L, entries.single().amountMinor)
    }

    @Test
    fun completedPaymentResultUsesSafeSourceTitleWhenCounterpartyIsMissing() {
        val entries = BillPageParser().parse(
            source = BillSyncSource.Alipay,
            pageText = """
                支付信息
                支付成功
                金额 ¥20.00
                交易时间 2026-07-10 09:12
            """.trimIndent()
        )

        assertEquals(1, entries.size)
        assertEquals("支付宝支付", entries.single().merchantTitle)
        assertTrue(entries.single().merchantTitleFromFallback)
    }

    @Test
    fun completedPaymentResultUsesCaptureTimeWhenPageHasNoTransactionTime() {
        val entries = BillPageParser().parse(
            source = BillSyncSource.Alipay,
            pageText = "支付成功\n收款方：测试门店\n¥0.01",
            fallbackTransactionTimeText = "2026-07-10 12:34"
        )

        assertEquals(1, entries.size)
        assertEquals(1L, entries.single().amountMinor)
        assertEquals("2026-07-10 12:34", entries.single().transactionTimeText)
    }

    @Test
    fun parsesAlipayCompletedPaymentWordingAsExpense() {
        val entry = BillPageParser().parse(
            source = BillSyncSource.Alipay,
            pageText = """
                完成支付
                收款方
                便利店
                金额
                ¥20.00
                付款方式
                支付宝余额
            """.trimIndent(),
            fallbackTransactionTimeText = "2026-08-06 12:34"
        ).single()

        assertEquals("支出", entry.transactionKindLabel)
        assertEquals(2_000L, entry.amountMinor)
        assertEquals("便利店", entry.merchantTitle)
    }

    @Test
    fun completedPaymentResultIgnoresPromotionalAmountsBelowPrimaryAmount() {
        val entries = BillPageParser().parse(
            source = BillSyncSource.WeChat,
            pageText = """
                支付成功
                测试商户
                ¥12.34
                摇一摇，有优惠
                打车券 2元 免费领
                缴费券 1元 免费领
            """.trimIndent(),
            fallbackTransactionTimeText = "2026-07-10 14:05"
        )

        assertEquals(1, entries.size)
        assertEquals(1_234L, entries.single().amountMinor)
        assertEquals("测试商户", entries.single().merchantTitle)
    }

    @Test
    fun completedPaymentResultPrefersExplicitActualAmountOverOriginalPrice() {
        val entries = BillPageParser().parse(
            source = BillSyncSource.WeChat,
            pageText = """
                支付成功
                测试商户
                原价 ¥20.00
                实付 ¥10.40
                交易详情
            """.trimIndent(),
            fallbackTransactionTimeText = "2026-07-17 01:30"
        )

        assertEquals(1, entries.size)
        assertEquals(1_040L, entries.single().amountMinor)
    }

    @Test
    fun parsesStructuredFieldsFromWechatMerchantHistoryDetail() {
        val entry = BillPageParser().parse(
            source = BillSyncSource.WeChat,
            pageText = """
                肯德基
                -¥10.40
                当前状态
                支付成功
                支付时间
                2026年07月12日 09:16:07
                商品
                KFC_PREWX10012651367114169061602
                商户全称
                百胜餐饮（广东）有限公司
                收单机构
                财付通支付科技有限公司
                支付方式
                零钱
                交易单号
                4500000279202607127462299679
                商户单号
                WX10012651367114169061602
            """.trimIndent()
        ).single()

        assertEquals("KFC_PREWX10012651367114169061602", entry.merchantTitle)
        assertEquals(1_040L, entry.amountMinor)
        assertEquals("2026-07-12 09:16", entry.transactionTimeText)
        assertEquals("零钱", entry.fundingAccountLabel)
        assertTrue(entry.parsedFields.contains("当前状态=支付成功"))
        assertTrue(entry.parsedFields.contains("商品=KFC_PREWX10012651367114169061602"))
        assertTrue(entry.parsedFields.contains("商品名称=KFC_PREWX10012651367114169061602"))
        assertTrue(entry.parsedFields.contains("商户或收款方=百胜餐饮（广东）有限公司"))
        assertTrue(entry.parsedFields.contains("交易单号=4500000279202607127462299679"))
        assertTrue(entry.parsedFields.contains("商户单号=WX10012651367114169061602"))
    }

    @Test
    fun parsesCompletedWechatTransferAsExpense() {
        val entry = BillPageParser().parse(
            source = BillSyncSource.WeChat,
            pageText = """
                转账-转给测试对象
                ¥-7.00
                当前状态
                对方已收钱
                转账说明
                微信转账
                转账时间
                2026年7月19日 15:10:21
                收款时间
                2026年7月19日 15:11:38
                支付方式
                测试银行卡
                转账单号
                10000000000000000001
            """.trimIndent()
        ).single()

        assertEquals("测试对象", entry.merchantTitle)
        assertEquals(700L, entry.amountMinor)
        assertEquals("支出", entry.transactionKindLabel)
        assertEquals("2026-07-19 15:10", entry.transactionTimeText)
        assertEquals("测试银行卡", entry.fundingAccountLabel)
        assertTrue(entry.parsedFields.contains("当前状态=对方已收钱"))
        assertTrue(entry.parsedFields.contains("交易单号=10000000000000000001"))
    }

    @Test
    fun parsesCompletedWechatRefundFields() {
        val entry = BillPageParser().parse(
            source = BillSyncSource.WeChat,
            pageText = """
                转账-退款
                ¥+0.05
                退款状态
                已退款
                退款时间
                2026年7月15日 04:54:51
                退款方式
                零钱
                退款单号
                10000000000000000002
                原订单
                查看原订单
            """.trimIndent()
        ).single()

        assertEquals("转账-退款", entry.merchantTitle)
        assertEquals(5L, entry.amountMinor)
        assertEquals("退款", entry.transactionKindLabel)
        assertEquals("2026-07-15 04:54", entry.transactionTimeText)
        assertEquals("零钱", entry.fundingAccountLabel)
        assertTrue(entry.parsedFields.contains("当前状态=已退款"))
        assertTrue(entry.parsedFields.contains("交易单号=10000000000000000002"))
    }

    @Test
    fun currentStatusPaymentSuccessOverridesUnrelatedIncomeText() {
        val entry = BillPageParser().parse(
            source = BillSyncSource.WeChat,
            pageText = """
                当前状态
                支付成功
                收入统计
                -¥224.00
                支付时间
                2026年06月01日 16:57:25
                商品
                测试商品
            """.trimIndent()
        ).single()

        assertEquals("支出", entry.transactionKindLabel)
    }

    @Test
    fun fallsBackToReceiptNoteAndJoinsWrappedWechatTransferOrderId() {
        val entry = BillPageParser().parse(
            source = BillSyncSource.WeChat,
            pageText = """
                扫二维码付款-给陈波
                -¥9.00
                当前状态
                支付成功
                收款方备注
                二维码收款
                支付方式
                零钱
                转账时间
                2026年7月12日 08:57:03
                转账单号
                10001073012026071201842745332618
                0
            """.trimIndent()
        ).single()

        assertEquals("扫二维码付款-给陈波", entry.merchantTitle)
        assertTrue(entry.parsedFields.contains("商品=二维码收款"))
        assertTrue(entry.parsedFields.contains("商品名称=扫二维码付款-给陈波"))
        assertTrue(entry.parsedFields.contains("商户或收款方=扫二维码付款-给陈波"))
        assertTrue(entry.parsedFields.contains("交易单号=100010730120260712018427453326180"))
    }

    @Test
    fun parsesWechatMerchantAndAmountFromStrongSuccessPageLayout() {
        val entries = BillPageParser().parse(
            source = BillSyncSource.WeChat,
            pageText = """
                21:12
                0.99 KB/s 5G 91%
                支付成功
                中国电信
                ¥6.99
                返回商家
            """.trimIndent(),
            fallbackTransactionTimeText = "2026-07-13 21:12"
        )

        assertEquals(1, entries.size)
        assertEquals("中国电信", entries.single().merchantTitle)
        assertEquals(699L, entries.single().amountMinor)
        assertFalse(entries.single().merchantTitleFromFallback)
    }

    @Test
    fun observesSupportedPaymentRecordsAndBlockedPaymentInitiation() {
        assertEquals(
            BillSyncPageObservation.PaymentResult,
            observeBillSyncPage(
                source = BillSyncSource.Alipay,
                pageText = """
                    支付信息
                    支付成功
                    交易时间 2026-07-10 09:12
                    金额 ¥20.00
                """.trimIndent()
            )
        )
        assertEquals(
            BillSyncPageObservation.BlockedPaymentInitiation,
            observeBillSyncPage(
                source = BillSyncSource.Alipay,
                pageText = "收银台\n立即付款\n确认支付\n¥20.00"
            )
        )
        assertEquals(
            BillSyncPageObservation.Ignored,
            observeBillSyncPage(
                source = BillSyncSource.WeChat,
                pageText = "聊天\n消息\n微信支付助手\n今晚吃什么\n¥20.00"
            )
        )
    }
}
