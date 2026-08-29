package dev.allofus.fusioncore.data

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface GameSettingsDao {
    @Query("SELECT * FROM game_preferences WHERE id = :packageId LIMIT 1")
    fun getSettingsFlowForApp(packageId: String): Flow<GameSettingsData?>

    @Query("SELECT * FROM game_preferences WHERE id = :packageId LIMIT 1")
    suspend fun getSettingsForApp(packageId: String): GameSettingsData?

    @Upsert
    suspend fun upsertSettings(settings: GameSettingsData)

    @Query("DELETE FROM game_preferences WHERE id = :packageId")
    suspend fun delete(packageId: String)
}