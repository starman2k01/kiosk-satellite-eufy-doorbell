// SPDX-License-Identifier: Apache-2.0
package ks.eufydoorbell.eufy;

import ks.eufydoorbell.media.FrameHub;
import ks.eufydoorbell.media.MediaStream;

/**
 * One live camera stream for the length of a call, fetched through the eufy-security-ws bridge: asks the bridge to
 * start the camera's livestream, then turns its chunks into what the viewer takes: H.264 access units in Annex B, and
 * AAC frames with their AudioSpecificConfig (the bridge sends AAC in ADTS, so each frame's header is read and removed).
 */
public final class EufyStream implements MediaStream {
    private static final int[] RATES = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};
    private static final long NO_VIDEO_MILLIS = 15_000;

    private final EufyBridge bridge;
    private final String serial;
    private final Listener listener;
    private final FrameHub hub = new FrameHub();
    private volatile boolean running;
    private volatile boolean delivered;
    private volatile boolean over;  // failed or ended: a stop must not reach the camera, or it would end the stream that replaced this one
    private long baseNanos;
    private Thread starter;
    private boolean audioFormatSent;

    public EufyStream(EufyBridge bridge, String serial, Listener listener) {
        this.bridge = bridge;
        this.serial = serial;
        this.listener = listener;
    }

    public String serial() {
        return serial;
    }

    @Override public synchronized void start() {
        if (running) return;
        running = true;
        hub.start();
        baseNanos = System.nanoTime();
        Thread thread = new Thread(this::open, "eufy-stream-start");
        thread.setDaemon(true);
        starter = thread;
        thread.start();
    }

    private void open() {
        listener.onStreamState("connecting");
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (running && !bridge.connected() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException stopped) {
                return;
            }
        }
        if (!running) return;
        if (bridge.send("device.start_livestream", serial) == null) {
            over = true;
            listener.onStreamState("stream failed: the bridge is not connected");
            return;
        }
        try {
            Thread.sleep(NO_VIDEO_MILLIS);
        } catch (InterruptedException stopped) {
            return;
        }
        if (running && !delivered) {
            over = true;
            listener.onStreamState("stream failed: no video from the camera in " + NO_VIDEO_MILLIS / 1000 + " s");
        }
    }

    @Override public void stop() {
        Thread t;
        synchronized (this) {
            if (!running) return;
            running = false;
            t = starter;
            starter = null;
        }
        if (t != null) t.interrupt();
        if (!over) bridge.send("device.stop_livestream", serial);
        hub.stop();
    }

    /** The bridge says the camera ended its stream (it does that on its own after a while). */
    public void deviceEvent(String event) {
        if (running && event.equals("livestream stopped")) {
            over = true;
            listener.onStreamState("stream ended: the camera stopped the livestream");
        }
    }

    /** One livestream chunk from the bridge's session thread. */
    public void media(boolean video, byte[] data) {
        if (!running || data.length == 0) return;
        long micros = (System.nanoTime() - baseNanos) / 1000;
        if (video) {
            boolean keyframe = keyframe(data);
            hub.video(data, micros, keyframe);
            if (!delivered && hub.firstKeyframeMillis() >= 0) {
                delivered = true;
                listener.onStreamState("streaming");
            }
        } else {
            audio(data, micros);
        }
    }

    private void audio(byte[] data, long micros) {
        int at = 0;
        while (at + 7 <= data.length) {
            if ((data[at] & 0xff) != 0xff || (data[at + 1] & 0xf0) != 0xf0) return;  // not ADTS: nothing to play
            boolean crc = (data[at + 1] & 1) == 0;
            int header = crc ? 9 : 7;
            int objectType = ((data[at + 2] >> 6) & 3) + 1;
            int rateIndex = (data[at + 2] >> 2) & 0xf;
            int channels = ((data[at + 2] & 1) << 2) | ((data[at + 3] >> 6) & 3);
            int frame = ((data[at + 3] & 3) << 11) | ((data[at + 4] & 0xff) << 3) | ((data[at + 5] & 0xff) >> 5);
            if (frame < header || at + frame > data.length || rateIndex >= RATES.length) return;
            if (!audioFormatSent) {
                audioFormatSent = true;
                byte[] config = {(byte) ((objectType << 3) | (rateIndex >> 1)), (byte) (((rateIndex & 1) << 7) | (channels << 3))};
                hub.audioFormat(config, RATES[rateIndex]);
            }
            byte[] aac = new byte[frame - header];
            System.arraycopy(data, at + header, aac, 0, aac.length);
            hub.audio(aac, micros);
            at += frame;
        }
    }

    /** An IDR picture (or the parameter sets before one) in an Annex B access unit. */
    public static boolean keyframe(byte[] data) {
        for (int i = 0; i + 3 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && (data[i + 2] == 1 || (data[i + 2] == 0 && data[i + 3] == 1 && i + 4 < data.length))) {
                int nal = data[i + 2] == 1 ? data[i + 3] : data[i + 4];
                int type = nal & 0x1f;
                if (type == 5 || type == 7) return true;
            }
        }
        return false;
    }

    @Override public void attachVideo(VideoSink sink) {
        hub.attachVideo(sink);
    }

    @Override public void attachAudio(AudioSink sink) {
        hub.attachAudio(sink);
    }

    @Override public long firstKeyframeMillis() {
        return hub.firstKeyframeMillis();
    }
}
