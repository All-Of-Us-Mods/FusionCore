# Fusion Core

Unity modding framework for Android IL2CPP games.

## Compatibility

Current minimum supported Android version is 7.0 (API level 24).
No guarantees it will work on that version though, 
as it has only been tested on Android 11 (API level 30) and above.

As of writing this, PC versions of BepInEx and MelonLoader still use .NET 6.0 and legacy MonoMod.
However, FusionCore uses .NET 10.0 and MonoMod reorganized, so if you directly use MonoMod in your mods,
you will need to make it compatible with the newer MonoMod version.

There is no Lemon/Melonloader support yet, just BepInEx Fusion (my custom fork of BepInEx).

FusionCore automatically detects Unity version, then downloads the unstripped libunity.so file. You only need to follow the configuration steps below to make things work.

## Can you add support for "X" game?

Make a new issue with the following information:
- Game name and version
- Android package name (e.g. `com.example.game`)
- Unity version (if known, otherwise I can check it myself)
- Any other relevant information (e.g. if the game uses a custom UnityPlayerActivity, or if it has anti-cheat)

We will try to add support, but there are no guarantees. If you want to help, you can also submit a pull request with the necessary changes to support the game.

## Configuration

1. Add the target package to the `SelectorActivity`'s package list.

2. Add/update `<queries><package ... /></queries>` in `unityLibrary/src/main/AndroidManifest.xml` for your target package.
   - Android package visibility rules require this for package context lookup.

3. If the game uses a custom UnityPlayerActivity, add that to the `AndroidManifest.xml`.
   - You can clone the existing activity declaration and change the name to match the game's custom activity.

## Build

Use the Gradle wrapper from the repository root:

```powershell
.\gradlew.bat :launcher:assembleDebug
```

Optional clean build:

```powershell
.\gradlew.bat clean :launcher:assembleDebug
```
