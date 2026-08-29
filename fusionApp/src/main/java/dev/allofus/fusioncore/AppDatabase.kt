package dev.allofus.fusioncore

import androidx.room3.Database
import androidx.room3.RoomDatabase
import dev.allofus.fusioncore.data.GameSettingsDao
import dev.allofus.fusioncore.data.GameSettingsData

@Database(entities = [GameSettingsData::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun gameSettingsDao(): GameSettingsDao
}