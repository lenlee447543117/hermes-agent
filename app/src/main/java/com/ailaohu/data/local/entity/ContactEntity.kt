package com.ailaohu.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val wechatRemarkPinyin: String,
    val wechatRemarkChinese: String,
    val callType: String,
    val tileOrder: Int,
    val isActive: Boolean = true,
    val childPhone: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
