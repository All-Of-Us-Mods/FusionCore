package dev.allofus.fusioncore;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public class FusionInstrumentation extends Instrumentation {

    private static final String TAG = "FusionCore";

    private static volatile ClassLoader gameClassLoader;

    public static void setGameClassLoader(ClassLoader classLoader) {
        gameClassLoader = classLoader;
    }

    public static void install() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Method currentActivityThread = activityThreadClass.getDeclaredMethod("currentActivityThread");
            currentActivityThread.setAccessible(true);
            Object activityThread = currentActivityThread.invoke(null);

            Field field = activityThreadClass.getDeclaredField("mInstrumentation");
            field.setAccessible(true);

            Instrumentation current = (Instrumentation) field.get(activityThread);
            if (current instanceof FusionInstrumentation) {
                return;
            }

            FusionInstrumentation replacement = new FusionInstrumentation();
            inheritState(current, replacement);
            field.set(activityThread, replacement);
            Log.i(TAG, "Installed FusionInstrumentation");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to install FusionInstrumentation", t);
        }
    }

    @Override
    public Activity newActivity(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        try {
            return super.newActivity(cl, className, intent);
        } catch (ClassNotFoundException notInOurApk) {
            ClassLoader game = gameClassLoader;
            if (game != null) {
                try {
                    Activity activity = super.newActivity(game, className, intent);
                    Log.i(TAG, "Instantiated activity from game classloader: " + className);
                    return activity;
                } catch (ClassNotFoundException ignored) {
                }
            }
            throw notInOurApk;
        }
    }

    // A freshly constructed Instrumentation has no internal wiring (ActivityThread, contexts, ...),
    // so the replacement reuses the original's.
    private static void inheritState(Instrumentation from, Instrumentation to) {
        for (Field field : Instrumentation.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            try {
                field.set(to, field.get(from));
            } catch (IllegalAccessException ignored) {
            }
        }
    }
}
