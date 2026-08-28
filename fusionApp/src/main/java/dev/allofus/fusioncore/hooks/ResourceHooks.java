package dev.allofus.fusioncore.hooks;

import android.content.res.Resources;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

public class ResourceHooks {
    private static final String TAG = "ResourceHooks";

    public static void installHooks(Resources gameResources, Resources ourResources) {

        try {
            Method getIdentifierMethod = Resources.class.getMethod("getIdentifier", String.class, String.class, String.class);
            Pine.hook(getIdentifierMethod, new MethodHook() {
                @Override
                public void afterCall(Pine.CallFrame callFrame) {
                    if ((int)callFrame.getResult() != 0) {
                        return;
                    }


                    try {
                        Log.i(TAG, "getIdentifer returned 0!");
                        Object gameResResult = callFrame.invokeOriginalMethod(gameResources, callFrame.args);
                        if (gameResResult != null) {
                            Log.i(TAG, "Found result in game resources!");
                            callFrame.setResult(gameResResult);
                            return;
                        }

                        Object ourResResult = callFrame.invokeOriginalMethod(ourResources, callFrame.args);
                        if (ourResResult != null) {
                            Log.i(TAG, "Found result in our resources!");
                            callFrame.setResult(ourResResult);
                            return;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Exception in in getIdentifier: " + e);
                    }

                    Log.e(TAG, "Could not find identifier in game or our resources!!");
                }
            });

            ArrayList<String> blacklist = new ArrayList<>();
            blacklist.add(getIdentifierMethod.toString());
            blacklist.add(Resources.class.getMethod("getConfiguration").toString());
            blacklist.add(Resources.class.getMethod("getDisplayMetrics").toString());

            for (Method method : Resources.class.getDeclaredMethods()) {
                if (blacklist.contains(method.toString())) {
                    Log.i(TAG, "Skipping method (blacklisted): " + method.getName());
                    continue;
                }

                if (method.getReturnType().equals(Void.TYPE)) {
                    Log.i(TAG, "Skipping method (doesn't return anything): " + method.getName());
                    continue;
                }

                Log.i(TAG, "Hooking " + method.getName());
                Pine.hook(method, new MethodHook() {
                    @Override
                    public void afterCall(Pine.CallFrame callFrame) {
                        if (!callFrame.hasThrowable() && callFrame.getResult() == null) {
                            return;
                        }

                        if (callFrame.hasThrowable()) {
                            Log.i(TAG, method.getName() + " threw " + callFrame.getThrowable().getMessage());
                        } else {
                            Log.i(TAG, method.getName() + " returned null!");
                        }

                        try {
                            Object gameResResult = callFrame.invokeOriginalMethod(gameResources, callFrame.args);
                            if (gameResResult != null) {
                                Log.i(TAG, "Found result in game resources!");
                                callFrame.setResult(gameResResult);
                                return;
                            }

                            Object ourResResult = callFrame.invokeOriginalMethod(ourResources, callFrame.args);
                            if (ourResResult != null) {
                                Log.i(TAG, "Found result in our resources!");
                                callFrame.setResult(ourResResult);
                                return;
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Exception when invoking original methods! " + e);
                        }

                        Log.e(TAG, "Could not find resource in game or our resources!!");
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to hook Resources!!");
        }
    }
}
