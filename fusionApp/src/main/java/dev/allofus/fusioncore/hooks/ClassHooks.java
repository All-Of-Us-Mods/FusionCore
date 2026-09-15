package dev.allofus.fusioncore.hooks;

import android.util.Log;

import java.lang.reflect.Method;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

public final class ClassHooks {
    public static final String TAG = "ClassHooks";

    public static void installHooks(ClassLoader gameClassLoader) {
        try {
            Method classForNameMethod = Class.class.getDeclaredMethod("forName", String.class, boolean.class, ClassLoader.class);;
            ClassLoader myClassLoader = ClassLoaderHooks.class.getClassLoader();
            assert myClassLoader != null : "My classloader is null, this should never happen";

            ClassLoader[] loaders = {gameClassLoader, myClassLoader};

            Pine.hook(classForNameMethod, new MethodHook() {
                @Override
                public void afterCall(Pine.CallFrame callFrame) throws Throwable {
                    try {
                        // Only attempt fallback if class was not found
                        if (callFrame.getThrowable() instanceof ClassNotFoundException) {
                            String className = (String) callFrame.args[0];
                            boolean initialize = (boolean) callFrame.args[1];
                            ClassLoader loader = (ClassLoader) callFrame.args[2];
                            Log.d(TAG, "after Class.forName: class " + className + "not found in loader " + loader.getClass().getName());

                            Class<?> foundClass = null;
                            for (ClassLoader l : loaders) {
                                try {
                                    foundClass = (Class<?>) callFrame.invokeOriginalMethod(l, className, initialize, myClassLoader);
                                    if (foundClass != null) {
                                        break;
                                    }
                                } catch (Exception e) {
                                    Log.d(TAG, "Class not found in loader: " + className);
                                }
                            }

                            if (foundClass != null) {
                                callFrame.setResult(foundClass);
                                Log.d(TAG, "Successfully loaded " + className);
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Unexpected error in Class.forName afterCall (CRITICAL)", e);
                    }
                }
            });


        } catch (NoSuchMethodException e) {
            Log.e(TAG, "Failed to find forName method in Class", e);
        }
    }
}