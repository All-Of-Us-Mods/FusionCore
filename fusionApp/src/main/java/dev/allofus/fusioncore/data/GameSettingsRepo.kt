package dev.allofus.fusioncore.data

import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

interface GameSettingsRepo {
    fun getSettingsFlowForPackage(packageName: String): Flow<GameSettingsData?>
    suspend fun getSettingsForPackage(packageName: String): GameSettingsData?
    suspend fun saveSettings(settings: GameSettingsData)
}

class GameSettingsRepoImpl @Inject constructor(
    private val databaseManager: DatabaseManager
) : GameSettingsRepo {

    private val activeDao: GameSettingsDao
        get() = databaseManager.getDatabase().gameSettingsDao()

    override fun getSettingsFlowForPackage(packageName: String): Flow<GameSettingsData?> {
        return activeDao.getSettingsFlowForApp(packageName)
    }

    override suspend fun getSettingsForPackage(packageName: String): GameSettingsData? {
        return activeDao.getSettingsForApp(packageName)
    }

    override suspend fun saveSettings(settings: GameSettingsData) {
        activeDao.upsertSettings(settings)
    }
}