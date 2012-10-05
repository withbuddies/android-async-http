package com.loopj.http;

import org.apache.http.HttpResponse;

public interface AsyncHttpResponseHandler {
	public void onStart();
	public void onFinish();
	public void onSuccess(String content);
	public void onSuccess(int statusCode, String content);
	public void onFailure(Throwable error);
	public void onFailure(Throwable error, String content);
	public void sendStartMessage();
	public void sendFinishMessage();
	public void sendFailureMessage(Throwable e, String responseBody);
	public void sendFailureMessage(Throwable e, byte[] responseBody);
	public void sendResponseMessage(HttpResponse response);
}
