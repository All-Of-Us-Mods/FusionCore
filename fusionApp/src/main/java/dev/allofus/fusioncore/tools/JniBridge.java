package dev.allofus.fusioncore.tools;

import android.app.Activity;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import dev.allofus.fusioncore.R;

public class JniBridge {
    private static final String TAG = "JniBridge";

    public static void SetLoadingState(Activity activity, boolean enabled) {
        activity.runOnUiThread(() -> {
            View view = activity.findViewById(R.id.loader);
            if (view == null) {
                Log.e(TAG, "Could not set loading state, view is null!");
                return;
            }

            view.setVisibility(enabled ? View.VISIBLE : View.GONE);
            Log.i(TAG, "Set loading state to "+enabled);
        });
    }

    public static void SetLoadingText(Activity activity, String text) {
        activity.runOnUiThread(() -> {
            TextView view = activity.findViewById(R.id.loadingText);
            if (view == null) {
                Log.e(TAG, "Could not set loading text, view is null!");
                return;
            }

            view.setText(text);
            Log.i(TAG, "Set loading text to "+text);
        });
    }
}
