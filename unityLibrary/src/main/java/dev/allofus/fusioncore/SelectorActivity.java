package dev.allofus.fusioncore;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

public class SelectorActivity extends Activity {
    private static final String TAG = "FusionCore";
    private static final String[] SUPPORTED_PACKAGES = {
            "com.innersloth.spacemafia",
            "com.abstractsoft.hybridanimals",
            "com.StefMorojna.SpaceflightSimulator",
            "com.DanVogt.DATAWING"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        List<AppEntry> installedTargets = resolveInstalledTargets();
        setContentView(buildContentView(installedTargets));
    }

    private ViewGroup buildContentView(List<AppEntry> installedTargets) {
        int basePadding = dp(16);

        int backgroundColor;
        int primaryTextColor;
        int secondaryTextColor;
        int cardBackgroundColor;
        int cardStrokeColor;
        try (TypedArray colors = obtainStyledAttributes(new int[]{
                android.R.attr.colorBackground,
                android.R.attr.textColorPrimary,
                android.R.attr.textColorSecondary,
                android.R.attr.colorForeground,
                android.R.attr.colorAccent
        })) {
            backgroundColor = colors.getColor(0, Color.BLACK);
            primaryTextColor = colors.getColor(1, Color.WHITE);
            secondaryTextColor = colors.getColor(2, primaryTextColor);
            cardBackgroundColor = blendColor(backgroundColor, colors.getColor(3, Color.WHITE), 0.08f);
            cardStrokeColor = blendColor(backgroundColor, colors.getColor(4, primaryTextColor), 0.2f);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        root.setBackgroundColor(backgroundColor);
        root.setPadding(basePadding, basePadding, basePadding, basePadding);

        TextView title = new TextView(this);
        title.setTextSize(24f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(primaryTextColor);
        title.setText(getString(R.string.selector_title));
        title.setPadding(0, 0, 0, dp(6));
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView subtitle = new TextView(this);
        subtitle.setTextSize(14f);
        subtitle.setTextColor(secondaryTextColor);
        subtitle.setText(getString(R.string.selector_subtitle));
        subtitle.setPadding(0, 0, 0, dp(14));
        root.addView(subtitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        if (installedTargets.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setGravity(Gravity.CENTER_HORIZONTAL);
            empty.setTextColor(secondaryTextColor);
            empty.setText(getString(R.string.selector_empty_not_installed));
            root.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            applyWindowInsets(root, basePadding);
            return root;
        }

        ListView listView = new ListView(this);
        listView.setDividerHeight(0);
        listView.setCacheColorHint(Color.TRANSPARENT);
        listView.setClipToPadding(false);
        listView.setPadding(0, dp(2), 0, dp(6));
        Drawable touchFeedback = resolveSelectableBackground();
        Drawable defaultIcon = getPackageManager().getDefaultActivityIcon();
        ArrayAdapter<AppEntry> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_list_item_1,
                installedTargets
        ) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                RowHolder holder;
                if (convertView == null) {
                    FrameLayout container = new FrameLayout(getContext());
                    container.setLayoutParams(new ListView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                    ));
                    container.setPadding(0, dp(6), 0, dp(6));

                    LinearLayout card = new LinearLayout(getContext());
                    card.setOrientation(LinearLayout.HORIZONTAL);
                    card.setGravity(Gravity.CENTER_VERTICAL);
                    card.setPadding(dp(14), dp(12), dp(14), dp(12));
                    card.setBackground(createCardBackground(cardBackgroundColor, cardStrokeColor));

                    ImageView icon = new ImageView(getContext());
                    LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(40), dp(40));
                    iconParams.rightMargin = dp(12);
                    icon.setLayoutParams(iconParams);

                    LinearLayout textColumn = new LinearLayout(getContext());
                    textColumn.setOrientation(LinearLayout.VERTICAL);
                    textColumn.setLayoutParams(new LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f
                    ));

                    TextView name = new TextView(getContext());
                    name.setTextSize(16f);
                    name.setTypeface(Typeface.DEFAULT_BOLD);
                    name.setTextColor(primaryTextColor);

                    TextView pkg = new TextView(getContext());
                    pkg.setTextSize(12f);
                    pkg.setTextColor(secondaryTextColor);
                    pkg.setPadding(0, dp(2), 0, 0);

                    TextView version = new TextView(getContext());
                    version.setTextSize(12f);
                    version.setTextColor(secondaryTextColor);
                    version.setPadding(0, dp(2), 0, 0);

                    textColumn.addView(name, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                    ));
                    textColumn.addView(pkg, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                    ));
                    textColumn.addView(version, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                    ));

                    card.addView(icon);
                    card.addView(textColumn);
                    container.addView(card, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                    ));

                    if (touchFeedback != null) {
                        container.setForeground(touchFeedback);
                    }

                    holder = new RowHolder(icon, name, pkg, version);
                    container.setTag(holder);
                    convertView = container;
                } else {
                    holder = (RowHolder) convertView.getTag();
                }

                AppEntry entry = getItem(position);
                if (entry != null) {
                    holder.icon.setImageDrawable(entry.icon != null ? entry.icon : defaultIcon);
                    holder.name.setText(entry.label);
                    holder.packageName.setText(entry.packageName);
                    holder.version.setText(formatVersionText(entry.versionName, entry.versionCode));
                }
                return convertView;
            }
        };
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            AppEntry selected = installedTargets.get(position);
            launchBootstrap(selected.packageName);
        });

        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        applyWindowInsets(root, basePadding);
        return root;
    }

    private void applyWindowInsets(View root, int basePadding) {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int insetTop;
            int insetBottom;
            int insetLeft;
            int insetRight;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                insetTop = bars.top;
                insetBottom = bars.bottom;
                insetLeft = bars.left;
                insetRight = bars.right;
            } else {
                insetTop = insets.getSystemWindowInsetTop();
                insetBottom = insets.getSystemWindowInsetBottom();
                insetLeft = insets.getSystemWindowInsetLeft();
                insetRight = insets.getSystemWindowInsetRight();
            }

            v.setPadding(
                    basePadding + insetLeft,
                    basePadding + insetTop,
                    basePadding + insetRight,
                    basePadding + insetBottom
            );
            return insets;
        });
        root.requestApplyInsets();
    }

    private List<AppEntry> resolveInstalledTargets() {
        PackageManager pm = getPackageManager();
        List<AppEntry> result = new ArrayList<>();

        for (String pkg : SUPPORTED_PACKAGES) {
            if (pm.getLaunchIntentForPackage(pkg) == null) {
                continue;
            }

            String label = pkg;
            Drawable icon = pm.getDefaultActivityIcon();
            String versionName = "Unknown";
            long versionCode = 0L;
            try {
                ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                label = pm.getApplicationLabel(info).toString();
                icon = pm.getApplicationIcon(info);

                PackageInfo packageInfo;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageInfo = pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0));
                } else {
                    packageInfo = pm.getPackageInfo(pkg, 0);
                }
                if (packageInfo.versionName != null && !packageInfo.versionName.isEmpty()) {
                    versionName = packageInfo.versionName;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    versionCode = packageInfo.getLongVersionCode();
                } else {
                    versionCode = packageInfo.versionCode;
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to resolve metadata for package: " + pkg, e);
            }

            Log.i(TAG, "Found installed target: " + pkg + " (" + label + ")");
            result.add(new AppEntry(pkg, label, icon, versionName, versionCode));
        }

        return result;
    }

    private String formatVersionText(String versionName, long versionCode) {
        if (versionCode > 0L) {
            return "v" + versionName + " (" + versionCode + ")";
        }
        return "v" + versionName;
    }

    private void launchBootstrap(String packageName) {
        Intent intent = new Intent(this, BootstrapActivity.class);
        intent.putExtra(BootstrapActivity.EXTRA_TARGET_PACKAGE, packageName);
        startActivity(intent);
        finish();
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private Drawable createCardBackground(int fillColor, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(12));
        drawable.setColor(fillColor);
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private Drawable resolveSelectableBackground() {
        TypedValue value = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true)) {
            return getDrawable(value.resourceId);
        }
        return null;
    }

    private int blendColor(int from, int to, float amount) {
        float clamped = Math.max(0f, Math.min(1f, amount));
        int r = Math.round((1f - clamped) * Color.red(from) + clamped * Color.red(to));
        int g = Math.round((1f - clamped) * Color.green(from) + clamped * Color.green(to));
        int b = Math.round((1f - clamped) * Color.blue(from) + clamped * Color.blue(to));
        return Color.rgb(r, g, b);
    }

    private static final class AppEntry {
        private final String packageName;
        private final String label;
        private final Drawable icon;
        private final String versionName;
        private final long versionCode;

        private AppEntry(String packageName, String label, Drawable icon, String versionName, long versionCode) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
            this.versionName = versionName;
            this.versionCode = versionCode;
        }

        @NonNull
        @Override
        public String toString() {
            if (label.equals(packageName)) {
                return packageName;
            }
            return label + " (" + packageName + ")";
        }
    }

    private static final class RowHolder {
        private final ImageView icon;
        private final TextView name;
        private final TextView packageName;
        private final TextView version;

        private RowHolder(ImageView icon, TextView name, TextView packageName, TextView version) {
            this.icon = icon;
            this.name = name;
            this.packageName = packageName;
            this.version = version;
        }
    }
}
