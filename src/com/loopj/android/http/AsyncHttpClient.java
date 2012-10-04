/*
    Android Asynchronous Http Client
    Copyright (c) 2011 James Smith <james@loopj.com>
    http://loopj.com

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
*/

package com.loopj.android.http;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.zip.GZIPInputStream;

import org.apache.http.Header;
import org.apache.http.HeaderElement;
import org.apache.http.HttpEntity;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.HttpVersion;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CookieStore;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.protocol.ClientContext;
import org.apache.http.conn.params.ConnManagerParams;
import org.apache.http.conn.params.ConnPerRouteBean;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.entity.HttpEntityWrapper;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpProtocolParams;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.SyncBasicHttpContext;


/**
 * The AsyncHttpClient can be used to make asynchronous GET, POST, PUT and 
 * DELETE HTTP requests in your Android applications. Requests can be made
 * with additional parameters by passing a {@link RequestParams} instance,
 * and responses can be handled by passing an anonymously overridden 
 * {@link AsyncHttpResponseHandler} instance.
 * <p>
 * For example:
 * <p>
 * <pre>
 * AsyncHttpClient client = new AsyncHttpClient();
 * client.get("http://www.google.com", new AsyncHttpResponseHandler() {
 *     &#064;Override
 *     public void onSuccess(String response) {
 *         System.out.println(response);
 *     }
 * });
 * </pre>
 */
public class AsyncHttpClient {
    private static final String VERSION = "1.4.1";

    private static final int DEFAULT_MAX_CONNECTIONS = 10;
    private static final int DEFAULT_SOCKET_TIMEOUT = 10 * 1000;
    private static final int DEFAULT_MAX_RETRIES = 5;
    private static final int DEFAULT_SOCKET_BUFFER_SIZE = 8192;
    private static final String HEADER_ACCEPT_ENCODING = "Accept-Encoding";
    private static final String ENCODING_GZIP = "gzip";

    private static int maxConnections = DEFAULT_MAX_CONNECTIONS;
    private static int socketTimeout = DEFAULT_SOCKET_TIMEOUT;

    private final DefaultHttpClient httpClient;
    private final HttpContext httpContext;
    private ThreadPoolExecutor threadPool;
    private final Map<Object, List<WeakReference<Future<?>>>> requestMap;
    private final Map<String, String> clientHeaderMap;


    /**
     * Creates a new AsyncHttpClient.
     */
    public AsyncHttpClient() {
        BasicHttpParams httpParams = new BasicHttpParams();

        ConnManagerParams.setTimeout(httpParams, socketTimeout);
        ConnManagerParams.setMaxConnectionsPerRoute(httpParams, new ConnPerRouteBean(maxConnections));
        ConnManagerParams.setMaxTotalConnections(httpParams, DEFAULT_MAX_CONNECTIONS);

        HttpConnectionParams.setSoTimeout(httpParams, socketTimeout);
        HttpConnectionParams.setConnectionTimeout(httpParams, socketTimeout);
        HttpConnectionParams.setTcpNoDelay(httpParams, true);
        HttpConnectionParams.setSocketBufferSize(httpParams, DEFAULT_SOCKET_BUFFER_SIZE);

        HttpProtocolParams.setVersion(httpParams, HttpVersion.HTTP_1_1);
        HttpProtocolParams.setUserAgent(httpParams, String.format("android-async-http/%s (http://loopj.com/android-async-http)", VERSION));

        SchemeRegistry schemeRegistry = new SchemeRegistry();
        schemeRegistry.register(new Scheme("http", PlainSocketFactory.getSocketFactory(), 80));
        schemeRegistry.register(new Scheme("https", SSLSocketFactory.getSocketFactory(), 443));
        ThreadSafeClientConnManager cm = new ThreadSafeClientConnManager(httpParams, schemeRegistry);

        httpContext = new SyncBasicHttpContext(new BasicHttpContext());
        httpClient = new DefaultHttpClient(cm, httpParams);
        httpClient.addRequestInterceptor(new HttpRequestInterceptor() {
            public void process(HttpRequest request, HttpContext context) {
                if (!request.containsHeader(HEADER_ACCEPT_ENCODING)) {
                    request.addHeader(HEADER_ACCEPT_ENCODING, ENCODING_GZIP);
                }
                for (String header : clientHeaderMap.keySet()) {
                    request.addHeader(header, clientHeaderMap.get(header));
                }
            }
        });

        httpClient.addResponseInterceptor(new HttpResponseInterceptor() {
            public void process(HttpResponse response, HttpContext context) {
                final HttpEntity entity = response.getEntity();
                if (entity == null) {
                    return;
                }
                final Header encoding = entity.getContentEncoding();
                if (encoding != null) {
                    for (HeaderElement element : encoding.getElements()) {
                        if (element.getName().equalsIgnoreCase(ENCODING_GZIP)) {
                            response.setEntity(new InflatingEntity(response.getEntity()));
                            break;
                        }
                    }
                }
            }
        });

        httpClient.setHttpRequestRetryHandler(new RetryHandler(DEFAULT_MAX_RETRIES));

        threadPool = (ThreadPoolExecutor)Executors.newCachedThreadPool();

        requestMap = new WeakHashMap<Object, List<WeakReference<Future<?>>>>();
        clientHeaderMap = new HashMap<String, String>();
    }

