package com.rex.wsproxy;

import com.google.gson.Gson;
import com.rex.wsproxy.utils.AllowAllHostnameVerifier;
import com.rex.wsproxy.utils.EchoServer;
import com.rex.wsproxy.utils.X509TrustAllManager;
import com.rex.wsproxy.websocket.control.ControlMessage;
import okhttp3.*;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.ByteString;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

// TODO: Test connect timeout
// TODO: Test connection lost
// TODO: Test re-try
// TODO: Test verify sub-protocol
// TODO: Test auth
public class WsProxyServerTest {

    @Test
    public void testConfigPort() throws Exception {
        final int port = 1234;
        WsProxyServer server = new WsProxyServer()
                .config(new WsProxyServer.Configuration("127.0.0.1", port))
                .start();

        URLConnection conn = new URL("http://127.0.0.1:" + port + "/")
                .openConnection();
        HttpURLConnection connHttp = (HttpURLConnection) conn;
        assertEquals(404, connHttp.getResponseCode());
        assertEquals("Not Found", connHttp.getResponseMessage());

        server.stop();
    }

    @Test
    public void testConfigFile() throws Exception {
        // getClass().getResourceAsStream() point to build/classes/java/test/
        // ClassLoader.getSystemResourceAsStream() point to build/resources/test/
        WsProxyServer server = new WsProxyServer()
                .config(ClassLoader.getSystemResourceAsStream("config_127.0.0.1_4321.properties"))
                .start();

        URLConnection conn = new URL("http://127.0.0.1:4321/")
                .openConnection();
        HttpURLConnection connHttp = (HttpURLConnection) conn;
        assertEquals(404, connHttp.getResponseCode());

        server.stop();
    }

    @Test
    public void testSSL() throws Exception {
        WsProxyServer server = new WsProxyServer()
                .config(new WsProxyServer.Configuration("127.0.0.1", 1234,
                        ClassLoader.getSystemResource("test.cert.pem").getFile(),
                        ClassLoader.getSystemResource("test.key.p8.pem").getFile()))
                .start();

        URLConnection conn = new URL("https://127.0.0.1:" + server.port() + "/")
                .openConnection();


        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new X509TrustManager[] { new X509TrustAllManager() }, null);

        HttpsURLConnection connHttps = (HttpsURLConnection) conn;
        connHttps.setSSLSocketFactory(ctx.getSocketFactory());
        connHttps.setHostnameVerifier(new AllowAllHostnameVerifier());

        assertEquals(404, connHttps.getResponseCode());

