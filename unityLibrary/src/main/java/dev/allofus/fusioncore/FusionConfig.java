package dev.allofus.fusioncore;

public class FusionConfig {

    public FusionConfig(String gameLibDir, String appLibDir, boolean useOriginalLibUnity) {
        this.gameLibraryDirectory = gameLibDir;
        this.appLibraryDirectory = appLibDir;
        this.useOriginalLibUnity = useOriginalLibUnity;
    }

    public String appLibraryDirectory;
    public String gameLibraryDirectory;
    public boolean useOriginalLibUnity;
}
