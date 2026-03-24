package com.unity3d.player;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Window;

import dev.allofus.fusioncore.ActivityBridge;
import dev.allofus.fusioncore.CustomContextWrapper;
import dev.allofus.fusioncore.FusionConfig;
import dev.allofus.fusioncore.NativeLibraryManager;

public class UnityPlayerActivity extends Activity implements IUnityPlayerLifecycleEvents
{
    static {
        System.loadLibrary("fusion");
    }

    //public final String TARGET_GAME = "com.innersloth.spacemafia";
    public final String TARGET_GAME = "com.abstractsoft.hybridanimals";

    protected UnityPlayer mUnityPlayer; // don't change the name of this variable; referenced from native code
    public Context m_context;

    private static final String[] FusionLibraries = new String[]{
            // "unity",
            "main",
    };

    protected String updateUnityCommandLineArguments(String cmdLine)
    {
        return cmdLine;
    }

    // Setup activity layout
    @Override protected void onCreate(Bundle savedInstanceState)
    {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);

        String cmdLine = updateUnityCommandLineArguments(getIntent().getStringExtra("unity"));
        getIntent().putExtra("unity", cmdLine);

        // ---------- FUSION CORE -------------

        try {

            Context myContext = this;
            Context gameContext = createPackageContext(TARGET_GAME, CONTEXT_IGNORE_SECURITY);
            m_context = gameContext;

            FusionConfig config = new FusionConfig(
                    gameContext.getApplicationInfo().nativeLibraryDir,
                    myContext.getApplicationInfo().nativeLibraryDir
            );

            ActivityBridge.loadFusion(config);

            // Setup native library hooks
            // NativeLibraryManager.setupLibraryHooks(config);

            // Create custom context to redirect stuff
            CustomContextWrapper wrappedContext = new CustomContextWrapper(gameContext, myContext, this);

            mUnityPlayer = new UnityPlayer(wrappedContext, this);
            UnityPlayer.currentActivity = this;
            //UnityPlayer.currentContext = wrappedContext;
            setContentView(mUnityPlayer);
            mUnityPlayer.requestFocus();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // When Unity player unloaded move task to background
    @Override public void onUnityPlayerUnloaded() {
        moveTaskToBack(true);
    }

    // Callback before Unity player process is killed
    @Override public void onUnityPlayerQuitted() {
    }

    @Override protected void onNewIntent(Intent intent)
    {
        // To support deep linking, we need to make sure that the client can get access to
        // the last sent intent. The clients access this through a JNI api that allows them
        // to get the intent set on launch. To update that after launch we have to manually
        // replace the intent with the one caught here.
        setIntent(intent);
        mUnityPlayer.newIntent(intent);
    }

    // Quit Unity
    @Override protected void onDestroy ()
    {
        mUnityPlayer.destroy();
        super.onDestroy();
    }

    // If the activity is in multi window mode or resizing the activity is allowed we will use
    // onStart/onStop (the visibility callbacks) to determine when to pause/resume.
    // Otherwise it will be done in onPause/onResume as Unity has done historically to preserve
    // existing behavior.
    @Override protected void onStop()
    {
        super.onStop();
        mUnityPlayer.onStop();
    }

    @Override protected void onStart()
    {
        super.onStart();
        mUnityPlayer.onStart();
    }

    // Pause Unity
    @Override protected void onPause()
    {
        super.onPause();
        mUnityPlayer.onPause();
    }

    // Resume Unity
    @Override protected void onResume()
    {
        super.onResume();
        mUnityPlayer.onResume();
    }

    // Low Memory Unity
    @Override public void onLowMemory()
    {
        super.onLowMemory();
        mUnityPlayer.lowMemory();
    }

    // Trim Memory Unity
    @Override public void onTrimMemory(int level)
    {
        super.onTrimMemory(level);
        if (level == TRIM_MEMORY_RUNNING_CRITICAL)
        {
            mUnityPlayer.lowMemory();
        }
    }

    // This ensures the layout will be correct.
    @Override public void onConfigurationChanged(Configuration newConfig)
    {
        super.onConfigurationChanged(newConfig);
        mUnityPlayer.configurationChanged(newConfig);
    }

    // Notify Unity of the focus change.
    @Override public void onWindowFocusChanged(boolean hasFocus)
    {
        super.onWindowFocusChanged(hasFocus);
        mUnityPlayer.windowFocusChanged(hasFocus);
    }

    // For some reason the multiple keyevent type is not supported by the ndk.
    // Force event injection by overriding dispatchKeyEvent().
    @Override public boolean dispatchKeyEvent(KeyEvent event)
    {
        if (event.getAction() == KeyEvent.ACTION_MULTIPLE)
            return mUnityPlayer.injectEvent(event);
        return super.dispatchKeyEvent(event);
    }

    // Pass any events not handled by (unfocused) views straight to UnityPlayer
    @Override public boolean onKeyUp(int keyCode, KeyEvent event)     { return mUnityPlayer.onKeyUp(keyCode, event); }
    @Override public boolean onKeyDown(int keyCode, KeyEvent event)   { return mUnityPlayer.onKeyDown(keyCode, event); }
    @Override public boolean onTouchEvent(MotionEvent event)          { return mUnityPlayer.onTouchEvent(event); }
    @Override public boolean onGenericMotionEvent(MotionEvent event)  { return mUnityPlayer.onGenericMotionEvent(event); }
}
