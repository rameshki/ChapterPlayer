package com.chapterplayer.di

import com.chapterplayer.BuildConfig
import com.chapterplayer.data.remote.abs.AbsApiService
import com.chapterplayer.data.remote.plex.PlexApiService
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * We need two separate Retrofit instances: one for ABS and one for Plex.
     * The base URL is set dynamically per server, so we use a placeholder URL here.
     * The actual URL is injected via a custom OkHttp interceptor or by creating
     * Retrofit instances on demand in the repositories.
     *
     * For simplicity in v1, we use a shared Retrofit with a dummy base URL and
     * override it per-call using @Url parameters where needed.
     * The per-server base URL is passed as part of the request in the repository.
     */

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)

        // Add logging in debug builds only
        if (BuildConfig.DEBUG) {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }
            builder.addInterceptor(logging)
        }

        return builder.build()
    }

    /**
     * ABS Retrofit instance.
     * Base URL is a placeholder — the real URL is set per-server in BookRepositoryImpl
     * by creating a new Retrofit instance dynamically. However, we still need a
     * singleton Retrofit here for Hilt to provide AbsApiService.
     *
     * In practice the base URL must end with "/" for Retrofit to work correctly.
     * We use "http://localhost/" as the placeholder.
     */
    @Provides
    @Singleton
    @Named("abs")
    fun provideAbsRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl("http://localhost/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    @Named("plex")
    fun providePlexRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl("http://localhost/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideAbsApiService(@Named("abs") retrofit: Retrofit): AbsApiService =
        retrofit.create(AbsApiService::class.java)

    @Provides
    @Singleton
    fun providePlexApiService(@Named("plex") retrofit: Retrofit): PlexApiService =
        retrofit.create(PlexApiService::class.java)
}
