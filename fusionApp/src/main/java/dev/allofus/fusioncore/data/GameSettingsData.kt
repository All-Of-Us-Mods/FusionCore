package dev.allofus.fusioncore.data

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "game_preferences")
data class GameSettingsData(
    @PrimaryKey @ColumnInfo(name = "id") val packageId: String,
    @ColumnInfo(name = "use_unstripped_unity") val useUnstrippedUnity: Boolean = true,
    @ColumnInfo(name = "activity_override") val activityOverride: String? = null,
    @ColumnInfo(name = "metadata_override") val metadataOverride: String? = null,
    @ColumnInfo(name = "use_runtime_metadata") val useRuntimeMetadata: Boolean = false,
)
