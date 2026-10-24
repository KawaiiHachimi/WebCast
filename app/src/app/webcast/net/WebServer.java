package app.webcast.net;

import android.content.Context;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;

/**
 * LAN HTTPS + WebSocket server for the sender page. Runs entirely on the TV.
 *
 * GET /            sender page (assets/web)
 * GET /api/info    device info for the page
 * GET /health      "ok"
 * GET /ws          WebSocket (see PROTOCOL.md)
 */
public final class WebServer {
  private static final String TAG = "WebCastWeb";
  private static final Charset UTF8 = Charset.forName("UTF-8");
  private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
  private static final int PREFERRED_PORT = 8443;
  private static final int REDIRECT_PORT = 8080;
  private static final int MAX_HEADER_BYTES = 16 * 1024;
  private static final int MAX_MESSAGE_BYTES = 8 * 1024 * 1024;

  /** Implemented by CastService. All callbacks run on network threads. */
  public interface Callback {
    void onSenderConnected();
    void onSenderClosed(String reason);
    void onControl(JSONObject msg);
    void onVideoFrame(byte[] data, int off, int len, boolean keyframe, long ptsMs);
    void onAudioFrame(byte[] data, int off, int len);
  }

  private final Context context;
  private final Callback callback;
  private final ExecutorService workers = Executors.newCachedThreadPool();
  private volatile boolean running;
  private volatile int port = -1;
  private volatile String ip;
  private SSLServerSocket serverSocket;
  private ServerSocket redirectSocket;
  private Thread acceptThread;
  private Thread redirectThread;
  private Thread writerThread;
  private final LinkedBlockingQueue<String> outbox = new LinkedBlockingQueue<String>(64);
  private volatile WsConnection sender;

  public WebServer(Context context, Callback callback) {
    this.context = context.getApplicationContext();
    this.callback = callback;
  }

  public synchronized void start() throws Exception {
    if (running) return;
    LanAddress.Result addr = LanAddress.find();
    ip = addr == null ? null : addr.ip;

    SSLServerSocketFactory factory = TlsContext.createServerSocketFactory(context.getAssets());
    SSLServerSocket socket = null;
    int chosen = -1;
    for (int candidate = PREFERRED_PORT; candidate < PREFERRED_PORT + 10 && socket == null; candidate++) {
      try {
        socket = (SSLServerSocket) factory.createServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(candidate));
        socket.setEnabledProtocols(new String[] {"TLSv1.2", "TLSv1.3"});
        chosen = candidate;
      } catch (Exception busy) {
        if (socket != null) try { socket.close(); } catch (IOException ignored) {}
        socket = null;
        Log.w(TAG, "port " + candidate + " unavailable: " + busy);
      }
    }
    if (socket == null) throw new IOException("No free TLS port");
    serverSocket = socket;
    port = chosen;
    running = true;
    Log.i(TAG, "HTTPS listening on " + (ip == null ? "?" : ip) + ":" + port);

    acceptThread = new Thread(new Runnable() {
      @Override public void run() { acceptLoop(); }
    }, "webcast-https");
    acceptThread.start();

    redirectThread = new Thread(new Runnable() {
      @Override public void run() { redirectLoop(); }
    }, "webcast-http");
    redirectThread.start();

