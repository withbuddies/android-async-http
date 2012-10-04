import com.loopj.http.AsyncHttpClient;
import com.loopj.http.AsyncHttpResponseHandler;
import com.loopj.http.android.*;

public class ExampleUsage {
    public static void makeRequest() {
        AsyncHttpClient client = new AsyncHttpClient();

        client.get("http://www.google.com", new AsyncHttpResponseHandler() {
            @Override
            public void onSuccess(String response) {
                System.out.println(response);
            }
        });
    }
}