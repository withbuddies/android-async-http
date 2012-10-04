import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.loopj.http.android.AndroidJsonResponseHandler;

class TwitterRestClientUsage {
    public void getPublicTimeline() {
        TwitterRestClient.get("statuses/public_timeline.json", null, new AndroidJsonResponseHandler() {
            @Override
            public void onSuccess(JSONArray timeline) {
                try {
                    JSONObject firstEvent = (JSONObject)timeline.get(0);
                    String tweetText = firstEvent.getString("text");

                    // Do something with the response
                    System.out.println(tweetText);
                } catch(JSONException e) {
                    e.printStackTrace();
                }
            }
        });
    }
}