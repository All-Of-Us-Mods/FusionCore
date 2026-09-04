package dev.allofus.fusioncore.hooks;

import android.content.res.Resources;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

public class ResourceHooks {
    private static final String TAG = "ResourceHooks";

    public static void installHooks(Resources gameResources, Resources ourResources) {

        try {
            // getIdentifier needs a separate hook because null values get converted to integer 0
            Method getIdentifierMethod = Resources.class.getMethod("getIdentifier", String.class, String.class, String.class);
            Pine.hook(getIdentifierMethod, new MethodHook() {
                @Override
                public void afterCall(Pine.CallFrame callFrame) {
                    if ((int) callFrame.getResult() != 0) {
                        return;
                    }

                    // try game resources
                    try {
                        Object gameResResult = callFrame.invokeOriginalMethod(gameResources, callFrame.args);
                        if (gameResResult instanceof Integer && (int) gameResResult != 0) {
                            Log.i(TAG, "Found identifier in game resources!");
                            callFrame.setResult(gameResResult);
                            return;
                        }
                    } catch (Throwable ignored) {}

                    // try fusion resources
                    try {
                        Object ourResResult = callFrame.invokeOriginalMethod(ourResources, callFrame.args);
                        if (ourResResult instanceof Integer && (int) ourResResult != 0) {
                            Log.i(TAG, "Found identifier in our resources!");
                            callFrame.setResult(ourResResult);
                            return;
                        }
                    } catch (Throwable ignored) {}

                    Log.e(TAG, "Could not find identifier in game or our resources! Args: " + Arrays.toString(callFrame.args));
                }
            });

            ArrayList<String> blacklist = new ArrayList<>();
            blacklist.add(getIdentifierMethod.toString());
            blacklist.add(Resources.class.getMethod("getConfiguration").toString());
            blacklist.add(Resources.class.getMethod("getDisplayMetrics").toString());

            for (Method method : Resources.class.getDeclaredMethods()) {
                if (blacklist.contains(method.toString()) || method.getReturnType().equals(Void.TYPE)) {
                    continue;
                }

                Pine.hook(method, new MethodHook() {
                    @Override
                    public void afterCall(Pine.CallFrame callFrame) {
                        if (!callFrame.hasThrowable() && callFrame.getResult() != null) {
                            return;
                        }

                        // try game resources
                        try {
                            Object gameResResult = callFrame.invokeOriginalMethod(gameResources, callFrame.args);
                            if (gameResResult != null) {
                                Log.i(TAG, method.getName() + " found in game resources!");
                                callFrame.setThrowable(null);
                                callFrame.setResult(gameResResult);
                                return;
                            }
                        } catch (Throwable ignored) {}

                        // try our resources
                        try {
                            Object ourResResult = callFrame.invokeOriginalMethod(ourResources, callFrame.args);
                            if (ourResResult != null) {
                                Log.i(TAG, method.getName() + " found in our resources!");
                                callFrame.setThrowable(null);
                                callFrame.setResult(ourResResult);
                                return;
                            }
                        } catch (Throwable ignored) {}

                        Log.e(TAG, "Could not resolve " + method.getName() + " across resources! Args: " + Arrays.toString(callFrame.args));
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to hook Resources: " + e.getMessage());
        }
    }
}