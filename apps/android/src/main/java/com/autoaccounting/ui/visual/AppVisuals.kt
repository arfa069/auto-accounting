package com.autoaccounting.ui.visual

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.autoaccounting.R
import com.autoaccounting.data.local.DefaultCategories
import com.autoaccounting.data.local.TransactionKind

@Composable
fun AppWallpaper(
    @DrawableRes backgroundRes: Int,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier = modifier.fillMaxSize()) {
        Image(
            painter = painterResource(backgroundRes),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        content()
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
