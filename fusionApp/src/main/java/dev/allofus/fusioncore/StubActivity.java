package dev.allofus.fusioncore;

import android.content.Intent;
import android.os.Bundle;

public class StubActivity extends android.app.Activity {

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(null);
        Intent intent = new Intent(this, SelectorActivity.class);
        startActivity(intent);
    }

    @Override
    protected void onPause() {
        // If we're about to be replaced by another activity, don't pause unnecessarily
        super.onPause();
    }
}
