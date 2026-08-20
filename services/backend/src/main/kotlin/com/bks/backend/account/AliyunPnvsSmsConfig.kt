package com.bks.backend.account

internal class AliyunPnvsSmsConfig(
    val accessKeyId: String,
    val accessKeySecret: String,
    val signName: String,
    val templateCode: String,
    val schemeName: String = "",
    val endpoint: String = "https://dypnsapi.aliyuncs.com"
)
