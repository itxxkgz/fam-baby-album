package cn.xiaojia.album;

import android.app.Activity;
import android.view.View;

import org.json.JSONObject;

import java.util.List;

/** Activity-owned requests and navigation for the family feed. */
interface SocialFeedHost {
    Activity activity();

    ImageLoader images();

    JSONObject identity();

    void requestDetailed(
            String method,
            String path,
            JSONObject body,
            SettingsScreen.Done success,
            MemoryFeatureHost.Failure failure);

    Runnable requestCancelableGet(
            String path, SettingsScreen.Done success, MemoryFeatureHost.Failure failure);

    void showFeed(View page);

    void openFeedMedia(JSONObject item, List<JSONObject> scope, Runnable back);
}