        server.stop();
    }

    @Test
    public void testSSLWithEncryptedKey() throws Exception {
        WsProxyServer server = new WsProxyServer()
                .config(new WsProxyServer.Configuration("127.0.0.1", 1234,
                        ClassLoader.getSystemResource("test.cert.pem").getFile(),
                        ClassLoader.getSystemResource("test.key.p8.encrypted.pem").getFile(),
                        "TestOnly"))
                .start();

        server.stop();
    }

    @Test
    public void testWebsocket() throws Exception {
        Gson gson = new Gson();
        WsProxyServer server = new WsProxyServer().start();

        WebSocketListener listener = mock(WebSocketListener.class);
        OkHttpClient client = new OkHttpClient.Builder()
                .build();
        Request request = new Request.Builder()
                .url("ws://127.0.0.1:" + server.port() + "/ws")
                .build();
        WebSocket ws = client.newWebSocket(request, listener);

        ArgumentCaptor<Response> response = ArgumentCaptor.forClass(Response.class);
        verify(listener, timeout(1000)).onOpen(eq(ws), response.capture());


        ControlMessage msg = new ControlMessage();
        msg.type = "request";
        msg.action = "echo";
        ws.send(gson.toJson(msg));

        ArgumentCaptor<String> respTextMsg = ArgumentCaptor.forClass(String.class);
        verify(listener, timeout(1000)).onMessage(eq(ws), respTextMsg.capture());
        msg = gson.fromJson(respTextMsg.getValue(), ControlMessage.class);
        assertEquals("response", msg.type);
        assertEquals("echo", msg.action);

        server.stop();
    }

    @Test
    public void testSecuredWebsocket() throws Exception {
        Gson gson = new Gson();
        WsProxyServer server = new WsProxyServer()
                .config(new WsProxyServer.Configuration("127.0.0.1", 1234,
                        ClassLoader.getSystemResource("test.cert.pem").getFile(),
                        ClassLoader.getSystemResource("test.key.p8.pem").getFile()))
                .start();

        X509TrustManager trustManager = new X509TrustAllManager();
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[] { trustManager }, null);

        WebSocketListener listener = mock(WebSocketListener.class);
        OkHttpClient client = new OkHttpClient.Builder()
                .sslSocketFactory(sslContext.getSocketFactory(), trustManager)
                .hostnameVerifier(new AllowAllHostnameVerifier())
                .build();
        Request request = new Request.Builder()
                .url("wss://127.0.0.1:" + server.port() + "/ws")
                .build();
        WebSocket ws = client.newWebSocket(request, listener);

        ArgumentCaptor<Response> response = ArgumentCaptor.forClass(Response.class);
        verify(listener, timeout(1000)).onOpen(eq(ws), response.capture());


        ControlMessage msg = new ControlMessage();
        msg.type = "request";
        msg.action = "echo";
        ws.send(gson.toJson(msg));

        ArgumentCaptor<String> respTextMsg = ArgumentCaptor.forClass(String.class);
        verify(listener, timeout(1000)).onMessage(eq(ws), respTextMsg.capture());
        msg = gson.fromJson(respTextMsg.getValue(), ControlMessage.class);
        assertEquals("response", msg.type);
        assertEquals("echo", msg.action);

        server.stop();
    }

    @Test
    public void testProxy() throws Exception {
        Gson gson = new Gson();
        MockWebServer httpServer = new MockWebServer();
        httpServer.enqueue(new MockResponse().setResponseCode(200).setBody("HelloWorld!"));
        httpServer.start();

        WsProxyServer server = new WsProxyServer()
                .start();

        WebSocketListener listener = mock(WebSocketListener.class);
        OkHttpClient client = new OkHttpClient.Builder()
                .build();
        Request request = new Request.Builder()
                .url("ws://127.0.0.1:" + server.port() + "/ws")
                .build();
        WebSocket ws = client.newWebSocket(request, listener);

        ArgumentCaptor<Response> response = ArgumentCaptor.forClass(Response.class);
        verify(listener, timeout(1000)).onOpen(eq(ws), response.capture());

        ControlMessage msg = new ControlMessage();
        msg.type = "request";
        msg.action = "connect";
        msg.address = "127.0.0.1";
        msg.port = httpServer.getPort();
        ws.send(gson.toJson(msg));

        ArgumentCaptor<String> respTextMsg = ArgumentCaptor.forClass(String.class);
        verify(listener, timeout(1000)).onMessage(eq(ws), respTextMsg.capture());
        msg = gson.fromJson(respTextMsg.getValue(), ControlMessage.class);
        assertEquals("response", msg.type);
        assertEquals("success", msg.action);

        StringBuffer sb = new StringBuffer()
                .append("GET / HTTP/1.1\r\n")
                .append("\r\n");
        ws.send(ByteString.of(sb.toString().getBytes()));

        RecordedRequest recordedReq = httpServer.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("GET", recordedReq.getMethod());

        ArgumentCaptor<ByteString> respByteMsg = ArgumentCaptor.forClass(ByteString.class);
        verify(listener, timeout(1000)).onMessage(eq(ws), respByteMsg.capture());
        assertEquals("HTTP/1.1 200 OK\r\nContent-Length: 11\r\n\r\nHelloWorld!", StandardCharsets.UTF_8
                .newDecoder()
                .decode(respByteMsg.getValue().asByteBuffer())
                .toString());

        // https://tools.ietf.org/html/rfc6455#section-7.4
        ws.close(1000, "Normal Closure");

        server.stop();
        httpServer.shutdown();
    }

    @Test
    public void testProxyMultiStream() throws Exception {
        Gson gson = new Gson();
        MockWebServer httpServer = new MockWebServer();
        httpServer.enqueue(new MockResponse().setResponseCode(200).setBody("HelloWorld1"));
        httpServer.enqueue(new MockResponse().setResponseCode(200).setBody("HelloWorld2"));
        httpServer.start();

        WsProxyServer server = new WsProxyServer()
                .start();

        OkHttpClient client = new OkHttpClient.Builder()
                .build();
        Request request = new Request.Builder()
                .url("ws://127.0.0.1:" + server.port() + "/ws")
                .build();

        WebSocketListener listener1 = mock(WebSocketListener.class);
        WebSocketListener listener2 = mock(WebSocketListener.class);
        WebSocket ws1 = client.newWebSocket(request, listener1);
        WebSocket ws2 = client.newWebSocket(request, listener2);

        ArgumentCaptor<Response> response1 = ArgumentCaptor.forClass(Response.class);
        ArgumentCaptor<Response> response2 = ArgumentCaptor.forClass(Response.class);
        verify(listener1, timeout(1000)).onOpen(eq(ws1), response1.capture());
        verify(listener2, timeout(1000)).onOpen(eq(ws2), response2.capture());

        ControlMessage msg = new ControlMessage();
        msg.type = "request";
        msg.action = "connect";
        msg.address = "127.0.0.1";
        msg.port = httpServer.getPort();
        ws1.send(gson.toJson(msg));
        ws2.send(gson.toJson(msg));

        ArgumentCaptor<String> respTextMsg = ArgumentCaptor.forClass(String.class);
        verify(listener1, timeout(1000)).onMessage(eq(ws1), respTextMsg.capture());
        msg = gson.fromJson(respTextMsg.getValue(), ControlMessage.class);
        assertEquals("response", msg.type);
        assertEquals("success", msg.action);

        verify(listener2, timeout(1000)).onMessage(eq(ws2), respTextMsg.capture());
        msg = gson.fromJson(respTextMsg.getValue(), ControlMessage.class);
        assertEquals("response", msg.type);
        assertEquals("success", msg.action);

        StringBuffer sb = new StringBuffer()
                .append("GET / HTTP/1.1\r\n")
                .append("\r\n");
        ws1.send(ByteString.of(sb.toString().getBytes()));
        ws2.send(ByteString.of(sb.toString().getBytes()));

        ArgumentCaptor<ByteString> respByteMsg1 = ArgumentCaptor.forClass(ByteString.class);
        verify(listener1, timeout(1000)).onMessage(eq(ws1), respByteMsg1.capture());
        assertEquals("HTTP/1.1 200 OK\r\nContent-Length: 11\r\n\r\nHelloWorld1", StandardCharsets.UTF_8
                .newDecoder()
                .decode(respByteMsg1.getValue().asByteBuffer())
                .toString());

        ArgumentCaptor<ByteString> respByteMsg2 = ArgumentCaptor.forClass(ByteString.class);
        verify(listener2, timeout(1000)).onMessage(eq(ws2), respByteMsg2.capture());
        assertEquals("HTTP/1.1 200 OK\r\nContent-Length: 11\r\n\r\nHelloWorld2", StandardCharsets.UTF_8
                .newDecoder()
                .decode(respByteMsg2.getValue().asByteBuffer())
                .toString());

        ws1.close(1000, "NormalClosure");
        ws2.close(1000, "NormalClosure");

        server.stop();
        httpServer.shutdown();
    }

    @Test
    public void testProxyLargeFrame() throws Exception {
        Gson gson = new Gson();
        EchoServer echoServer = new EchoServer().start(false);
        WsProxyServer proxyServer = new WsProxyServer()
                .start();

//        WebSocketListener listener = mock(WebSocketListener.class);
        final List<ByteBuffer> bbList = new ArrayList<>();
        WebSocketListener listener = spy(new WebSocketListener() {
            @Override
            public void onMessage(@NotNull WebSocket webSocket, @NotNull ByteString bytes) {
                bbList.add(bytes.asByteBuffer());
            }
        });
        OkHttpClient client = new OkHttpClient.Builder()
                .build();
        Request request = new Request.Builder()
                .url("ws://127.0.0.1:" + proxyServer.port() + "/ws")
                .build();
        WebSocket ws = client.newWebSocket(request, listener);

        ArgumentCaptor<Response> response = ArgumentCaptor.forClass(Response.class);
        verify(listener, timeout(1000)).onOpen(eq(ws), response.capture());

        ControlMessage msg = new ControlMessage();
        msg.type = "request";
        msg.action = "connect";
        msg.address = "127.0.0.1";
        msg.port = EchoServer.PORT;
        ws.send(gson.toJson(msg));

        ArgumentCaptor<String> respTextMsg = ArgumentCaptor.forClass(String.class);
        verify(listener, timeout(1000)).onMessage(eq(ws), respTextMsg.capture());
        msg = gson.fromJson(respTextMsg.getValue(), ControlMessage.class);
        assertEquals("response", msg.type);
        assertEquals("success", msg.action);

        StringBuffer sb1 = new StringBuffer();
        StringBuffer sb2 = new StringBuffer();
        int total = 65536;
        for (int i = 0; i < total; i++) {
            sb1.append((char) ((i % 26) + 'a'));
            sb2.append((char) ((i % 26) + 'A'));
        }
        ws.send(ByteString.of(sb1.toString().getBytes()));
        ws.send(ByteString.of(sb2.toString().getBytes()));

//        ArgumentCaptor<ByteString> respByteMsg = ArgumentCaptor.forClass(ByteString.class);
//        verify(listener, after(1000).times(4)).onMessage(eq(ws), respByteMsg.capture());
        verify(listener, after(1000).times(4)).onMessage(eq(ws), any(ByteString.class));

        int all = 0;
        for (ByteBuffer bb : bbList) {
            all += bb.remaining();
        }
        ByteBuffer respBuf = ByteBuffer.allocate(all);
        for (ByteBuffer bb : bbList) {
            respBuf.put(bb);
        }
        respBuf.rewind();

        int idx = 0; // The first byte
        assertEquals((idx % 26) + 'a', respBuf.get(idx));

        idx = sb1.length() - 1; // The last byte
        assertEquals((idx % 26) + 'a', respBuf.get(idx));

        Random rand = new Random();
        for (int i = 0; i < 9; i++) {
            idx = rand.nextInt(total);
            assertEquals((idx % 26) + (idx > 65535 ? 'A' : 'a'), respBuf.get(idx));
        }

        proxyServer.stop();
        echoServer.stop();
    }
}
