// SPDX-License-Identifier: Apache-2.0
package ks.eufydoorbell.eufy;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * A minimal RFC 6455 websocket client for plain ws:// (the eufy-security-ws bridge on the local network): text frames
 * both ways, pings answered, fragments joined. Android gives plugins no websocket library, so this is all there is.
 */
public final class WsClient {
    private static final int MAX_MESSAGE = 64 * 1024 * 1024;  // a 2K keyframe sent as a JSON number list is a few MB
    private final Socket socket = new Socket();
    private DataInputStream in;
    private OutputStream out;
    private final Object writeLock = new Object();
    private final SecureRandom random = new SecureRandom();

    public void connect(String host, int port, int timeoutMillis) throws IOException {
        socket.connect(new InetSocketAddress(host, port), timeoutMillis);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(0);
        in = new DataInputStream(socket.getInputStream());
        out = socket.getOutputStream();
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String key = Base64.getEncoder().encodeToString(nonce);
        String request = "GET / HTTP/1.1\r\nHost: " + host + ":" + port + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
            + "Sec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
        synchronized (writeLock) {
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }
        String status = readLine(in);
        if (status == null || !status.contains(" 101")) throw new IOException("websocket refused: " + status);
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            // Headers are not needed: the bridge does not negotiate extensions.
        }
    }

    /** The next text message, or null once the connection closed. Blocks. Pings are answered on the way. */
    public String readText() throws IOException {
        ByteArrayOutputStream message = null;
        boolean text = false;
        while (true) {
            int b0 = in.read();
            if (b0 < 0) return null;
            int b1 = in.readUnsignedByte();
            int opcode = b0 & 0x0f;
            boolean fin = (b0 & 0x80) != 0;
            long length = b1 & 0x7f;
            if (length == 126) length = in.readUnsignedShort();
            else if (length == 127) length = in.readLong();
            boolean masked = (b1 & 0x80) != 0;
            byte[] mask = null;
            if (masked) {
                mask = new byte[4];
                in.readFully(mask);
            }
            if (length < 0 || length > MAX_MESSAGE) throw new IOException("websocket frame too large: " + length);
            byte[] payload = new byte[(int) length];
            in.readFully(payload);
            if (masked) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
            switch (opcode) {
                case 0x8:  // close
                    return null;
                case 0x9:  // ping
                    writeFrame(0xA, payload);
                    continue;
                case 0xA:  // pong
                    continue;
                case 0x1:
                case 0x2:
                    text = opcode == 0x1;
                    message = new ByteArrayOutputStream(payload.length);
                    message.write(payload);
                    break;
                case 0x0:  // continuation
                    if (message == null) throw new IOException("websocket continuation without a start");
                    if (message.size() + payload.length > MAX_MESSAGE) throw new IOException("websocket message too large");
                    message.write(payload);
                    break;
                default:
                    throw new IOException("websocket opcode " + opcode);
            }
            if (fin && message != null) {
                if (!text) {
                    message = null;  // binary frames carry nothing this plugin uses
                    continue;
                }
                return new String(message.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }

    public void sendText(String text) throws IOException {
        writeFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
    }

    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already gone.
        }
    }

    private void writeFrame(int opcode, byte[] payload) throws IOException {
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        ByteArrayOutputStream frame = new ByteArrayOutputStream(payload.length + 14);
        frame.write(0x80 | opcode);
        if (payload.length < 126) {
            frame.write(0x80 | payload.length);
        } else if (payload.length < 65536) {
            frame.write(0x80 | 126);
            frame.write(payload.length >> 8);
            frame.write(payload.length & 0xff);
        } else {
            frame.write(0x80 | 127);
            for (int shift = 56; shift >= 0; shift -= 8) frame.write((int) ((long) payload.length >> shift) & 0xff);
        }
        frame.write(mask);
        for (int i = 0; i < payload.length; i++) frame.write(payload[i] ^ mask[i & 3]);
        synchronized (writeLock) {
            out.write(frame.toByteArray());
            out.flush();
        }
    }

    private static String readLine(InputStream stream) throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = stream.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') line.append((char) c);
            if (line.length() > 8192) throw new IOException("websocket header too long");
        }
        return c < 0 && line.length() == 0 ? null : line.toString();
    }
}
