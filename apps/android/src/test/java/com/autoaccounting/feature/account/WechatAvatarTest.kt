package com.autoaccounting.feature.account

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun cacheAcceptsOnlyHttpsAndUsesDedicatedTenMegabyteDirectory() {
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
        assertNull(cache.prepareUrl("http://example.com/avatar.jpg"))
        assertNull(cache.prepareUrl("file:///tmp/avatar.jpg"))
        assertEquals(
            "https://example.com/avatar.jpg",
            cache.prepareUrl("https://example.com/avatar.jpg")
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
                avatarUrl = "http://example.com/unsafe.jpg",
                cache = cache,
                contentDescription = "默认微信头像"
            )
        }

        composeRule.onNodeWithContentDescription("默认微信头像").assertIsDisplayed()
    }
}
