package dev.allofus.fusioncore;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import dev.allofus.fusioncore.hooks.GameRuntime;

public class StubActivity extends android.app.Activity {
    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(null);
        if (handleIntent(getIntent())) return;
        if (GameRuntime.getGameContext() == null) {
            Intent intent = new Intent(this, SelectorActivity.class);
            startActivity(intent);
        }
        finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private boolean handleIntent(Intent incoming) {
        if (incoming == null) return false;
        Uri data = incoming.getData();
        var fgScheme = "eos.xyza7891maded0tpnjfoif8omi0dwy0j";
        if (data == null || !fgScheme.equalsIgnoreCase(data.getScheme())) return false;
        Context gameContext = GameRuntime.getGameContext();
        if (gameContext == null) {
            finish();
            return true;
        }
        Intent callback = new Intent(incoming);
        callback.setComponent(new ComponentName(gameContext.getPackageName(), "com.epicgames.mobile.eossdk.EOSAuthHandlerActivity"));
        callback.setFlags(0);
        GameRuntime.dispatchToGame(callback);
        finish();
        return true;
    }

    @Override
    protected void onPause() {
        // If we're about to be replaced by another activity, don't pause unnecessarily
        super.onPause();
    }
}
