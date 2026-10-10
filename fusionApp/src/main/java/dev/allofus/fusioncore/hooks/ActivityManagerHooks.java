package dev.allofus.fusioncore.hooks;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public final class ActivityManagerHooks {
    private ActivityManagerHooks() {}

    public static void install(Context fusionContext, Context gameContext)
            throws ReflectiveOperationException {
        Class<?> manager = Class.forName(Build.VERSION.SDK_INT >= 26
                ? "android.app.ActivityManager" : "android.app.ActivityManagerNative");
        Field singletonField = manager.getDeclaredField(Build.VERSION.SDK_INT >= 26
                ? "IActivityManagerSingleton" : "gDefault");
        singletonField.setAccessible(true);
        Object singleton = singletonField.get(null);
        Class<?> singletonClass = Class.forName("android.util.Singleton");
        Method get = singletonClass.getDeclaredMethod("get");
        get.setAccessible(true);
        Object original = get.invoke(singleton);
        Class<?> service = Class.forName("android.app.IActivityManager");
        String hostPackage = fusionContext.getPackageName();
        String gamePackage = gameContext.getPackageName();
        Object proxy = Proxy.newProxyInstance(ActivityManagerHooks.class.getClassLoader(),
                new Class<?>[] {service}, (object, method, args) -> {
                    String name = method.getName();
                    if ((name.equals("getIntentSender") || name.equals("getIntentSenderWithFeature"))
                            && args != null && args.length > 1 && gamePackage.equals(args[1])) {
                        args = args.clone();
                        args[1] = hostPackage;
                        Log.d("ActivityManagerHooks", "PendingIntent owner: " + gamePackage
                                + " -> " + hostPackage);
                    }
                    try {
                        return method.invoke(original, args);
                    } catch (InvocationTargetException error) {
                        throw error.getCause();
                    }
                });
        Field instance = singletonClass.getDeclaredField("mInstance");
        instance.setAccessible(true);
        instance.set(singleton, proxy);
    }
}