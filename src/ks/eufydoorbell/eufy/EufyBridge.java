// SPDX-License-Identifier: Apache-2.0
package ks.eufydoorbell.eufy;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A session with the eufy-security-ws bridge (the Home Assistant add-on, port 3002 by default): signs in with the
 * newest API schema, starts listening, and reports the events this plugin cares about. It stays connected and
 * reconnects by itself, so a ring is heard whenever the bridge is up. Callbacks come from the session's own thread and
 * must return quickly; video and audio arrive through {@link Listener#onMedia}.
 */
public final class EufyBridge {
    private static final int SCHEMA = 21;

    public interface Listener {
        /** "connecting", "connected" or a reason the session dropped. */
        void onConnection(boolean connected, String detail);

        /** The device's ring state changed: state true is a button press. */
        void onRing(String serial, boolean ringing);

        /** "livestream started", "livestream stopped", "talkback started", ... for the device. */
        void onDeviceEvent(String serial, String event);

        /** One chunk of an active livestream: video is H.264 in Annex B, audio is AAC in ADTS. */
        void onMedia(String serial, boolean video, byte[] data, int fps, int width, int height);

        /** A device's new event picture (the person who pressed the button, or walked up): a JPEG as the camera sent it. */
        default void onPicture(String serial, byte[] image) {}

        /** The result of a command sent with {@link #send}: errorCode is null on success. */
        default void onResult(String messageId, String errorCode) {}
    }

    private final String host;
    private final int port;
    private final Listener listener;
    private final AtomicInteger ids = new AtomicInteger();
    private volatile WsClient client;
    private volatile boolean running;
    private volatile boolean connected;
    private Thread thread;

    public EufyBridge(String host, int port, Listener listener) {
        this.host = host;
        this.port = port;
        this.listener = listener;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::run, "eufy-bridge");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        Thread t;
        WsClient c;
        synchronized (this) {
            running = false;
            t = thread;
            thread = null;
            c = client;
            client = null;
        }
        connected = false;
        if (c != null) c.close();
        if (t != null) t.interrupt();
    }

    public boolean connected() {
        return connected;
    }

    /** Sends one command, such as device.start_livestream, and returns its message id; null when not connected. */
    public String send(String command, String serial) {
        WsClient c = client;
        if (c == null || !connected) return null;
        String id = command + "." + ids.incrementAndGet();
        try {
            JSONObject message = new JSONObject();
            message.put("messageId", id);
            message.put("command", command);
            if (serial != null) message.put("serialNumber", serial);
            c.sendText(message.toString());
            return id;
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    private void run() {
        long backoff = 1000;
        while (running) {
            WsClient c = new WsClient();
            try {
                listener.onConnection(false, "connecting");
                c.connect(host, port, 8000);
                client = c;
                String hello = c.readText();
                if (hello == null) throw new IOException("the bridge closed the connection");
                JSONObject version = new JSONObject(hello);
                int schema = Math.min(SCHEMA, version.optInt("maxSchemaVersion", SCHEMA));
                c.sendText("{\"messageId\":\"schema\",\"command\":\"set_api_schema\",\"schemaVersion\":" + schema + "}");
                c.sendText("{\"messageId\":\"listen\",\"command\":\"start_listening\"}");
                connected = true;
                backoff = 1000;
                listener.onConnection(true, "connected");
                String text;
                while (running && (text = c.readText()) != null) handle(text);
                throw new IOException("the bridge closed the connection");
            } catch (IOException | JSONException e) {
                connected = false;
                client = null;
                c.close();
                if (!running) return;
                String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                listener.onConnection(false, why);
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException stopped) {
                return;
            }
            backoff = Math.min(30_000, backoff * 2);
        }
    }

    private void handle(String text) {
        // Video and audio first, and without a JSON parse: a keyframe is a list of a hundred thousand numbers.
        boolean video = text.contains("\"event\":\"livestream video data\"");
        if (video || text.contains("\"event\":\"livestream audio data\"")) {
            byte[] data = MediaData.bytes(text);
            if (data == null) return;
            String serial = MediaData.string(text, "serialNumber");
            if (serial == null) return;
            listener.onMedia(serial, video, data, MediaData.number(text, "videoFPS"), MediaData.number(text, "videoWidth"), MediaData.number(text, "videoHeight"));
            return;
        }
        // A new event picture: {"event":"property changed","name":"picture","value":{"data":{"type":"Buffer","data":[...]},...}}.
        if (text.contains("\"event\":\"property changed\"") && text.contains("\"name\":\"picture\"")) {
            byte[] image = MediaData.bytes(text);
            String serial = MediaData.string(text, "serialNumber");
            if (image != null && image.length > 0 && serial != null) listener.onPicture(serial, image);
            return;
        }
        try {
            JSONObject message = new JSONObject(text);
            String type = message.optString("type");
            if (type.equals("event")) {
                JSONObject event = message.optJSONObject("event");
                if (event == null || !"device".equals(event.optString("source"))) return;
                String name = event.optString("event");
                String serial = event.optString("serialNumber");
                if (name.equals("rings")) listener.onRing(serial, event.optBoolean("state"));
                else if (name.startsWith("livestream") || name.startsWith("talkback")) listener.onDeviceEvent(serial, name);
            } else if (type.equals("result")) {
                String id = message.optString("messageId");
                if (id.contains(".")) listener.onResult(id, message.optBoolean("success", true) ? null : message.optString("errorCode", "error"));
            }
        } catch (JSONException ignored) {
            // A message this plugin does not understand is not an error.
        }
    }
}
