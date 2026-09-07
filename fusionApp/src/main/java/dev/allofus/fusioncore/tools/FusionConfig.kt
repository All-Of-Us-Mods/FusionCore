package dev.allofus.fusioncore.tools

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
class FusionConfig(
    /** The package ID of the game. */
    @JvmField var gamePackageId: String,
    /** The ComponentName of the target activity. */
    @JvmField var gameLauncherName: String,
    /** The directory where the game's native libraries are located. */
    @JvmField var gameLibraryDirectory: String,
    /** The directory where Fusion's native libraries are located. */
    @JvmField var appLibraryDirectory: String,
    /** The directory where Fusion's data files are located. */
    @JvmField var appDataDirectory: String,
    /** The directory where BepInEx should be installed. */
    @JvmField var bepInExDirectory: String,
    /** The directory where the .NET runtime should be installed. */
    @JvmField var dotnetDirectory: String,
    /** The directory where the game's Unity data files are located. */
    @JvmField var unityDataDirectory: String,
    /** The Unity version of the game. */
    @JvmField var unityVersion: String,
    /** Whether to use the original libunity.so from the game or the one provided by Fusion. */
    @JvmField var useOriginalLibUnity: Boolean
) : Parcelable