    // All server->browser messages go through this queue: callbacks (decoder/UI threads) must
    // never touch a socket, and NetworkOnMainThreadException would otherwise fire.
    writerThread = new Thread(new Runnable() {
      @Override public void run() { writerLoop(); }
    }, "webcast-ws-writer");
    writerThread.start();
  }

  private void writerLoop() {
    while (running) {
      String message;
      try {
        message = outbox.poll(1, TimeUnit.SECONDS);
      } catch (InterruptedException stop) {
        break;
      }
      if (message == null) continue;
      WsConnection target = sender;
      if (target != null) target.sendText(message);
    }
  }

  public synchronized void stop() {
    if (!running) return;
    running = false;
    closeSender("server stopped");
    try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
    try { if (redirectSocket != null) redirectSocket.close(); } catch (IOException ignored) {}
    outbox.clear();
    if (writerThread != null) writerThread.interrupt();
    workers.shutdownNow();
    serverSocket = null;
    redirectSocket = null;
    port = -1;
  }

  public boolean isRunning() { return running; }
  public int getPort() { return port; }
  public String getIp() { return ip; }
  public boolean isSenderOnline() {
    WsConnection s = sender;
    return s != null && s.open;
  }

  public String url() {
    return ip == null ? null : "https://" + ip + ":" + port + "/";
  }

  /** Same JSON as GET /api/info, reused for the hello-ack message. */
  public String infoJsonString() {
    return infoJson().toString();
  }

  /** Re-reads the LAN address after a Wi-Fi/network change. */
  public void refreshAddress() {
    LanAddress.Result addr = LanAddress.find();
    if (addr != null && addr.ip != null) ip = addr.ip;
  }

  public void sendText(String json) {
    if (json == null) return;
    WsConnection s = sender;
    if (s == null || !s.open) return;
    if (!outbox.offer(json)) {
      outbox.poll();          // drop the oldest low-priority message (usually a stats line)
      outbox.offer(json);
    }
  }

  public void sendNeedKeyframe() {
    sendText("{\"t\":\"need-keyframe\"}");
  }

  public void closeSender(String reason) {
    WsConnection s = sender;
    if (s != null) s.close(reason);
    sender = null;
  }

  // ---------------------------------------------------------------- HTTPS accept

  private void acceptLoop() {
    while (running) {
      Socket raw = null;
      try {
        raw = serverSocket.accept();
        if (!isLanClient(raw)) { raw.close(); continue; }
        raw.setSoTimeout(20000);
        final Socket socket = raw;
        workers.execute(new Runnable() {
          @Override public void run() { serve(socket); }
        });
      } catch (IOException e) {
        if (running) Log.w(TAG, "accept failed: " + e);
        if (raw != null) try { raw.close(); } catch (IOException ignored) {}
        if (!running) break;
      }
    }
  }

  private boolean isLanClient(Socket socket) {
    try {
      return socket.getInetAddress().isSiteLocalAddress() || socket.getInetAddress().isLoopbackAddress();
    } catch (Exception e) {
      return false;
    }
  }

  private void serve(Socket raw) {
    boolean upgraded = false;
    try {
      if (raw instanceof SSLSocket) {
        try {
          SSLSocket ssl = (SSLSocket) raw;
          ssl.startHandshake();
          Log.i(TAG, "TLS " + ssl.getSession().getProtocol() + " " + ssl.getSession().getCipherSuite()
              + " from " + raw.getInetAddress().getHostAddress());
        } catch (Exception handshake) {
          Log.w(TAG, "handshake failed: " + handshake);
          return;
        }
      }
      InputStream in = new BufferedInputStream(raw.getInputStream(), 16384);
      OutputStream out = new BufferedOutputStream(raw.getOutputStream(), 16384);
      String requestLine = readLine(in);
      if (requestLine == null) return;
      String[] parts = requestLine.split(" ");
      if (parts.length != 3) { respond(out, 400, "text/plain", "bad request".getBytes(UTF8)); return; }
      String method = parts[0];
      String target = parts[1];
      Map<String, String> headers = readHeaders(in);
      int query = target.indexOf('?');
      String path = query < 0 ? target : target.substring(0, query);

      if ("/ws".equals(path) && "websocket".equalsIgnoreCase(headers.get("upgrade"))) {
        upgraded = handleWebSocket(raw, in, out, headers);
        return;
      }
      if (!"GET".equals(method) && !"HEAD".equals(method)) {
        respond(out, 405, "text/plain", "method not allowed".getBytes(UTF8));
        return;
      }
      serveHttp(out, path, headers, "HEAD".equals(method));
    } catch (Exception e) {
      Log.w(TAG, "connection error: " + e);
    } finally {
      if (!upgraded) try { raw.close(); } catch (IOException ignored) {}
    }
  }

  private void serveHttp(OutputStream out, String path, Map<String, String> headers, boolean head)
      throws IOException {
    if ("/api/info".equals(path)) {
      byte[] body = infoJson().toString().getBytes(UTF8);
      respond(out, 200, "application/json; charset=utf-8", body, head);
      return;
    }
    if ("/health".equals(path)) {
      respond(out, 200, "text/plain; charset=utf-8", "ok".getBytes(UTF8), head);
      return;
    }
    if ("/favicon.ico".equals(path)) {
      respond(out, 204, null, new byte[0], head);
      return;
    }
    String asset = assetFor(path);
    if (asset == null) {
      respond(out, 404, "text/plain; charset=utf-8", "not found".getBytes(UTF8), head);
      return;
    }
    byte[] body = readAsset(asset);
    if (body == null) {
      respond(out, 404, "text/plain; charset=utf-8", "not found".getBytes(UTF8), head);
      return;
    }
    respond(out, 200, contentType(asset), body, head);
  }

  private static String assetFor(String path) {
    if ("/".equals(path) || "/index.html".equals(path)) return "web/index.html";
    if ("/app.js".equals(path)) return "web/app.js";
    if ("/style.css".equals(path)) return "web/style.css";
    if ("/worklet.js".equals(path)) return "web/worklet.js";
    if (path.startsWith("/assets/")) return "web/" + path.substring("/assets/".length());
    return null;
  }

  private static String contentType(String asset) {
    if (asset.endsWith(".html")) return "text/html; charset=utf-8";
    if (asset.endsWith(".js")) return "application/javascript; charset=utf-8";
    if (asset.endsWith(".css")) return "text/css; charset=utf-8";
    if (asset.endsWith(".svg")) return "image/svg+xml";
    if (asset.endsWith(".png")) return "image/png";
    return "application/octet-stream";
  }

  private byte[] readAsset(String name) {
    try {
      InputStream in = context.getAssets().open(name);
      try {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16384);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
      } finally {
        in.close();
      }
    } catch (IOException e) {
      return null;
    }
  }

  private void respond(OutputStream out, int code, String type, byte[] body) throws IOException {
    respond(out, code, type, body, false);
  }

  private void respond(OutputStream out, int code, String type, byte[] body, boolean head) throws IOException {
    StringBuilder sb = new StringBuilder(128);
    sb.append("HTTP/1.1 ").append(code).append(' ').append(statusText(code)).append("\r\n");
    if (type != null) sb.append("Content-Type: ").append(type).append("\r\n");
    sb.append("Content-Length: ").append(body.length).append("\r\n");
    sb.append("Cache-Control: no-store\r\n");
    sb.append("X-Content-Type-Options: nosniff\r\n");
    sb.append("Connection: close\r\n\r\n");
    out.write(sb.toString().getBytes(UTF8));
    if (!head && body.length > 0) out.write(body);
    out.flush();
  }

  private static String statusText(int code) {
    switch (code) {
      case 200: return "OK";
      case 204: return "No Content";
      case 302: return "Found";
      case 400: return "Bad Request";
      case 404: return "Not Found";
      case 405: return "Method Not Allowed";
      default: return "Error";
    }
  }

  private JSONObject infoJson() {
    JSONObject o = new JSONObject();
    try {
      int[] size = displaySize();
      o.put("name", "WebCast");
      o.put("model", "Android TV");
      o.put("version", "1.1.0");
      o.put("protocol", 1);
      o.put("ip", ip == null ? "" : ip);
      o.put("port", port);
      o.put("width", size[0]);
      o.put("height", size[1]);
      o.put("fps", refreshRate());
      o.put("audio", true);
      o.put("codecs", new org.json.JSONArray().put("avc1.42E028").put("avc1.4D4028"));
      o.put("maxWidth", size[0]);
      o.put("maxHeight", size[1]);
    } catch (Exception ignored) {}
    return o;
  }

  private int[] displaySize() {
    try {
      WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
      Display d = wm.getDefaultDisplay();
      int w = d.getWidth();
      int h = d.getHeight();
      if (w > 0 && h > 0) return new int[] {w, h};
    } catch (Exception ignored) {}
    return new int[] {1920, 1080};
  }

  private int refreshRate() {
    try {
      WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
      Display d = wm.getDefaultDisplay();
      float rate = d.getRefreshRate();
      return rate > 0 ? Math.round(rate) : 50;
    } catch (Exception e) {
      return 50;
    }
  }

  // ---------------------------------------------------------------- HTTP redirect

  private void redirectLoop() {
    ServerSocket socket = null;
    try {
      socket = new ServerSocket();
      socket.setReuseAddress(true);
      socket.bind(new InetSocketAddress(REDIRECT_PORT));
      redirectSocket = socket;
      Log.i(TAG, "HTTP redirect listening on " + REDIRECT_PORT);
    } catch (IOException e) {
      Log.w(TAG, "HTTP redirect unavailable: " + e);
      return;
    }
    while (running) {
      try {
        final Socket client = socket.accept();
        workers.execute(new Runnable() {
          @Override public void run() { serveRedirect(client); }
        });
      } catch (IOException e) {
        if (running) Log.w(TAG, "redirect accept failed: " + e);
        break;
      }
    }
  }

  private void serveRedirect(Socket client) {
    try {
      client.setSoTimeout(5000);
      BufferedInputStream in = new BufferedInputStream(client.getInputStream());
      String request = readLine(in);
      if (request == null) return;
      readHeaders(in);
      String location = url();
      String body = location == null ? "TV is offline" : "Moved to " + location;
      String response = "HTTP/1.1 302 Found\r\nLocation: " + (location == null ? "/" : location)
          + "\r\nContent-Length: " + body.length() + "\r\nConnection: close\r\n\r\n" + body;
      OutputStream out = client.getOutputStream();
      out.write(response.getBytes(UTF8));
      out.flush();
    } catch (Exception ignored) {
    } finally {
      try { client.close(); } catch (IOException ignored) {}
    }
  }

  // ---------------------------------------------------------------- WebSocket

  private boolean handleWebSocket(Socket socket, InputStream in, OutputStream out, Map<String, String> headers) {
    String key = headers.get("sec-websocket-key");
    if (key == null) { try { respond(out, 400, "text/plain", "missing key".getBytes(UTF8)); } catch (IOException ignored) {} return false; }
    String accept;
    try {
      MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
      accept = base64(sha1.digest((key + WS_GUID).getBytes(UTF8)));
    } catch (Exception e) {
      return false;
    }
    try {
      String response = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
          + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
      out.write(response.getBytes(UTF8));
      out.flush();
    } catch (IOException e) {
      return false;
    }
    WsConnection conn = new WsConnection(socket, in, out);
    try {
      socket.setSoTimeout(0); // long-lived stream: no read timeout once upgraded
    } catch (IOException ignored) {}
    WsConnection previous;
    synchronized (this) {
      previous = sender;
      sender = conn;
    }
    if (previous != null) previous.close("replaced by new sender");
    callback.onSenderConnected();
    Log.i(TAG, "sender connected from " + socket.getInetAddress().getHostAddress());
    String reason = "closed";
    try {
      conn.readLoop();
    } finally {
      conn.close(reason);
      synchronized (this) {
        if (sender == conn) sender = null;
      }
      Log.i(TAG, "sender disconnected: " + reason);
      callback.onSenderClosed(reason);
    }
    return true;
  }

  private static String base64(byte[] data) {
    return android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
  }

  private final class WsConnection {
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Object writeLock = new Object();
    volatile boolean open = true;
    private final AtomicLong frames = new AtomicLong();
    private final ByteArrayOutputStream message = new ByteArrayOutputStream(64 * 1024);
    private int messageOpcode = 0;

    WsConnection(Socket socket, InputStream in, OutputStream out) {
      this.socket = socket;
      this.in = in;
      this.out = out;
    }

    void readLoop() {
      try {
        while (open) {
          if (!readFrame()) break;
        }
      } catch (Exception e) {
        if (open) Log.w(TAG, "ws read error: " + e);
      } finally {
        close("read loop ended");
      }
    }

    private boolean readFrame() throws IOException {
      int b0 = in.read();
      if (b0 < 0) return false;
      int b1 = in.read();
      if (b1 < 0) return false;
      boolean fin = (b0 & 0x80) != 0;
      int opcode = b0 & 0x0F;
      boolean masked = (b1 & 0x80) != 0;
      long len = b1 & 0x7F;
      if (len == 126) {
        len = ((long) readByte() << 8) | readByte();
      } else if (len == 127) {
        len = 0;
        for (int i = 0; i < 8; i++) len = (len << 8) | readByte();
      }
      if (len < 0 || len > MAX_MESSAGE_BYTES) throw new IOException("frame too large: " + len);
      if (!masked) throw new IOException("unmasked client frame");
      byte[] mask = new byte[4];
      readFully(mask);
      byte[] payload = new byte[(int) len];
      readFully(payload);
      for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];

      if (opcode == 0x8) { sendFrame(0x8, payload, 0, payload.length); return false; }
      if (opcode == 0x9) { sendFrame(0xA, payload, 0, payload.length); return true; }
      if (opcode == 0xA) return true;
      if (opcode != 0 && opcode != 1 && opcode != 2) return true;

      if (opcode != 0) {
        message.reset();
        messageOpcode = opcode;
      }
      if (message.size() + payload.length > MAX_MESSAGE_BYTES) {
        throw new IOException("message too large");
      }
      message.write(payload);
      if (!fin) return true;
      byte[] complete = message.toByteArray();
      message.reset();
      if (messageOpcode == 1) {
        String text = new String(complete, UTF8);
        JSONObject json = null;
        try { json = new JSONObject(text); } catch (Exception e) { Log.w(TAG, "bad json: " + e); }
        if (json != null) callback.onControl(json);
      } else if (messageOpcode == 2) {
        dispatchBinary(complete);
      }
      frames.incrementAndGet();
      return true;
    }

    private void dispatchBinary(byte[] data) {
      if (data.length < 1) return;
      int type = data[0] & 0xFF;
      if (type == 1) {
        if (data.length < 8) return;
        int flags = data[1] & 0xFF;
        long pts = ((long) (data[4] & 0xFF) << 24) | ((data[5] & 0xFF) << 16)
            | ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
        callback.onVideoFrame(data, 8, data.length - 8, (flags & 1) != 0, pts);
      } else if (type == 2) {
        if (data.length < 4) return;
        callback.onAudioFrame(data, 4, data.length - 4);
      }
    }

    private int readByte() throws IOException {
      int b = in.read();
      if (b < 0) throw new EOFException();
      return b;
    }

    private void readFully(byte[] buf) throws IOException {
      int at = 0;
      while (at < buf.length) {
        int n = in.read(buf, at, buf.length - at);
        if (n < 0) throw new EOFException();
        at += n;
      }
    }

    void sendText(String text) {
      byte[] payload = text.getBytes(UTF8);
      sendFrame(1, payload, 0, payload.length);
    }

    void sendFrame(int opcode, byte[] payload, int off, int len) {
      if (!open) return;
      try {
        synchronized (writeLock) {
          if (!open) return;
          out.write(0x80 | opcode);
          if (len < 126) {
            out.write(len);
          } else if (len <= 0xFFFF) {
            out.write(126);
            out.write((len >>> 8) & 0xFF);
            out.write(len & 0xFF);
          } else {
            out.write(127);
            for (int shift = 56; shift >= 0; shift -= 8) out.write((int) (((long) len >>> shift) & 0xFF));
          }
          out.write(payload, off, len);
          out.flush();
        }
      } catch (IOException e) {
        close("write failed: " + e.getMessage());
      }
    }

    void close(String reason) {
      if (!open) return;
      open = false;
      try { sendFrame(0x8, new byte[0], 0, 0); } catch (Throwable ignored) {}
      try { socket.close(); } catch (IOException ignored) {}
    }
  }

  // ---------------------------------------------------------------- helpers

  private static String readLine(InputStream in) throws IOException {
    ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
    int b;
    while ((b = in.read()) >= 0) {
      if (b == '\n') break;
      if (b != '\r') buf.write(b);
      if (buf.size() > MAX_HEADER_BYTES) throw new IOException("header line too long");
    }
    if (b < 0 && buf.size() == 0) return null;
    return new String(buf.toByteArray(), UTF8);
  }

  private static Map<String, String> readHeaders(InputStream in) throws IOException {
    Map<String, String> headers = new HashMap<String, String>();
    int total = 0;
    String line;
    while ((line = readLine(in)) != null && line.length() > 0) {
      total += line.length();
      if (total > MAX_HEADER_BYTES) throw new IOException("headers too large");
      int colon = line.indexOf(':');
      if (colon <= 0) continue;
      String name = line.substring(0, colon).trim().toLowerCase(Locale.US);
      String value = line.substring(colon + 1).trim();
      if (!headers.containsKey(name)) headers.put(name, value);
    }
    return headers;
  }
}
