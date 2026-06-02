package dev.allofus.fusioncore;

/**
 * Holds all resolved paths and parameters needed to bootstrap a non-Unity
 * native Android target (e.g. com.oculus.os.cm / HeadsetApplication).
 *
 * Analogous to {@link FusionConfig} but stripped of Unity-specific fields
 * (libunity, MelonLoader, dotnet) - non-Unity targets receive a plain .so injection
 * with no managed runtime on top.
 */
public final class NonUnityConfig {

    /** Package name of the target app (e.g. "com.oculus.os.cm"). */
    public final String targetPackage;

    /** Fully-qualified class name of the Activity to hook and launch. */
    public final String targetActivityClass;

    /** Absolute path of the .so to inject before the target's onCreate runs. */
    public final String libraryPath;

    /** Absolute path of the target's native library directory (for findLibrary redirect). */
    public final String gameLibraryDirectory;

    /** Absolute path of FusionCore's own native library directory. */
    public final String appLibraryDirectory;

    /**
     * Optional extra .so files to load after the primary injection library but
     * still before the target's own onCreate. May be null or empty.
     */
    public final String[] extraLibraryPaths;

    public NonUnityConfig(
            String targetPackage,
            String targetActivityClass,
            String libraryPath,
            String gameLibraryDirectory,
            String appLibraryDirectory,
            String[] extraLibraryPaths) {
        this.targetPackage       = targetPackage;
        this.targetActivityClass = targetActivityClass;
        this.libraryPath         = libraryPath;
        this.gameLibraryDirectory = gameLibraryDirectory;
        this.appLibraryDirectory  = appLibraryDirectory;
        this.extraLibraryPaths   = extraLibraryPaths != null ? extraLibraryPaths : new String[0];
    }

    /** Convenience constructor for single-library injection with no extras. */
    public NonUnityConfig(
            String targetPackage,
            String targetActivityClass,
            String libraryPath,
            String gameLibraryDirectory,
            String appLibraryDirectory) {
        this(targetPackage, targetActivityClass, libraryPath,
                gameLibraryDirectory, appLibraryDirectory, null);
    }

    @Override
    public String toString() {
        return "NonUnityConfig{"
                + "pkg=" + targetPackage
                + ", activity=" + targetActivityClass
                + ", lib=" + libraryPath
                + ", extras=" + extraLibraryPaths.length
                + '}';
    }
}