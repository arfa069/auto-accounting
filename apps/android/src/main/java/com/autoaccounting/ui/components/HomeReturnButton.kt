package com.autoaccounting.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.autoaccounting.R

@Composable
fun HomeReturnButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(48.dp)
            .testTag("return-home")
    ) {
        Image(
            painter = painterResource(R.drawable.aa_return_home_art),
            contentDescription = "返回主页",
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(40.dp)
        )
    }
}
