package com.waveguard.di

import android.content.Context
import com.waveguard.data.local.AlertDao
import com.waveguard.data.local.CsiDao
import com.waveguard.data.local.WaveGuardDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideWaveGuardDatabase(
        @ApplicationContext context: Context
    ): WaveGuardDatabase = WaveGuardDatabase.getInstance(context)

    @Provides
    @Singleton
    fun provideCsiDao(database: WaveGuardDatabase): CsiDao = database.csiDao()

    @Provides
    @Singleton
    fun provideAlertDao(database: WaveGuardDatabase): AlertDao = database.alertDao()
}
