package com.loopj.http;

import org.apache.http.HttpResponse;

public interface AsyncHttpResponseHandler {
	public void sendStartMessage();
	public void sendFinishMessage();
	public void sendFailureMessage(Throwable e, String responseBody);
	public void sendFailureMessage(Throwable e, byte[] responseBody);
	public void sendResponseMessage(HttpResponse response);
}
