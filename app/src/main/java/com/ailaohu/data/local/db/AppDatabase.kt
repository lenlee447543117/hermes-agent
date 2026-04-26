package com.ailaohu.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.ailaohu.data.local.entity.ContactEntity

@Database(entities = [ContactEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
}