    /**
     * Get the underlying HttpClient instance. This is useful for setting
     * additional fine-grained settings for requests by accessing the
     * client's ConnectionManager, HttpParams and SchemeRegistry.
     */
    public HttpClient getHttpClient() {
        return this.httpClient;
    }

    /**
     * Get the underlying HttpContext instance. This is useful for getting 
     * and setting fine-grained settings for requests by accessing the
     * context's attributes such as the CookieStore.
     */
    public HttpContext getHttpContext() {
        return this.httpContext;
    }

    /**
     * Sets an optional CookieStore to use when making requests
     * @param cookieStore The CookieStore implementation to use, usually an instance of {@link PersistentCookieStore}
     */
    public AsyncHttpClient setCookieStore(CookieStore cookieStore) {
        httpContext.setAttribute(ClientContext.COOKIE_STORE, cookieStore);
        return this;
    }

    /**
     * Overrides the threadpool implementation used when queuing/pooling
     * requests. By default, Executors.newCachedThreadPool() is used.
     * @param threadPool an instance of {@link ThreadPoolExecutor} to use for queuing/pooling requests.
     */
    public AsyncHttpClient setThreadPool(ThreadPoolExecutor threadPool) {
        this.threadPool = threadPool;
        return this;
    }

    /**
     * Sets the User-Agent header to be sent with each request. By default,
     * "Android Asynchronous Http Client/VERSION (http://loopj.com/android-async-http/)" is used.
     * @param userAgent the string to use in the User-Agent header.
     */
    public AsyncHttpClient setUserAgent(String userAgent) {
        HttpProtocolParams.setUserAgent(this.httpClient.getParams(), userAgent);
        return this;
    }

    /**
     * Sets the connection time oout. By default, 10 seconds
     * @param timeout the connect/socket timeout in milliseconds
     */
    public AsyncHttpClient setTimeout(int timeout){
        final HttpParams httpParams = this.httpClient.getParams();
        ConnManagerParams.setTimeout(httpParams, timeout);
        HttpConnectionParams.setSoTimeout(httpParams, timeout);
        HttpConnectionParams.setConnectionTimeout(httpParams, timeout);
        return this;
    }

    /**
     * Sets the SSLSocketFactory to user when making requests. By default,
     * a new, default SSLSocketFactory is used.
     * @param sslSocketFactory the socket factory to use for https requests.
     */
    public AsyncHttpClient setSSLSocketFactory(SSLSocketFactory sslSocketFactory) {
        this.httpClient.getConnectionManager().getSchemeRegistry().register(new Scheme("https", sslSocketFactory, 443));
        return this;
    }
    
    /**
     * Sets headers that will be added to all requests this client makes (before sending).
     * @param header the name of the header
     * @param value the contents of the header
     */
    public AsyncHttpClient addHeader(String header, String value) {
        clientHeaderMap.put(header, value);
        return this;
    }

    /**
     * Sets basic authentication for the request. Uses AuthScope.ANY. This is the same as
     * setBasicAuth('username','password',AuthScope.ANY) 
     * @param username
     * @param password
     */
    public AsyncHttpClient setBasicAuth(String user, String pass){
        AuthScope scope = AuthScope.ANY;
        setBasicAuth(user, pass, scope);
        return this;
    }
    
