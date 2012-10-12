// Static wrapper library around AsyncHttpClient

import com.loopj.http.AsyncHttpClient;
import com.loopj.http.AsyncHttpResponseHandler;
import com.loopj.http.RequestParams;
import com.loopj.http.android.*;

public class TwitterRestClient {
    private static final String BASE_URL = "http://api.twitter.com/1/";

    private static AsyncHttpClient client = new AsyncHttpClient();

    public static void get(String url, RequestParams params, AsyncHttpResponseHandler responseHandler) {
//        client.get(getAbsoluteUrl(url), params, responseHandler);
        client.new Transaction().get(url, responseHandler);
    }

    public static void post(String url, RequestParams params, AsyncHttpResponseHandler responseHandler) {
//        client.get(getAbsoluteUrl(url), params, responseHandler);
        client.new Transaction().setParams(params).get(url, responseHandler);
    }

    private static String getAbsoluteUrl(String relativeUrl) {
        return BASE_URL + relativeUrl;
    }
}