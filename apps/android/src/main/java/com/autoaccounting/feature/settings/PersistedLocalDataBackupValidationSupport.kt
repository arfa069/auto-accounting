package com.autoaccounting.feature.settings

internal fun referencesExist(
    categoryId: String?,
    fundingAccountId: Long?,
    categoryIds: Set<String>,
    fundingAccountIds: Set<Long>
): Boolean =
    (categoryId == null || categoryId in categoryIds) &&
        (fundingAccountId == null || fundingAccountId in fundingAccountIds)

internal fun <T> List<T>.allDistinct(): Boolean = size == toSet().size