   /**
     * Sets basic authentication for the request. You should pass in your AuthScope for security. It should be like this
     * setBasicAuth("username","password", new AuthScope("host",port,AuthScope.ANY_REALM))
     * @param username
     * @param password
     * @param scope - an AuthScope object
     *
     */
    public AsyncHttpClient setBasicAuth( String user, String pass, AuthScope scope){
        UsernamePasswordCredentials credentials = new UsernamePasswordCredentials(user,pass);
        this.httpClient.getCredentialsProvider().setCredentials(scope, credentials);
        return this;
    }

    /**
     * Cancels any pending (or potentially active) requests associated with the
     * passed cancelKey.
     * <p>
     * <b>Note:</b> This will only affect requests which were created with a non-null
     * cancelKey. This method is intended to be used in the onDestroy
     * method of your android activities to destroy all requests which are no
     * longer required.
     *
     * @param cancelKey the Object (for instance an android Context instance) associated to the request.
     * @param mayInterruptIfRunning specifies if active requests should be cancelled along with pending requests.
     */
    public void cancelRequests(Object cancelKey, boolean mayInterruptIfRunning) {
        List<WeakReference<Future<?>>> requestList = requestMap.get(cancelKey);
        if(requestList != null) {
            for(WeakReference<Future<?>> requestRef : requestList) {
                Future<?> request = requestRef.get();
                if(request != null) {
                    request.cancel(mayInterruptIfRunning);
                }
            }
        }
        requestMap.remove(cancelKey);
    }


    /**
     * Create, setup and execute a HTTP Transaction using method chaining to set up the call.
     * This class should be used in place of the various get(), post(), put() and delete() calls.<br>
     * <br>
     * For Instance, <code>get(url, params, responseHandler)</code><br>
     * can be replaced with <code> new Transaction().setParams(params).get(url,responseHandler)</code><br>
     * As new parameters are added to the request system, it becomes more and more difficult
     * to support the various permutations of possible parameters to get() calls. This
     * class fixes that.
     */
    class Transaction {
    	private RequestParams params = null;
    	public Transaction setParams(RequestParams params){this.params=params;return this;}

    	private Object cancelKey = null;
    	public Transaction setCancelKey(Object cancelKey){this.cancelKey=cancelKey;return this;}
    	
    	private Header[] headers = null;
    	public Transaction setHeaders(Header[] headers){this.headers=headers;return this;}
    	
    	HttpEntity entity = null;
    	public Transaction setEntity(HttpEntity entity){this.entity=entity;return this;}

    	private String contentType = null;
    	public Transaction setContentType(String contentType){this.contentType=contentType;return this;}
    	
    	public Transaction get(String url, AsyncHttpResponseHandler responseHandler){
    		if (entity!=null) throw new IllegalArgumentException("Cannot setEntity for a get");
    		HttpUriRequest request = new HttpGet(getUrlWithQueryString(url, params));
            if(headers != null) request.setHeaders(headers);
            sendRequest(httpClient, httpContext, request, contentType, responseHandler, cancelKey);
    		return this;
    	}
    	public Transaction post(String url, AsyncHttpResponseHandler responseHandler){
    		if (entity!=null && params!=null) throw new IllegalArgumentException("On post can't do both setEntity and setParams");
            HttpEntityEnclosingRequestBase request = new HttpPost(url);
            if(entity != null) request = addEntityToRequestBase(request, entity);
            if(params != null) request.setEntity(paramsToEntity(params));
            if(headers != null) request.setHeaders(headers);
            sendRequest(httpClient, httpContext, request, contentType, responseHandler, cancelKey);
    		return this;
    	}
    	public Transaction put(String url, AsyncHttpResponseHandler responseHandler){
    		if (entity!=null && params!=null) throw new IllegalArgumentException("On put can't do both setEntity and setParams");
    		if (params!=null) entity = paramsToEntity(params);
            HttpEntityEnclosingRequestBase request = addEntityToRequestBase(new HttpPut(url), entity);
            if(headers != null) request.setHeaders(headers);
            sendRequest(httpClient, httpContext, request, contentType, responseHandler, cancelKey);
    		return this;
    	}
    	public Transaction delete(String url, AsyncHttpResponseHandler responseHandler){
    		if (entity!=null) throw new IllegalArgumentException("Cannot setEntity for a delete");
    		if (params!=null) throw new IllegalArgumentException("Cannot setParams for a delete");
            final HttpDelete request = new HttpDelete(url);
            if(headers != null) request.setHeaders(headers);
            sendRequest(httpClient, httpContext, request, null, responseHandler, cancelKey);
    		return this;
    	}
    }

