package com.bks.feature.account

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
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
import androidx.core.content.FileProvider
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.bks.R
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        val safeUrl = avatarUrl?.takeIf {
            it.startsWith("https://", ignoreCase = true) ||
            it.startsWith("http://", ignoreCase = true) ||
            it.startsWith("file://", ignoreCase = true) ||
            it.startsWith("content://", ignoreCase = true) ||
            it.startsWith("data:image/jpeg;base64,", ignoreCase = true) ||
            it.startsWith("data:image/png;base64,", ignoreCase = true)
        }
        val safeUrlHash = safeUrl?.stableHash()
        val previousUrlHash = preferences.getString(KEY_CURRENT_AVATAR_URL_HASH, null)
        if (previousUrlHash != null && previousUrlHash != safeUrlHash) clearCacheOnly()
        preferences.edit().apply {
            remove(LEGACY_KEY_CURRENT_AVATAR_URL)
            if (safeUrlHash == null) remove(KEY_CURRENT_AVATAR_URL_HASH) else putString(KEY_CURRENT_AVATAR_URL_HASH, safeUrlHash)
        }.apply()
        return safeUrl
    }

    fun prepareModel(avatarUrl: String?): Any? {
        val safeUrl = prepareUrl(avatarUrl) ?: return null
        if (!safeUrl.startsWith("data:image/", ignoreCase = true)) return safeUrl
        val encoded = safeUrl.substringAfter("base64,", missingDelimiterValue = "")
        return runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
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
    val safeModel = remember(avatarUrl, cache) { cache.prepareModel(avatarUrl) }
    val fallback = painterResource(R.drawable.aa_nav_profile)
    AsyncImage(
        model = safeModel,
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

internal suspend fun Context.readCompressedAvatarDataUrl(uri: Uri): String = withContext(Dispatchers.IO) {
    val decoded = ImageDecoder.decodeBitmap(
        ImageDecoder.createSource(contentResolver, uri)
    ) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
        val scale = minOf(
            1f,
            AVATAR_OUTPUT_SIZE.toFloat() / info.size.width,
            AVATAR_OUTPUT_SIZE.toFloat() / info.size.height
        )
        if (scale < 1f) {
            decoder.setTargetSize(
                (info.size.width * scale).toInt().coerceAtLeast(1),
                (info.size.height * scale).toInt().coerceAtLeast(1)
            )
        }
    }
    try {
        val bytes = ByteArrayOutputStream()
        var quality = 90
        do {
            bytes.reset()
            check(decoded.compress(Bitmap.CompressFormat.JPEG, quality, bytes))
            quality -= 10
        } while (bytes.size() > AVATAR_MAX_BYTES && quality >= 40)
        require(bytes.size() in 1..AVATAR_MAX_BYTES)
        "data:image/jpeg;base64,${Base64.getEncoder().encodeToString(bytes.toByteArray())}"
    } finally {
        decoded.recycle()
    }
}

internal fun Context.createAvatarCaptureUri(): Uri {
    val directory = File(cacheDir, AVATAR_CAPTURE_DIRECTORY).apply {
        check(exists() || mkdirs()) { "Avatar capture directory is unavailable" }
    }
    val file = File(directory, "avatar-${UUID.randomUUID()}.jpg")
    return FileProvider.getUriForFile(
        this,
        "$packageName.fileprovider",
        file
    )
}

internal fun Context.deleteAvatarCapture(uri: Uri) {
    runCatching { contentResolver.delete(uri, null, null) }
}

private const val AVATAR_OUTPUT_SIZE = 256
private const val AVATAR_MAX_BYTES = 256 * 1024
private const val AVATAR_CAPTURE_DIRECTORY = "avatar_captures"
