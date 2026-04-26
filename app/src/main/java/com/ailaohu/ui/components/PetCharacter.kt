package com.ailaohu.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.ailaohu.R

@Composable
fun PetCharacter(
    isHappy: Boolean = false,
    modifier: Modifier = Modifier
) {
    val imageResource = if (isHappy) {
        R.drawable.happy
    } else {
        R.drawable.normal
    }

    Image(
        painter = painterResource(id = imageResource),
        contentDescription = if (isHappy) "开心的宠物" else "正常的宠物",
        modifier = modifier.size(120.dp)
    )
}