    //
    // HTTP GET Requests
    //
    
    /**
     * @deprecated
     * Perform a HTTP GET request, without any parameters.
     * @param url the URL to send the request to.
     * @param responseHandler the response handler instance that should handle the response.
     */
    public void get(String url, AsyncHttpResponseHandler responseHandler) {
//        get(null, url, null, responseHandler);
    	new Transaction().get(url, responseHandler);
    	
    }

    /**
     * @deprecated
     * Perform a HTTP GET request with parameters.
     * @param url the URL to send the request to.
     * @param params additional GET parameters to send with the request.
     * @param responseHandler the response handler instance that should handle the response.
     */
    public void get(String url, RequestParams params, AsyncHttpResponseHandler responseHandler) {
//        get(null, url, params, responseHandler);
        new Transaction().setParams(params).get(url, responseHandler);
    }

    /**
     * @deprecated
     * Perform a HTTP GET request without any parameters and track the cancelKey which initiated the request.
     * @param cancelKey the Object (for instance, an Android Context) which initiated the request.
     * @param url the URL to send the request to.
     * @param responseHandler the response handler instance that should handle the response.
     */
    public void get(Object cancelKey, String url, AsyncHttpResponseHandler responseHandler) {
//        get(cancelKey, url, null, responseHandler);
        new Transaction().setCancelKey(cancelKey).get(url, responseHandler);
    }

    /**
     * @deprecated
     * Perform a HTTP GET request and track the cancelKey Object which initiated the request.
     * @param cancelKey the Object (for instance, an Android Context) which initiated the request.
     * @param url the URL to send the request to.
     * @param params additional GET parameters to send with the request.
     * @param responseHandler the response handler instance that should handle the response.
     */
    public void get(Object cancelKey, String url, RequestParams params, AsyncHttpResponseHandler responseHandler) {
//        sendRequest(httpClient, httpContext, new HttpGet(getUrlWithQueryString(url, params)), null, responseHandler, cancelKey);
    	new Transaction().setCancelKey(cancelKey).setParams(params).get(url, responseHandler);
    }
    
    /**
     * @deprecated
     * Perform a HTTP GET request and track the cancelKey Object which initiated the request
     * with customized headers
     * @param cancelKey the Object (for instance, an Android Context) which initiated the request.
     * @param url the URL to send the request to.
     * @param headers set headers only for this request
     * @param params additional GET parameters to send with the request.
     * @param responseHandler the response handler instance that should handle
     *        the response.
     */
    public void get(Object cancelKey, String url, Header[] headers, RequestParams params, AsyncHttpResponseHandler responseHandler) {
//        HttpUriRequest request = new HttpGet(getUrlWithQueryString(url, params));
//        if(headers != null) request.setHeaders(headers);
//        sendRequest(httpClient, httpContext, request, null, responseHandler, cancelKey);
        new Transaction().setCancelKey(cancelKey).setHeaders(headers).setParams(params).get(url, responseHandler);
    }


    //
    // HTTP POST Requests
    //

    /**
     * @deprecated
     * Perform a HTTP POST request, without any parameters.
     * @param url the URL to send the request to.
     * @param responseHandler the response handler instance that should handle the response.
     */
    public void post(String url, AsyncHttpResponseHandler responseHandler) {
//        post(null, url, null, responseHandler);
        new Transaction().post(url, responseHandler);
    }

    /**
     * @deprecated
     * Perform a HTTP POST request with parameters.
     * @param url the URL to send the request to.
     * @param params additional POST parameters or files to send with the request.
     * @param responseHandler the response handler instance that should handle the response.
     */
    public void post(String url, RequestParams params, AsyncHttpResponseHandler responseHandler) {
//        post(null, url, params, responseHandler);
        new Transaction().setParams(params).post(url, responseHandler);
    }

