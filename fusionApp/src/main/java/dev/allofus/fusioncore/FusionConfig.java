package dev.allofus.fusioncore;

public class FusionConfig {

    public FusionConfig(
            String gameLibDir,
            String appLibDir,
            String appDataDir,
            String unityDataDir,
            String unityVersion,
            boolean useOriginalLibUnity
    ) {
        this.gameLibraryDirectory = gameLibDir;
        this.appLibraryDirectory = appLibDir;
        this.appDataDirectory = appDataDir;
        this.unityDataDirectory = unityDataDir;
        this.unityVersion = unityVersion;
        this.useOriginalLibUnity = useOriginalLibUnity;
    }

    /// The directory where Fusion's native libraries are located.
    public String appLibraryDirectory;

    /// The directory where the game's native libraries are located.
    public String gameLibraryDirectory;

    /// The directory where Fusion's data files are located.
    public String appDataDirectory;

    /// The directory where the game's Unity data files are located.
    public String unityDataDirectory;

    /// The Unity version of the game.
    public String unityVersion;

    /// Whether to use the original libunity.so from the game or the one provided by Fusion.
    public boolean useOriginalLibUnity;
}
