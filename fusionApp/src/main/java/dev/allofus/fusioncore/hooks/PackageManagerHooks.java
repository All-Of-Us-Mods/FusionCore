package dev.allofus.fusioncore.hooks;
import android.content.pm.PackageManager;
import android.util.Log;

public class PackageManagerHooks {
    private static final String TAG = "FusionCore";
    public static void install(android.content.Context fusionContext,
                                         android.content.Context gameContext) throws Exception {
        PackageManager manager = fusionContext.getPackageManager();
        Class<?> managerClass = Class.forName("android.app.ApplicationPackageManager");
        java.lang.reflect.Field pmField = managerClass.getDeclaredField("mPM");
        pmField.setAccessible(true);
        Object original = pmField.get(manager);
        Class<?> pmInterface = Class.forName("android.content.pm.IPackageManager");
        java.util.Set<String> fusionComponents = new java.util.HashSet<>();
        android.content.pm.PackageInfo info = manager.getPackageInfo(fusionContext.getPackageName(),
                PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES |
                        PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS);
        for (android.content.pm.ComponentInfo[] components : new android.content.pm.ComponentInfo[][] {
                info.activities, info.services, info.receivers, info.providers}) {
            if (components != null) for (android.content.pm.ComponentInfo component : components) {
                fusionComponents.add(component.name);
            }
        }
        Object proxy = java.lang.reflect.Proxy.newProxyInstance(PackageManagerHooks.class.getClassLoader(),
                new Class<?>[] {pmInterface}, (object, method, args) -> {
                    if (method.getName().equals("setComponentEnabledSetting") && args != null &&
                            args[0] instanceof android.content.ComponentName component) {
                        boolean externalGame = component.getPackageName().equals(gameContext.getPackageName());
                        boolean missingFusion = component.getPackageName().equals(fusionContext.getPackageName()) &&
                                !fusionComponents.contains(component.getClassName());
                        if (externalGame || missingFusion) {
                            Log.d(TAG, "Skipping component enablement: " + component);
                            return null;
                        }
                    }
                    try {
                        return method.invoke(original, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        pmField.set(manager, proxy);
        pmField.set(gameContext.getPackageManager(), proxy);
        java.lang.reflect.Field cachedPm = Class.forName("android.app.ActivityThread")
                .getDeclaredField("sPackageManager");
        cachedPm.setAccessible(true);
        cachedPm.set(null, proxy);
    }

}