    /**
     * @deprecated
     * Perform a HTTP POST request with parameters.
     * And set one-time headers and content-type for the request
     * @param url the URL to send the request to.
     * @param entity a raw {@link HttpEntity} to send with the request, for example, use this to send string/json/xml payloads to a server by passing a {@link org.apache.http.entity.StringEntity}.
     * @param contentType the content type of the payload you are sending, for example application/json if sending a json payload.
     * @param responseHandler the response handler instance that should handle the response.
     */
    public void post(String url, HttpEntity entity, String contentType, AsyncHttpResponseHandler responseHandler) {
//        post(null, url, entity, contentType, responseHandler);
        new Transaction().setEntity(entity).setContentType(contentType).post(url, responseHandler);
    }

    /**
     * @deprecated
     * Perform a HTTP POST request and track the cancelKey which initiated the request.
     * @param cancelKey the Object (for instance, an Android Context) which initiated the request.
     * @param url the URL to send the request to.
     * @param params additional POST parameters or files to send with the request.
     * @param responseHandler the response handler instance that should handle the response.
     */
    public void post(Object cancelKey, String url, RequestParams params, AsyncHttpResponseHandler responseHandler) {
//        post(cancelKey, url, paramsToEntity(params), null, responseHandler);
        new Transaction().setCancelKey(cancelKey).setEntity(paramsToEntity(params)).post(url, responseHandler);
    }

    /**
     * @deprecated
     * Perform a HTTP POST request and track the cancelKey which initiated the request.
     * @param cancelKey the Object (for instance, an Android Context) which initiated the request.
     * @param url the URL to send the request to.
     * @param entity a raw {@link HttpEntity} to send with the request, for example, use this to send string/json/xml payloads to a server by passing a {@link org.apache.http.entity.StringEntity}.
     * @param contentType the content type of the payload you are sending, for example application/json if sending a json payload.
     * @param responseHandler the response handler instance that should handle the response.
     */
    public void post(Object cancelKey, String url, HttpEntity entity, String contentType, AsyncHttpResponseHandler responseHandler) {
//        sendRequest(httpClient, httpContext, addEntityToRequestBase(new HttpPost(url), entity), contentType, responseHandler, cancelKey);
        new Transaction().setCancelKey(cancelKey).setEntity(entity).setContentType(contentType).post(url, responseHandler);
    }

    /**
     * @deprecated
     * Perform a HTTP POST request and track the cancelKey which initiated
     * the request. Set headers only for this request
     * 
     * @param cancelKey the Object (for instance, an Android Context) which initiated the request.
     * @param url the URL to send the request to.
     * @param headers set headers only for this request
     * @param params additional POST parameters to send with the request.
     * @param contentType the content type of the payload you are sending, for
     *        example application/json if sending a json payload.
     * @param responseHandler the response handler instance that should handle
     *        the response.
     */
    public void post(Object cancelKey, String url, Header[] headers, RequestParams params, String contentType, AsyncHttpResponseHandler responseHandler) {
//        HttpEntityEnclosingRequestBase request = new HttpPost(url);
//        if(params != null) request.setEntity(paramsToEntity(params));
//        if(headers != null) request.setHeaders(headers);
//        sendRequest(httpClient, httpContext, request, contentType, responseHandler, cancelKey);
        new Transaction().setCancelKey(cancelKey).setHeaders(headers).setParams(params).setContentType(contentType).post(url, responseHandler);
    }

    /**
     * @deprecated
     * Perform a HTTP POST request and track the cancelKey which initiated
     * the request. Set headers only for this request
     * 
     * @param cancelKey the Object (for instance, an Android Context) which initiated the request.
     * @param url the URL to send the request to.
     * @param headers set headers only for this request
     * @param entity a raw {@link HttpEntity} to send with the request, for
     *        example, use this to send string/json/xml payloads to a server by
     *        passing a {@link org.apache.http.entity.StringEntity}.
     * @param contentType the content type of the payload you are sending, for
     *        example application/json if sending a json payload.
     * @param responseHandler the response handler instance that should handle
     *        the response.
     */
    public void post(Object cancelKey, String url, Header[] headers, HttpEntity entity, String contentType, AsyncHttpResponseHandler responseHandler) {
//        HttpEntityEnclosingRequestBase request = new HttpPost(url);
//        request = addEntityToRequestBase(request, entity);
//        if(headers != null) request.setHeaders(headers);
//        sendRequest(httpClient, httpContext, request, contentType, responseHandler, cancelKey);
        new Transaction().setCancelKey(cancelKey).setHeaders(headers).setEntity(entity).setContentType(contentType).post(url, responseHandler);
    }

