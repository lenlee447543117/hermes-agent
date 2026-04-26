package com.ailaohu.di

import android.content.Context
import androidx.room.Room
import com.ailaohu.data.local.db.AppDatabase
import com.ailaohu.data.local.db.ContactDao
import com.ailaohu.service.sms.SmsFallbackService
import com.ailaohu.service.tts.TTSManager
import com.ailaohu.service.vlm.VLMCacheManager
import com.ailaohu.service.vlm.VLMService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ServiceModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "ailaohu_database"
        ).build()
    }

    @Provides
    fun provideContactDao(database: AppDatabase): ContactDao {
        return database.contactDao()
    }

    @Provides
    @Singleton
    fun provideVLMCacheManager(): VLMCacheManager {
        return VLMCacheManager()
    }
}
