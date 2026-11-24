package com.on.ebx.connect.test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/** Reusable local, scripted SaaS HTTP endpoint with immutable request captures. */
public final class MockSaasHttpServer implements AutoCloseable
{
	public static final class Response
	{
		final int status; final String body; final Map<String,String> headers;
		public Response(final int pStatus, final String pBody) { this(pStatus,pBody,Collections.<String,String>emptyMap()); }
		public Response(final int pStatus, final String pBody, final Map<String,String> pHeaders) { status=pStatus;body=pBody==null?"":pBody;headers=pHeaders; }
	}
	public static final class Request
	{
		public final String method,path,query,body; public final Map<String,List<String>> headers;
		Request(final HttpExchange x,final String pBody){method=x.getRequestMethod();path=x.getRequestURI().getPath();query=x.getRequestURI().getRawQuery();body=pBody;final Map<String,List<String>> copy=new LinkedHashMap<String,List<String>>();for(final Map.Entry<String,List<String>> e:x.getRequestHeaders().entrySet())copy.put(e.getKey().toLowerCase(java.util.Locale.ENGLISH),new ArrayList<String>(e.getValue()));headers=Collections.unmodifiableMap(copy);}
		public String header(final String pName){final List<String> values=headers.get(pName.toLowerCase(java.util.Locale.ENGLISH));return values==null||values.isEmpty()?null:values.get(0);}
	}
	private final HttpServer server; private final Map<String,Queue<Response>> scripts=new LinkedHashMap<String,Queue<Response>>(); private final List<Request> requests=Collections.synchronizedList(new ArrayList<Request>());
	public MockSaasHttpServer() throws IOException { server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",new Handler());server.start(); }
	public synchronized MockSaasHttpServer enqueue(final String pPath,final Response pResponse){Queue<Response> queue=scripts.get(pPath);if(queue==null){queue=new ArrayDeque<Response>();scripts.put(pPath,queue);}queue.add(pResponse);return this;}
	public String baseUrl(){return "http://127.0.0.1:"+server.getAddress().getPort();}
	public List<Request> requests(){synchronized(requests){return new ArrayList<Request>(requests);}}
	public int calls(final String pPath){int count=0;for(final Request r:requests())if(pPath.equals(r.path))count++;return count;}
	public void close(){server.stop(0);}
	private synchronized Response next(final String pPath){final Queue<Response> queue=scripts.get(pPath);return queue==null||queue.isEmpty()?new Response(404,"{\"error\":\"unscripted\"}"):queue.remove();}
	private final class Handler implements HttpHandler { public void handle(final HttpExchange x)throws IOException{final String body=read(x);requests.add(new Request(x,body));final Response response=next(x.getRequestURI().getPath());for(final Map.Entry<String,String> h:response.headers.entrySet())x.getResponseHeaders().add(h.getKey(),h.getValue());if(!response.headers.containsKey("Content-Type"))x.getResponseHeaders().add("Content-Type","application/json");final byte[] bytes=response.body.getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(response.status,bytes.length);x.getResponseBody().write(bytes);x.close();}}
	private static String read(final HttpExchange x)throws IOException{final ByteArrayOutputStream out=new ByteArrayOutputStream();final byte[] buffer=new byte[1024];for(int n;(n=x.getRequestBody().read(buffer))>=0;)out.write(buffer,0,n);return new String(out.toByteArray(),StandardCharsets.UTF_8);}
}