    //
    // HTTP PUT Requests
    //

    /**
     * @deprecated
     * Perform a HTTP PUT request, without any parameters.
     * @param url the URL to send the request to.
     * @param responseHandler the response handler instance that should handle the response.
     */
    public void put(String url, AsyncHttpResponseHandler responseHandler) {
//        put(null, url, null, responseHandler);
        new Transaction().put(url, responseHandler);
    }

    /**
     * @deprecated
     * Perform a HTTP PUT request with parameters.
     * @param url the URL to send the request to.
     * @param params additional PUT parameters or files to send with the request.
     * @param responseHandler the response handler instance that should handle the response.
     */
    public void put(String url, RequestParams params, AsyncHttpResponseHandler responseHandler) {
//        put(null, url, params, responseHandler);
        new Transaction().setParams(params).put(url, responseHandler);
    }

    /**
     * @deprecated
     * Perform a HTTP PUT request with parameters.
     * And set one-time headers and content-type for the request
     * @param url the URL to send the request to.
     * @param entity a raw {@link HttpEntity} to send with the request, for example, use this to send string/json/xml payloads to a server by passing a {@link org.apache.http.entity.StringEntity}.
     * @param contentType the content type of the payload you are sending, for example application/json if sending a json payload.
     * @param responseHandler the response handler instance that should handle the response.
     */
    public void put(String url, HttpEntity entity, String contentType, AsyncHttpResponseHandler responseHandler) {
//    	put(null, url, entity, contentType, responseHandler);
    	new Transaction().setEntity(entity).setContentType(contentType).put(url, responseHandler);
    	
    }

    /**
     * @deprecated
     * Perform a HTTP PUT request and track the cancelKey Object which initiated the request
     * @param cancelKey the Object (for instance, an Android Context) which initiated the request.
     * @param url the URL to send the request to.
     * @param params additional PUT parameters or files to send with the request.
     * @param responseHandler the response handler instance that should handle the response.
     */
    public void put(Object cancelKey, String url, RequestParams params, AsyncHttpResponseHandler responseHandler) {
//      put(cancelKey, url, paramsToEntity(params), null, responseHandler);
        new Transaction().setCancelKey(cancelKey).setParams(params).put(url, responseHandler);
    }

    /**
     * @deprecated
     * Perform a HTTP PUT request and track the cancelKey Object which initiated the request
     * And set one-time headers for the request
     * @param cancelKey the Object (for instance, an Android Context) which initiated the request.
     * @param url the URL to send the request to.
     * @param entity a raw {@link HttpEntity} to send with the request, for example, use this to send string/json/xml payloads to a server by passing a {@link org.apache.http.entity.StringEntity}.
     * @param contentType the content type of the payload you are sending, for example application/json if sending a json payload.
     * @param responseHandler the response handler instance that should handle the response.
     */
    public void put(Object cancelKey, String url, HttpEntity entity, String contentType, AsyncHttpResponseHandler responseHandler) {
//        HttpEntityEnclosingRequestBase request = addEntityToRequestBase(new HttpPut(url), entity);
//        sendRequest(httpClient, httpContext, request, contentType, responseHandler, cancelKey);
        new Transaction().setCancelKey(cancelKey).setEntity(entity).setContentType(contentType).put(url, responseHandler);
    }
    
