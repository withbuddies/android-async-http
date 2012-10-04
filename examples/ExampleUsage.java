import com.loopj.http.AsyncHttpClient;
import com.loopj.http.android.AndroidResponseHandler;

public class ExampleUsage {
    public static void makeRequest() {
        AsyncHttpClient client = new AsyncHttpClient();

//      client.get("http://www.google.com", new AndroidResponseHandler() {
        client.new Transaction().get("http://www.google.com", new AndroidResponseHandler() {
            @Override
            public void onSuccess(String response) {
                System.out.println(response);
            }
        });

    }
}