package cn.xiaojia.album;

import android.app.Activity;
import android.graphics.Bitmap;
import android.view.View;

import org.json.JSONObject;

import java.util.List;

/** Shared access for native growth and storybook screens. */
interface MemoryFeatureHost {
    interface Failure {
        void accept(int status, String message);
    }

    Activity activity();

    ImageLoader images();

    JSONObject profile();

    boolean canEdit();

    void request(
            String method,
            String path,
            JSONObject body,
            SettingsScreen.Done success,
            SettingsScreen.Failure failure);

    void requestDetailed(
            String method,
            String path,
            JSONObject body,
            SettingsScreen.Done success,
            Failure failure);

    void showFeature(String page, View view, Runnable back);

    void returnToTime();

    void openFeatureMedia(JSONObject item, List<JSONObject> scope, Runnable back);

    /** Takes ownership of the newly rendered bitmap, and recycles it after writing. */
    void saveComparison(Bitmap bitmap, boolean share);
}
