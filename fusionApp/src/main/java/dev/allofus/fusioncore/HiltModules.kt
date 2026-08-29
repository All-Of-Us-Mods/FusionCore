package dev.allofus.fusioncore

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.room3.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.allofus.fusioncore.data.DatabaseManager
import dev.allofus.fusioncore.data.GameSettingsDao
import dev.allofus.fusioncore.data.GameSettingsRepo
import dev.allofus.fusioncore.data.GameSettingsRepoImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PackageManagerModule {
    @Provides
    fun providePackageManager(@ApplicationContext appContext: Context): PackageManager {
        return appContext.packageManager
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    @Singleton
    abstract fun bindGameSettingsRepository(
        impl: GameSettingsRepoImpl
    ): GameSettingsRepo
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabaseManager(
        @ApplicationContext context: Context
    ): DatabaseManager = DatabaseManager(context)
}