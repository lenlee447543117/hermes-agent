package com.ailaohu.di

import com.ailaohu.BuildConfig
import com.ailaohu.data.remote.api.AutoGlmApiService
import com.ailaohu.data.remote.api.HermesApiService
import com.ailaohu.data.remote.api.HermesCronApiService
import com.ailaohu.data.remote.api.SmsApiService
import com.ailaohu.data.remote.api.VlmApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val VLM_BASE_URL = "https://dashscope.aliyuncs.com/"
    private const val AUTOGLM_BASE_URL = "https://open.bigmodel.cn/api/paas/v4/"
    private const val LLM_BASE_URL = "https://api.scnet.cn/api/llm/v1/"
    private const val HERMES_BASE_URL = "http://192.168.3.34:8642/"

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
            .build()
    }

    @Provides
    @Singleton
    @Named("vlm")
    fun provideVlmRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(VLM_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    @Named("autoglm")
    fun provideAutoGlmRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(AUTOGLM_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    @Named("llm")
    fun provideLlmRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(LLM_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    @Named("vlm")
    fun provideVlmApiServiceNamed(@Named("vlm") retrofit: Retrofit): VlmApiService {
        return retrofit.create(VlmApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideVlmApiService(@Named("vlm") retrofit: Retrofit): VlmApiService {
        return retrofit.create(VlmApiService::class.java)
    }

    @Provides
    @Singleton
    @Named("autoglm-vlm")
    fun provideAutoGlmVlmApiService(@Named("autoglm") retrofit: Retrofit): VlmApiService {
        return retrofit.create(VlmApiService::class.java)
    }

    @Provides
    @Singleton
    @Named("autoglm")
    fun provideAutoGlmApiServiceNamed(@Named("autoglm") retrofit: Retrofit): AutoGlmApiService {
        return retrofit.create(AutoGlmApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideAutoGlmApiService(@Named("autoglm") retrofit: Retrofit): AutoGlmApiService {
        return retrofit.create(AutoGlmApiService::class.java)
    }

    @Provides
    @Singleton
    @Named("hermes")
    fun provideHermesOkHttpClient(): OkHttpClient {
        val authInterceptor = Interceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("Authorization", "Bearer ${BuildConfig.HERMES_API_KEY}")
                .build()
            chain.proceed(request)
        }
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
            .build()
    }

    @Provides
    @Singleton
    @Named("hermes")
    fun provideHermesRetrofit(@Named("hermes") okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(HERMES_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideHermesApiService(@Named("hermes") retrofit: Retrofit): HermesApiService {
        return retrofit.create(HermesApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideHermesCronApiService(@Named("hermes") retrofit: Retrofit): HermesCronApiService {
        return retrofit.create(HermesCronApiService::class.java)
    }
}