    /**
     * @deprecated
     * Perform a HTTP PUT request and track the cancelKey Object which initiated the request
     * And set one-time headers for the request
     * @param cancelKey the Object (for instance, an Android Context) which initiated the request.
     * @param url the URL to send the request to.
     * @param headers set one-time headers for this request
     * @param entity a raw {@link HttpEntity} to send with the request, for example, use this to send string/json/xml payloads to a server by passing a {@link org.apache.http.entity.StringEntity}.
     * @param contentType the content type of the payload you are sending, for example application/json if sending a json payload.
     * @param responseHandler the response handler instance that should handle the response.
     */
    public void put(Object cancelKey, String url,Header[] headers, HttpEntity entity, String contentType, AsyncHttpResponseHandler responseHandler) {
//        HttpEntityEnclosingRequestBase request = addEntityToRequestBase(new HttpPut(url), entity);
//        if(headers != null) request.setHeaders(headers);
//        sendRequest(httpClient, httpContext, request, contentType, responseHandler, cancelKey);
        new Transaction().setCancelKey(cancelKey).setHeaders(headers).setEntity(entity).setContentType(contentType).put(url, responseHandler);
    }

    //
    // HTTP DELETE Requests
    //

    /**
     * @deprecated
     * Perform a HTTP DELETE request.
     * @param url the URL to send the request to.
     * @param responseHandler the response handler instance that should handle the response.
     */
    public void delete(String url, AsyncHttpResponseHandler responseHandler) {
//        delete(null, url, responseHandler);
        new Transaction().delete(url, responseHandler);
    }

    /**
     * @deprecated
     * Perform a cancelable HTTP DELETE request.
     * @param cancelKey the Object (for instance, an Android Context) which initiated the request.
     * @param url the URL to send the request to.
     * @param responseHandler the response handler instance that should handle the response.
     */
    public void delete(Object cancelKey, String url, AsyncHttpResponseHandler responseHandler) {
//        final HttpDelete delete = new HttpDelete(url);
//        sendRequest(httpClient, httpContext, delete, null, responseHandler, cancelKey);
        new Transaction().setCancelKey(cancelKey).delete(url, responseHandler);
    }
    
    /**
     * @deprecated
     * Perform a cancelable HTTP DELETE request.
     * @param cancelKey the Object (for instance, an Android Context) which initiated the request.
     * @param url the URL to send the request to.
     * @param headers set one-time headers for this request
     * @param responseHandler the response handler instance that should handle the response.
     */
    public void delete(Object cancelKey, String url, Header[] headers, AsyncHttpResponseHandler responseHandler) {
//        final HttpDelete delete = new HttpDelete(url);
//        if(headers != null) delete.setHeaders(headers);
//        sendRequest(httpClient, httpContext, delete, null, responseHandler, cancelKey);
        new Transaction().setCancelKey(cancelKey).setHeaders(headers).delete(url, responseHandler);
    }


    // Private stuff
    private void sendRequest(DefaultHttpClient client, HttpContext httpContext, HttpUriRequest uriRequest, String contentType, AsyncHttpResponseHandler responseHandler, Object cancelKey) {
        if(contentType != null) {
            uriRequest.addHeader("Content-Type", contentType);
        }

        Future<?> request = threadPool.submit(new AsyncHttpRequest(client, httpContext, uriRequest, responseHandler));

        if(cancelKey != null) {
            // Add request to request map
            List<WeakReference<Future<?>>> requestList = requestMap.get(cancelKey);
            if(requestList == null) {
                requestList = new LinkedList<WeakReference<Future<?>>>();
                requestMap.put(cancelKey, requestList);
            }

            requestList.add(new WeakReference<Future<?>>(request));

            // TODO: Remove dead weakrefs from requestLists?
        }
    }

    private String getUrlWithQueryString(String url, RequestParams params) {
        if(params != null) {
            String paramString = params.getParamString();
            url += "?" + paramString;
        }

        return url;
    }

    private HttpEntity paramsToEntity(RequestParams params) {
        HttpEntity entity = null;

        if(params != null) {
            entity = params.getEntity();
        }

        return entity;
    }

    private HttpEntityEnclosingRequestBase addEntityToRequestBase(HttpEntityEnclosingRequestBase requestBase, HttpEntity entity) {
        if(entity != null){
            requestBase.setEntity(entity);
        }

        return requestBase;
    }

    private static class InflatingEntity extends HttpEntityWrapper {
        public InflatingEntity(HttpEntity wrapped) {
            super(wrapped);
        }

        @Override
        public InputStream getContent() throws IOException {
            return new GZIPInputStream(wrappedEntity.getContent());
        }

        @Override
        public long getContentLength() {
            return -1;
        }
    }
}
