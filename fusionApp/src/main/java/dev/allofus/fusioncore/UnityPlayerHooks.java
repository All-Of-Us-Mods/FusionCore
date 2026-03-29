package dev.allofus.fusioncore;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

public class UnityPlayerHooks {

    public static String TAG = "UnityPlayerHooks";

    public static final String[] UnityPlayerClassNames = new String[] {
            "com.unity3d.player.UnityPlayer",
            "com.unity3d.player.UnityPlayerForGameActivity",
            "com.unity3d.player.UnityPlayerForActivityOrService"
    };

    public static void installHooks(Context gameContext) {
        var classLoader = BootstrapActivity.class.getClassLoader();
        if (classLoader == null) {
            throw new IllegalStateException("ClassLoader is null");
        }

        // get constructor
        ArrayList<Constructor<?>> constructors = new ArrayList<>();
        Class<?> unityPlayerClass = null;
        for (String className : UnityPlayerClassNames) {
            try {
                unityPlayerClass = classLoader.loadClass(className);
                for (Constructor<?> ctor : unityPlayerClass.getDeclaredConstructors()) {
                    if (ctor.getParameterTypes().length >= 1 &&
                            Context.class.isAssignableFrom(ctor.getParameterTypes()[0])) {
                        constructors.add(ctor);
                    }
                }
            } catch (ClassNotFoundException e) {
                // Try next class name
            }
        }

        if (unityPlayerClass == null || constructors.isEmpty()) {
            throw new IllegalStateException("Failed to find UnityPlayer class or constructor");
        }

        Log.i(TAG, "Found UnityPlayer class: " + unityPlayerClass.getName());

        ArrayList<Field> activityFields = new ArrayList<>();
        for (Field field : unityPlayerClass.getFields()) {
            if (Activity.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                activityFields.add(field);
                break;
            }
        }

        for (Constructor<?> constructor : constructors) {
            Log.i(TAG, "Hooking constructor: " + constructor);
            Pine.hook(constructor, new MethodHook() {
                Activity activity = null;
                View loadingOverlay;

                @Override
                public void beforeCall(Pine.CallFrame callFrame) {
                    try {
                        if (callFrame.args[0] == null || !(callFrame.args[0] instanceof Activity)) {
                            Log.w(TAG, "First argument is not a Activity, skipping hook");
                            return;
                        }
                        // In UnityPlayerHooks beforeCall:
                        Log.i("UnityPlayerHooks", "Constructor firing, context class: "
                                + callFrame.args[0].getClass().getName());
                        activity = (Activity) callFrame.args[0];
                        loadingOverlay = showLoadingOverlay(activity, "Injecting Fusion hooks...");
                        callFrame.args[0] = new CustomContextWrapper(gameContext, activity, activity);
                    } catch (Exception e) {
                        Log.i(TAG, "Failed to wrap context!", e);
                    }
                }

                @Override
                public void afterCall(Pine.CallFrame callFrame) {
                    if (activity == null) {
                        return;
                    }

                    hideLoadingOverlay(activity, loadingOverlay);

                    for (Field field : activityFields) {
                        try {
                            Log.i(TAG, "Setting activity field: " + field.getName());
                            field.set(callFrame.thisObject, activity);
                        } catch (IllegalAccessException e) {
                            throw new RuntimeException("Failed to set activity field: " + field.getName(), e);
                        }
                    }
                }
            });
        }
    }

    private static View showLoadingOverlay(Activity activity, String statusText) {
        final View[] created = new View[1];
        Runnable createOverlay = () -> {
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();

            FrameLayout overlay = new FrameLayout(activity);
            overlay.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
            ));
            overlay.setClickable(true);
            overlay.setBackgroundColor(0x88000000);

            LinearLayout container = new LinearLayout(activity);
            container.setOrientation(LinearLayout.VERTICAL);
            container.setGravity(Gravity.CENTER_HORIZONTAL);
            container.setPadding(48, 48, 48, 48);

            ProgressBar progressBar = new ProgressBar(activity);
            progressBar.setIndeterminate(true);

            TextView status = new TextView(activity);
            status.setText(statusText);
            status.setTextColor(Color.WHITE);
            status.setTextSize(16f);
            status.setPadding(0, 24, 0, 0);

            container.addView(progressBar);
            container.addView(status);

            FrameLayout.LayoutParams containerParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
            );
            overlay.addView(container, containerParams);
            decor.addView(overlay);
            created[0] = overlay;
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            createOverlay.run();
            return created[0];
        }

        CountDownLatch latch = new CountDownLatch(1);
        activity.runOnUiThread(() -> {
            createOverlay.run();
            latch.countDown();
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return created[0];
    }

    private static void hideLoadingOverlay(Activity activity, View overlay) {
        if (overlay == null) {
            return;
        }
        activity.runOnUiThread(() -> {
            ViewGroup parent = (ViewGroup) overlay.getParent();
            if (parent != null) {
                parent.removeView(overlay);
            }
        });
    }
}
