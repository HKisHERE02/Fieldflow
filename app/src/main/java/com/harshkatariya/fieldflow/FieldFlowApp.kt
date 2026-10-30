package com.harshkatariya.fieldflow

import android.app.Application
import com.harshkatariya.fieldflow.core.Wire
import com.harshkatariya.fieldflow.data.FieldApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@HiltAndroidApp class FieldFlowApp : Application()
@Module @InstallIn(SingletonComponent::class) object NetworkModule {
    @Provides @Singleton fun api(): FieldApi = Retrofit.Builder().baseUrl(BuildConfig.API_URL)
        .client(OkHttpClient.Builder().callTimeout(45, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS).build())
        .addConverterFactory(Wire.asConverterFactory("application/json".toMediaType())).build().create(FieldApi::class.java)
}
