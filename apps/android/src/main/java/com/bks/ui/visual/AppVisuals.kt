package com.bks.ui.visual

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.bks.R
import com.bks.data.local.DefaultCategories
import com.bks.data.local.TransactionKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val WALLPAPER_CACHE_MAX_BYTES = 21 * 1024 * 1024
private const val DECORATIVE_IMAGE_CACHE_MAX_BYTES = 2 * 1024 * 1024
private const val WALLPAPER_FORCED_SAMPLE_SIZE = 2
private val WallpaperPlaceholder = Color(0xFFFEF8ED)

private class ResourceImageCache(
    maxSizeBytes: Int,
    private val minimumSampleSize: Int = 1
) {
    private val decodeMutex = Mutex()
    private val images = object : LruCache<Int, ImageBitmap>(maxSizeBytes) {
        override fun sizeOf(key: Int, value: ImageBitmap): Int =
            (value.width.toLong() * value.height * 4L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
    }

    fun get(@DrawableRes resourceId: Int): ImageBitmap? = images.get(resourceId)

    suspend fun load(resources: Resources, @DrawableRes resourceId: Int): ImageBitmap? =
        withContext(Dispatchers.IO) {
            decodeMutex.withLock {
                images.get(resourceId) ?: decodeResource(resources, resourceId)
                    ?.asImageBitmap()
                    ?.also { images.put(resourceId, it) }
            }
        }

    private fun decodeResource(resources: Resources, @DrawableRes resourceId: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(resources, resourceId, bounds)

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateDecodeSampleSize(
                sourceWidth = bounds.outWidth,
                sourceHeight = bounds.outHeight,
                targetWidth = resources.displayMetrics.widthPixels,
                targetHeight = resources.displayMetrics.heightPixels,
                minimumSampleSize = minimumSampleSize
            )
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeResource(resources, resourceId, options)?.also {
            it.prepareToDraw()
        }
    }
}

private val WallpaperImageCache = ResourceImageCache(
    maxSizeBytes = WALLPAPER_CACHE_MAX_BYTES,
    minimumSampleSize = WALLPAPER_FORCED_SAMPLE_SIZE
)
private val DecorativeImageCache = ResourceImageCache(DECORATIVE_IMAGE_CACHE_MAX_BYTES)

@Suppress("ComplexCondition")
internal fun calculateDecodeSampleSize(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
    minimumSampleSize: Int = 1
): Int {
    if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) return 1

    var sampleSize = minimumSampleSize.coerceAtLeast(1)
    while (
        sourceWidth / (sampleSize * 2) >= targetWidth &&
        sourceHeight / (sampleSize * 2) >= targetHeight
    ) {
        sampleSize *= 2
    }
    return sampleSize
}

@Composable
fun AppWallpaper(
    @DrawableRes backgroundRes: Int,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val resources = LocalContext.current.applicationContext.resources
    val wallpaper = produceState(
        initialValue = WallpaperImageCache.get(backgroundRes),
        key1 = backgroundRes,
        key2 = resources
    ) {
        value = WallpaperImageCache.load(resources, backgroundRes)
    }.value

    Box(modifier = modifier.fillMaxSize()) {
        if (wallpaper == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(WallpaperPlaceholder)
            )
        } else {
            Image(
                bitmap = wallpaper,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        content()
    }
}

@Composable
fun CachedResourceImage(
    @DrawableRes imageRes: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit
) {
    val resources = LocalContext.current.applicationContext.resources
    val image = produceState(
        initialValue = DecorativeImageCache.get(imageRes),
        key1 = imageRes,
        key2 = resources
    ) {
        value = DecorativeImageCache.load(resources, imageRes)
    }.value

    Box(modifier = modifier) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun CategoryArtwork(
    categoryId: String? = null,
    categoryName: String? = null,
    transactionKind: TransactionKind? = null,
    modifier: Modifier = Modifier
) {
    Image(
        painter = painterResource(
            categoryArtworkRes(
                categoryId = categoryId,
                categoryName = categoryName,
                transactionKind = transactionKind
            )
        ),
        contentDescription = categoryName,
        contentScale = ContentScale.Fit,
        modifier = modifier
    )
}

@DrawableRes
fun categoryArtworkRes(
    categoryId: String? = null,
    categoryName: String? = null,
    transactionKind: TransactionKind? = null
): Int {
    val resolvedId = categoryId
        ?: categoryName?.let { DefaultCategories.idForName(it, transactionKind) }
    return when (resolvedId) {
        "food" -> R.drawable.aa_cat_food
        "shopping" -> R.drawable.aa_cat_shopping
        "daily" -> R.drawable.aa_cat_daily
        "transport" -> R.drawable.aa_cat_transport
        "vegetables" -> R.drawable.aa_cat_vegetables
        "fruit" -> R.drawable.aa_cat_fruit
        "snacks" -> R.drawable.aa_cat_snacks
        "sports" -> R.drawable.aa_cat_sports
        "entertainment" -> R.drawable.aa_cat_entertainment
        "communication" -> R.drawable.aa_cat_communication
        "clothing" -> R.drawable.aa_cat_clothing
        "beauty" -> R.drawable.aa_cat_beauty
        "housing" -> R.drawable.aa_cat_housing
        "family" -> R.drawable.aa_cat_family
        "social" -> R.drawable.aa_cat_social
        "travel" -> R.drawable.aa_cat_travel
        "tobacco_alcohol" -> R.drawable.aa_cat_tobacco_alcohol
        "digital" -> R.drawable.aa_cat_digital
        "car" -> R.drawable.aa_cat_car
        "healthcare" -> R.drawable.aa_cat_medical
        "books" -> R.drawable.aa_cat_books
        "study" -> R.drawable.aa_cat_study
        "pets" -> R.drawable.aa_cat_pets
        "cash_gift_expense" -> R.drawable.aa_cat_cash_gift_expense
        "gifts" -> R.drawable.aa_cat_gifts
        "office" -> R.drawable.aa_cat_office
        "repair" -> R.drawable.aa_cat_repair
        "donation" -> R.drawable.aa_cat_donation
        "lottery" -> R.drawable.aa_cat_lottery
        "red_packet_expense" -> R.drawable.aa_cat_red_packet_expense
        "courier" -> R.drawable.aa_cat_courier
        "other_expense" -> R.drawable.aa_cat_other_expense
        "repayment" -> R.drawable.aa_cat_repayment
        "lending" -> R.drawable.aa_cat_lending
        "drinks" -> R.drawable.aa_cat_drinks
        "fandom" -> R.drawable.aa_cat_fandom
        "games" -> R.drawable.aa_cat_games
        "salary" -> R.drawable.aa_cat_salary
        "red_packet_income" -> R.drawable.aa_cat_red_packet_income
        "rent_income" -> R.drawable.aa_cat_rent_income
        "cash_gift_income" -> R.drawable.aa_cat_cash_gift_income
        "dividends" -> R.drawable.aa_cat_dividends
        "investment_income" -> R.drawable.aa_cat_investment_income
        "year_end_bonus" -> R.drawable.aa_cat_year_end_bonus
        "other_income" -> R.drawable.aa_cat_other_income
        "borrowing" -> R.drawable.aa_cat_borrowing
        "payment_received" -> R.drawable.aa_cat_payment_received
        "refund" -> R.drawable.aa_category_refund
        else -> R.drawable.aa_category_uncategorized
    }
}
