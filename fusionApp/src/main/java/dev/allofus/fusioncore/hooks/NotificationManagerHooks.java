package dev.allofus.fusioncore.hooks;

import android.app.NotificationManager;
import android.content.Context;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public final class NotificationManagerHooks {
    private NotificationManagerHooks() {}

    public static void install(Context fusionContext, Context gameContext)
            throws ReflectiveOperationException {
        Method getService = NotificationManager.class.getDeclaredMethod("getService");
        getService.setAccessible(true);
        Object original = getService.invoke(null);
        String hostPackage = fusionContext.getPackageName();
        String gamePackage = gameContext.getPackageName();
        Class<?> service = Class.forName("android.app.INotificationManager");
        Object proxy = Proxy.newProxyInstance(NotificationManagerHooks.class.getClassLoader(),
                new Class<?>[] {service}, (object, method, args) -> {
                    if (args != null) {
                        int secondPackage = switch (method.getName()) {
                            case "getNotificationChannel", "getConversationNotificationChannel" -> 2;
                            case "getNotificationChannels", "enqueueNotificationWithTag",
                                    "cancelNotificationWithTag" -> 1;
                            default -> -1;
                        };
                        for (int i : new int[] {0, secondPackage}) {
                            if (i >= 0 && i < args.length && gamePackage.equals(args[i])) {
                                args = args.clone();
                                args[i] = hostPackage;
                                Log.d("NotificationManagerHooks", method.getName()
                                        + " owner: " + gamePackage + " -> " + hostPackage);
                            }
                        }
                    }
                    try {
                        return method.invoke(original, args);
                    } catch (InvocationTargetException error) {
                        Log.e("NotificationManagerHooks", "Notification service " + method.getName()
                                + " failed", error.getCause());
                        throw error.getCause();
                    }
                });
        Field field = NotificationManager.class.getDeclaredField("sService");
        field.setAccessible(true);
        field.set(null, proxy);
    }
}