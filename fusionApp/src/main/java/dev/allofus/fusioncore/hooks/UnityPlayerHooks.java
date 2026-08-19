package dev.allofus.fusioncore.hooks;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;

import dev.allofus.fusioncore.tools.CustomContextWrapper;
import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

public class UnityPlayerHooks {

    public static String TAG = "UnityPlayerHooks";

    public static final String[] UnityPlayerClassNames = new String[] {
            "com.unity3d.player.UnityPlayer",
            "com.unity3d.player.UnityPlayerForGameActivity",
            "com.unity3d.player.UnityPlayerForActivityOrService"
    };

    private static final ThreadLocal<Activity> pendingActivity = new ThreadLocal<>();

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

        ArrayList<Field> activityFields = new ArrayList<>();
        var clazz = unityPlayerClass;
        while (clazz.getSuperclass() != null) {
            Log.i(TAG, "Checking class for activity fields: " + clazz.getName());
            for (Field field : clazz.getDeclaredFields()) {
                if (Activity.class.isAssignableFrom(field.getType())) {
                    Log.i(TAG, "Found activity field " + field.getName() + " in class " + clazz.getName());
                    field.setAccessible(true);
                    activityFields.add(field);
                }
            }
            clazz = clazz.getSuperclass();
        }

        for (Constructor<?> constructor : constructors) {
            Log.i(TAG, "Hooking constructor: " + constructor);
            Pine.hook(constructor, new MethodHook() {

                @Override
                public void beforeCall(Pine.CallFrame callFrame) {
                    try {
                        if (callFrame.args[0] == null || !(callFrame.args[0] instanceof Activity activity)) {
                            Log.w(TAG, "First argument is not a Activity, skipping before hook");
                            return;
                        }

                        pendingActivity.set(activity);

                        // In UnityPlayerHooks beforeCall:
                        Log.i(TAG, "Constructor firing, context class: "
                                + callFrame.args[0].getClass().getName());
                        callFrame.args[0] = new CustomContextWrapper(gameContext, activity, activity);

                        Log.i(TAG, "Setting activity fields in before hook!");
                        for (Field field : activityFields) {
                            try {
                                boolean isStatic = Modifier.isStatic(field.getModifiers());
                                Log.i(TAG, "Setting activity field: " + field.getName() + (isStatic ? " (static)" : ""));
                                field.set(isStatic ? null : callFrame.thisObject, activity);
                            } catch (IllegalAccessException e) {
                                Log.e(TAG, "Failed to set activity field: " + field.getName(), e);
                            }
                        }
                    } catch (Exception e) {
                        Log.i(TAG, "Failed to wrap context!", e);
                    }
                }

                @Override
                public void afterCall(Pine.CallFrame callFrame) {
                    Activity activity = pendingActivity.get();
                    pendingActivity.remove();

                    if (activity == null) {
                        // Fallback to searching args if ThreadLocal is somehow empty
                        for (Object arg : callFrame.args) {
                            if (arg instanceof CustomContextWrapper wrapper) {
                                Context ctx = wrapper.getOriginalActivity();
                                if (ctx instanceof Activity) {
                                    activity = (Activity) ctx;
                                    break;
                                }
                            }
                            if (arg != null && Activity.class.isAssignableFrom(arg.getClass())) {
                                activity = (Activity) arg;
                            }
                        }
                    }

                    if (activity == null) {
                        Log.e(TAG, "Cannot set activity fields: activity is null!");
                        return;
                    }

                    Log.i(TAG, "Setting activity fields in after hook!");
                    for (Field field : activityFields) {
                        try {
                            boolean isStatic = Modifier.isStatic(field.getModifiers());
                            Log.i(TAG, "Setting activity field: " + field.getName() + (isStatic ? " (static)" : ""));
                            field.set(isStatic ? null : callFrame.thisObject, activity);
                        } catch (IllegalAccessException e) {
                            Log.e(TAG, "Failed to set activity field: " + field.getName(), e);
                        }
                    }
                }
            });
        }
    }
}
