package com.autoaccounting.feature.account

import android.content.Context
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.autoaccounting.R
import java.security.MessageDigest
import java.util.Base64
import okio.Path.Companion.toOkioPath

class WechatAvatarCache(
    context: Context
) {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(
        AVATAR_PREFERENCES,
        Context.MODE_PRIVATE
    )
    internal val cacheDirectory = applicationContext.cacheDir.resolve(AVATAR_CACHE_DIRECTORY)

    val imageLoader: ImageLoader = ImageLoader.Builder(applicationContext)
        .components { add(OkHttpNetworkFetcherFactory()) }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDirectory.toOkioPath())
                .maxSizeBytes(AVATAR_CACHE_MAX_BYTES)
                .build()
        }
        .build()

    init {
        if (preferences.contains(LEGACY_KEY_CURRENT_AVATAR_URL)) {
            preferences.edit().remove(LEGACY_KEY_CURRENT_AVATAR_URL).apply()
            clearCacheOnly()
        }
    }

    fun prepareUrl(avatarUrl: String?): String? {
        val safeUrl = avatarUrl?.takeIf { it.startsWith("https://", ignoreCase = true) }
        val safeUrlHash = safeUrl?.stableHash()
        val previousUrlHash = preferences.getString(KEY_CURRENT_AVATAR_URL_HASH, null)
        if (previousUrlHash != null && previousUrlHash != safeUrlHash) clearCacheOnly()
        preferences.edit().apply {
            remove(LEGACY_KEY_CURRENT_AVATAR_URL)
            if (safeUrlHash == null) remove(KEY_CURRENT_AVATAR_URL_HASH) else putString(KEY_CURRENT_AVATAR_URL_HASH, safeUrlHash)
        }.apply()
        return safeUrl
    }

    fun clear() {
        preferences.edit().clear().apply()
        clearCacheOnly()
    }

    private fun clearCacheOnly() {
        imageLoader.memoryCache?.clear()
        imageLoader.diskCache?.clear()
    }

    internal companion object {
        const val AVATAR_PREFERENCES = "wechat_avatar_cache"
        const val KEY_CURRENT_AVATAR_URL_HASH = "current_avatar_url_hash"
        const val LEGACY_KEY_CURRENT_AVATAR_URL = "current_avatar_url"
        const val AVATAR_CACHE_DIRECTORY = "wechat_avatars"
        const val AVATAR_CACHE_MAX_BYTES = 10L * 1024 * 1024
    }
}

private fun String.stableHash(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

@Composable
fun WechatAvatar(
    avatarUrl: String?,
    cache: WechatAvatarCache,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    contentDescription: String = "微信头像"
) {
    val safeUrl = remember(avatarUrl, cache) { cache.prepareUrl(avatarUrl) }
    val fallback = painterResource(R.drawable.aa_nav_profile)
    AsyncImage(
        model = safeUrl,
        contentDescription = contentDescription,
        imageLoader = cache.imageLoader,
        placeholder = fallback,
        error = fallback,
        fallback = fallback,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
    )
}

@Composable
fun rememberWechatAvatarCache(): WechatAvatarCache {
    val context = LocalContext.current
    return remember(context.applicationContext) { WechatAvatarCache(context.applicationContext) }
}
