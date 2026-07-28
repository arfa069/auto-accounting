package com.autoaccounting.feature.account

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WechatAvatarTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun cacheAcceptsSupportedRemoteLocalAndDataSources() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val preferences = context.getSharedPreferences(
            WechatAvatarCache.AVATAR_PREFERENCES,
            android.content.Context.MODE_PRIVATE
        )
        preferences.edit().clear().commit()
        preferences.edit()
            .putString(WechatAvatarCache.LEGACY_KEY_CURRENT_AVATAR_URL, "https://legacy.example/avatar.jpg")
            .commit()
        val cache = WechatAvatarCache(context)

        assertNull(cache.prepareUrl(null))
        assertEquals(
            "http://example.com/avatar.jpg",
            cache.prepareUrl("http://example.com/avatar.jpg")
        )
        assertEquals(
            "file:///tmp/avatar.jpg",
            cache.prepareUrl("file:///tmp/avatar.jpg")
        )
        assertEquals(
            "content://media/avatar.jpg",
            cache.prepareUrl("content://media/avatar.jpg")
        )
        assertEquals(
            "https://example.com/avatar.jpg",
            cache.prepareUrl("https://example.com/avatar.jpg")
        )
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),
            cache.prepareModel("data:image/jpeg;base64,/9j/") as ByteArray
        )
        assertEquals("wechat_avatars", cache.cacheDirectory.name)
        assertEquals(10L * 1024 * 1024, WechatAvatarCache.AVATAR_CACHE_MAX_BYTES)
        assertFalse(preferences.contains(WechatAvatarCache.LEGACY_KEY_CURRENT_AVATAR_URL))
        assertNotNull(preferences.getString(WechatAvatarCache.KEY_CURRENT_AVATAR_URL_HASH, null))
        assertFalse(preferences.all.values.any { it.toString().contains("example.com/avatar.jpg") })

        cache.clear()
    }

    @Test
    fun missingOrUnsafeAvatarStillDisplaysPlaceholderNode() {
        val cache = WechatAvatarCache(ApplicationProvider.getApplicationContext())
        composeRule.setContent {
            WechatAvatar(
                avatarUrl = "ftp://example.com/unsafe.jpg",
                cache = cache,
                contentDescription = "默认微信头像"
            )
        }

        composeRule.onNodeWithContentDescription("默认微信头像").assertIsDisplayed()
    }

    @Test
    fun cameraFileProviderIsRegisteredForApp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val provider = context.packageManager.resolveContentProvider(
            "${context.packageName}.fileprovider",
            android.content.pm.PackageManager.GET_META_DATA
        )

        assertNotNull(provider)
        assertFalse(provider!!.exported)
        assertTrue(provider.grantUriPermissions)
        assertTrue(
            provider.metaData.getInt("android.support.FILE_PROVIDER_PATHS") != 0
        )
    }
}
