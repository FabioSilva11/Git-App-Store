package com.saas.payment.gitappstore.data

import androidx.annotation.DrawableRes

data class CategoryItem(
    val id: String,
    val title: String,
    @param:DrawableRes val iconRes: Int,
)
