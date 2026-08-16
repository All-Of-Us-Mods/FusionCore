package dev.allofus.fusioncore;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

public class UnityPlayerHooks {

    public static String TAG = "UnityPlayerHooks";

    public static final String[] UnityPlayerClassNames = new String[] {
            "com.unity3d.player.UnityPlayer",
            "com.unity3d.player.UnityPlayerForGameActivity",
            "com.unity3d.player.UnityPlayerForActivityOrService"
    };

    // this is used to inject CustomContextWrapper into the game activity
    public static void installHooks(Context gameContext) {
        var classLoader = gameContext.getClassLoader();
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

        // Collect all Activity-typed fields across the hierarchy (incl. static currentActivity).
        ArrayList<Field> activityFields = new ArrayList<>();
        for (Class<?> cls = unityPlayerClass; cls != null; cls = cls.getSuperclass()) {
            for (Field field : cls.getDeclaredFields()) {
                if (Activity.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    activityFields.add(field);
                }
            }
        }

        for (Constructor<?> constructor : constructors) {
            Log.i(TAG, "Hooking constructor: " + constructor);
            Pine.hook(constructor, new MethodHook() {
                Activity activity = null;

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

                    for (Field field : activityFields) {
                        try {
                            if (!field.getType().isAssignableFrom(activity.getClass())) {
                                Log.i(TAG, "Skipping activity field " + field.getName()
                                        + ": type " + field.getType().getName()
                                        + " not assignable from " + activity.getClass().getName());
                                continue;
                            }

                            boolean isStatic = Modifier.isStatic(field.getModifiers());
                            Log.i(TAG, "Setting activity field: " + field.getName()
                                    + (isStatic ? " (static)" : ""));
                            field.set(isStatic ? null : callFrame.thisObject, activity);
                        } catch (IllegalAccessException e) {
                            throw new RuntimeException("Failed to set activity field: " + field.getName(), e);
                        }
                    }
                }
            });
        }
    }
}
