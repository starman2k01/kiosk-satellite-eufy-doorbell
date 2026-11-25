// SPDX-License-Identifier: Apache-2.0
package ks.eufydoorbell;

import android.content.Context;
import android.view.Surface;
import android.view.View;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;
import ks.eufydoorbell.RingController.State;
import ks.eufydoorbell.eufy.EufyBridge;
import ks.eufydoorbell.eufy.EufyStream;
import ks.eufydoorbell.media.MediaStream;
import ks.eufydoorbell.talk.TalkChannel;

/**
 * Eufy doorbell for Kiosk Satellite: stays connected to the eufy-security-ws bridge, and when the doorbell rings wakes the
 * panel, rings and opens the full-screen viewer with the chosen camera's live picture. Everything else is in
 * RingController, DoorbellView and the bridge client.
 */
public final class EufyPlugin implements KioskPlugin, RingController.Actions {
    static final String OVERLAY = "doorbell";
    private static final String ENTITY_EVENT = "ks.ha.entity.";

    private volatile PluginHost host;
    private volatile boolean stopped;  // a ring that raced stop() must not start a stream after stop released it
    private volatile Settings settings;
    private volatile Texts texts = Texts.of("Automatic");
    private volatile RingController controller;
    private volatile DoorbellView view;
    private volatile EufyBridge bridge;  // written under this
    private ScheduledExecutorService ticker;
    private volatile WakeWordGuard wakeWord;
    private volatile MusicPause music;
    // The call's media, all guarded by this.
    private MediaStream stream;
    private VideoRenderer videoRenderer;
    private Object videoOwner;  // the view whose surface the decoder draws on
    private Surface videoSurface;  // that surface: released after its decoder, never before
    // Each stream (a call's, or a reconnect's) and each stopMedia count this up, so a reconnect that is due for an
    // older stream, or for a call that has ended, does nothing.
    private int streamGeneration;
    private int reconnects;  // in a row, since a stream last delivered
    private int decoderRestarts;  // in this call
    private int talkGeneration;  // the same for talk: each channel opened, and each one dropped on purpose
    private int talkRetries;  // in a row, since talk last opened
    private int talkLostFor = -1;  // the talk channel already being reopened: a second error from it changes nothing
    private static final int MAX_DECODER_RESTARTS = 2;
    private AudioRenderer audioRenderer;
    private TalkChannel talk;
    // Talking to the door is not available through the bridge yet; kept false so the shared call code stays as it was.
    private volatile boolean talkFromRing;
    private MicCapture mic;
    private CallVolume callVolume;
    // The slider's level: updateSettings stores it for later sessions but does not call configure, so settings keeps
    // the level from the start. Everything reads this instead.
    private volatile int liveCallVolume;
    private CallMode callMode;
    private long ringAt;
    // Push to talk: the call's ducking, and when the door comes back up after the button lets go (guarded by this).
    private boolean talking;
    private ScheduledFuture<?> unduck;
    // Taken when the viewer opens: a Talk mode changed mid-call must not split the view from the microphone.
    private volatile boolean callPushToTalk;
    /** How long the door stays down after push to talk lets go: our own voice comes back from the door's microphone
     * about 1.4 s after we spoke over Baichuan (1 s to the door, 0.4 s back), 2.4 s over ONVIF (2 s to the door), and
     * should not play at full volume. */
    private static final long DUCK_TAIL_MILLIS = 1500, ONVIF_DUCK_TAIL_MILLIS = 2700;
    private volatile long countdownStart, countdownMillis;  // shown by the viewer, also one created later
    // The cards' Home Assistant entities: followed only while the viewer is open.
    private final Set<String> followed = new HashSet<>();  // guarded by itself
    private final Map<String, Map<?, ?>> snapshots = new ConcurrentHashMap<>();
    private volatile boolean viewerOpen;
    // The doorbell's own snapshot (an HA image entity), fetched whenever it changes and shown beside the live picture.
    private volatile android.graphics.Bitmap snapshot;
    private volatile String snapshotCaption = "";
    private volatile long snapshotAt;
    private String snapshotFollowed = "";  // guarded by this
    private String snapshotKey = "";  // guarded by this

    @Override public void start(PluginHost host, Map<String, Object> values) {
        stopped = false;
        this.host = host;
        headless = headless(host);
        Texts.setAssets(host::openAsset);  // the language files under assets/i18n
        settings = new Settings(values);
        this.values = new HashMap<>(values);
        texts = Texts.of(settings.language);
        liveCallVolume = settings.callVolume;
        // Monotonic: a clock set after boot (no RTC battery, NTP) must not end a ring at once or stretch a call.
        controller = new RingController(this, () -> System.nanoTime() / 1_000_000, settings.autoCloseMillis);
        controller.setMaxCallMillis(settings.maxCallMillis);
        wakeWord = new WakeWordGuard(new WakeWordGuard.Host() {
            @Override public void command(String name, Map<String, Object> arguments, WakeWordGuard.Result result) {
                PluginHost h = EufyPlugin.this.host;
                if (h == null) {
                    result.done(false, null);
                    return;
                }
                try {
                    h.executeCommand(name, arguments, (ok, data, error) -> result.done(ok, data));
                } catch (RuntimeException unavailable) {
                    result.done(false, null);  // an older Kiosk Satellite, or the session ended
                }
            }

            @Override public void remember(boolean mutedByUs) {
                PluginHost h = EufyPlugin.this.host;
                if (h == null) return;
                try {
                    h.updateSettings(Collections.<String, Object>singletonMap("wakeWordRestore", mutedByUs));
                    EufyPlugin.this.remember("wakeWordRestore", mutedByUs);
                } catch (RuntimeException | LinkageError older) {
                    // Without it, only a crash mid-call could leave the wake word muted.
                }
            }
        });
        wakeWord.restore(settings.wakeWordRestore);
        music = new MusicPause(new MusicPause.Host() {
            @Override public void command(String name, Map<String, Object> arguments, MusicPause.Result result) {
                PluginHost h = EufyPlugin.this.host;
                if (h == null) {
                    result.done(false, null);
                    return;
                }
                try {
                    h.executeCommand(name, arguments, (ok, data, error) -> result.done(ok, data));
                } catch (RuntimeException unavailable) {
                    result.done(false, null);  // an older Kiosk Satellite, or the session ended
                }
            }

            @Override public void remember(boolean pausedByUs) {
                PluginHost h = EufyPlugin.this.host;
                if (h == null) return;
                try {
                    h.updateSettings(Collections.<String, Object>singletonMap("musicRestore", pausedByUs));
                    EufyPlugin.this.remember("musicRestore", pausedByUs);
                } catch (RuntimeException | LinkageError older) {
                    // Without it, only a stop mid-call could leave the music paused.
                }
            }
        });
        music.restore(settings.musicRestore);
        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "reolink-ticker");
            thread.setDaemon(true);
            return thread;
        });
        ticker.scheduleWithFixedDelay(() -> {
            RingController c = controller;
            if (c != null) c.tick();
        }, 1, 1, TimeUnit.SECONDS);
        publishText("last_ring", texts.lastRingReading(), texts.noneYet());
        publishControls();
        screenRestore = new ScreenRestore();
        if (!headless) followScreen(host);
        connect();
        followSnapshot();
    }

    /** The screen and screensaver states, for Restore the screen: events first, then the reads for where they start. */
    private void followScreen(PluginHost h) {
        try {
            h.subscribe("screen.state");
            h.subscribe("screensaver.state");
            ScreenRestore r = screenRestore;
            h.executeCommand("isScreenOn", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
                if (ok && data instanceof Boolean) r.readScreen((Boolean) data);
            });
            h.executeCommand("isScreensaverActive", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
                if (ok && data instanceof Boolean) r.readScreensaver((Boolean) data);
            });
        } catch (RuntimeException unavailable) {
            log("screen: its state is unknown, so the screen stays on after a call: " + unavailable.getMessage());
        }
    }

    @Override public void configure(Map<String, Object> values) {
        synchronized (valuesLock) {
            this.values = new HashMap<>(values);
        }
        apply(values);
    }

    /** A settings change: from the settings page (configure), or from Home Assistant (control, values kept as remembered). */
    private void apply(Map<String, Object> values) {
        Settings previous = settings;
        settings = new Settings(values);
        texts = Texts.of(settings.language);
        liveCallVolume = settings.callVolume;
        RingController c = controller;
        if (c != null) {
            c.setAutoCloseMillis(settings.autoCloseMillis);
            c.setMaxCallMillis(settings.maxCallMillis);
        }
        publishControls();
        if (previous == null || !previous.sameConnection(settings)) connect();
        followSnapshot();
        if (viewerOpen) {
            followCards(settings.cards);
            DoorbellView v = view;
            Settings now = settings;
            if (v != null) v.post(() -> {
                v.setColumnPosition(now.columnPosition);
                v.setCallButtons(now.callButtons);
                v.setCards(now.cards);
            });
        }
    }

    @Override public void execute(String command, Map<String, Object> arguments) {
        RingController c = controller;
        if (c == null) return;
        if (headless) {
            if (headlessChimes(command)) headlessPress("Test ring", true);
            return;
        }
        switch (command) {
            case "show": c.open(); break;
            case "testRing": c.onVisitor(false); c.onVisitor(true); break;
            case "dismiss": c.hangUp(); break;
            default: throw new IllegalArgumentException("Unknown command " + command);
        }
    }

    @Override public void onEvent(String event, Map<String, Object> payload) {
        if ("settings.button".equals(event) && "check".equals(payload.get("id"))) {
            check(String.valueOf(payload.get("run")));
            return;
        }
        if ("settings.button".equals(event) && "testRing".equals(payload.get("id"))) {
            PluginHost h = host;
            try {
                execute("testRing", Collections.<String, Object>emptyMap());
                if (h != null) h.setupResult(String.valueOf(payload.get("run")), "success", "✓ Rang the panel as if the doorbell was pressed.");
            } catch (RuntimeException e) {
                if (h != null) h.setupResult(String.valueOf(payload.get("run")), "error", "✗ " + e.getMessage());
            }
            return;
        }
        if (event.startsWith(ENTITY_EVENT)) {
            String entityId = event.substring(ENTITY_EVENT.length());
            if (!entityId.isEmpty() && entityId.equals(settings.snapshotEntity)) snapshotChanged(payload);
            synchronized (followed) {
                if (!followed.contains(entityId)) return;  // late, for a card no longer shown: it would show next call
                snapshots.put(entityId, payload);
            }
            DoorbellView v = view;
            if (v != null) v.post(() -> v.entityChanged(entityId, payload));
            return;
        }
        if (event.startsWith("switch.") || event.startsWith("number.")) {
            control(event, payload);
            return;
        }
        if ("ks.screen.state".equals(event)) {
            screenRestore.screen(Boolean.TRUE.equals(payload.get("on")));
            return;
        }
        if ("ks.screensaver.state".equals(event)) {
            screenRestore.screensaver(Boolean.TRUE.equals(payload.get("active")));
            return;
        }
        if ("overlay.closed".equals(event) && OVERLAY.equals(payload.get("key"))) {
            RingController c = controller;
            if (c != null) c.hangUp();  // Back closed the viewer
        }
    }

    @Override public void stop() {
        // The host is revoked before stop: release resources without calling it.
        stopped = true;
        host = null;
        EufyBridge b = bridge;
        bridge = null;
        if (b != null) b.stop();
        ScheduledExecutorService t = ticker;
        ticker = null;
        if (t != null) t.shutdownNow();
        controller = null;
        view = null;
        viewerOpen = false;
        synchronized (followed) {
            followed.clear();  // the session's subscriptions end with it
        }
        snapshots.clear();
        stopMedia();
    }

    private synchronized void connect() {
        EufyBridge previous = bridge;
        bridge = null;
        if (previous != null) previous.stop();
        Settings s = settings;
        if (!s.configured()) {
            status(texts.enterSettings(), true);
            publishText("connection", texts.connectionReading(), texts.notConfigured());
            return;
        }
        EufyBridge[] self = new EufyBridge[1];  // a replaced session can take a moment to stop: ignore what it still says
        EufyBridge session = new EufyBridge(s.bridgeHost, s.bridgePort, new EufyBridge.Listener() {
            private String logged = "";  // each change once: a bridge that is away fails every few seconds

            @Override public void onConnection(boolean connected, String detail) {
                if (bridge != self[0]) return;
                Texts t = texts;
                boolean connecting = "connecting".equals(detail);
                if (connecting && !logged.isEmpty() && !logged.equals("connected")) return;
                if (!connecting && !detail.equals(logged)) log("events: " + detail);
                if (!connecting) logged = detail;
                String shown = connected ? t.connected() : connecting ? t.connectingState() : detail;
                publishText("connection", t.connectionReading(), shown);
                status(connected ? t.connectedTo(s.name(t)) : t.doorbellProblem(shown), !connected && !connecting);
            }

            @Override public void onRing(String serial, boolean ringing) {
                RingController c = controller;
                if (c == null || bridge != self[0] || !serial.equals(settings.ringSerial)) return;
                if (ringing) publishText("last_ring", texts.lastRingReading(), new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date()));
                visitor(c, ringing);
            }

            @Override public void onDeviceEvent(String serial, String event) {
                MediaStream current = stream;
                if (current instanceof EufyStream && ((EufyStream) current).serial().equals(serial)) ((EufyStream) current).deviceEvent(event);
            }

            @Override public void onMedia(String serial, boolean video, byte[] data, int fps, int width, int height) {
                MediaStream current = stream;
                if (current instanceof EufyStream && ((EufyStream) current).serial().equals(serial)) ((EufyStream) current).media(video, data);
            }

            @Override public void onPicture(String serial, byte[] image) {
                if (bridge != self[0] || !serial.equals(settings.ringSerial)) return;
                log("snapshot: the doorbell sent a picture, " + image.length + " bytes");
                showSnapshot(image, System.currentTimeMillis(), new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()));
            }
        });
        self[0] = session;
        bridge = session;
        session.start();
    }

    // Guarded by its own lock, never the plugin's: the controller calls back into that one while holding its own.
    private final PressGate pressGate = new PressGate();
    private volatile ScreenRestore screenRestore = new ScreenRestore();  // new each session: events are not replayed
    // A device without a screen, such as an Echo Dot (#20): a press only plays the chime there, never the call screen.
    private volatile boolean headless;

    private static boolean headless(PluginHost host) {
        try {
            return host.isHeadless();
        } catch (RuntimeException | LinkageError older) {
            return false;  // a Kiosk Satellite from before devices without a screen
        }
    }

    /**
     * A press on a device without a screen: the chime, as Play a chime says, and the trigger; Ring on this device off:
     * only the trigger. Test ring chimes even then, as it rings a panel whatever Ring on this device says, so an
     * automation can ring the device itself (#21).
     */
    private void headlessPress(String why, boolean test) {
        Settings s = settings;
        boolean chime = headlessChime(s, test);
        if (chime) Chime.play(s.chimeVolume, s.chimeStream, this::log);
        log("ring: " + why + (chime ? ", chime only: this device has no screen" : !s.chime ? ", no chime (Play a chime off), no screen"
            : ": off on this device (Ring on this device)"));
        fireTrigger("doorbellPressed");
    }

    /** Whether a press, or Test ring, plays the chime on a device without a screen. */
    static boolean headlessChime(Settings s, boolean test) {
        return s.chime && (s.ring || test);
    }

    /** Whether the doorbell's presses go to the call screen here; without a screen, every press goes the silent way. */
    static boolean ringsHere(Settings s, boolean headless) {
        return s.ring && !headless;
    }

    /** A command on a device without a screen: Test ring chimes; Open and Close have no call screen to act on. */
    static boolean headlessChimes(String command) {
        if (command.equals("testRing")) return true;
        if (command.equals("show") || command.equals("dismiss")) return false;
        throw new IllegalArgumentException("Unknown command " + command);
    }

    /** Whether a Home Assistant control is published here: a device without a screen has none to wake. */
    static boolean publishes(String key, boolean headless) {
        return !(headless && key.equals("wake_screen"));
    }

    /**
     * The doorbell's visitor state, from Baichuan or ONVIF. Ring on this device (#19) off: no chime, screen or call
     * screen on this panel, only Last ring (published by the caller) and the Doorbell pressed trigger, once a press.
     * The controller then does not see the press at all, so it neither rings nor counts a missed ring.
     */
    private void visitor(RingController c, boolean visitor) {
        PressGate.Result result;
        synchronized (pressGate) {
            result = pressGate.report(visitor, ringsHere(settings, headless), System.nanoTime() / 1_000_000);
        }
        if (result == PressGate.Result.PASS) {
            c.onVisitor(visitor);
        } else if (result == PressGate.Result.SILENT_PRESS && headless) {
            headlessPress("a press", false);
        } else if (result == PressGate.Result.SILENT_PRESS) {
            log("ring: off on this device (Ring on this device), so only Last ring and Doorbell pressed");
            fireTrigger("doorbellPressed");
        }
    }

    // RingController.Actions

    @Override public void openViewer(State state, boolean rang) {
        PluginHost h = host;
        if (h == null || headless) return;  // no screen to show it on
        if (rang && settings.wakeScreen) screenRestore.ring();  // before the wake below changes it
        else screenRestore.forget();  // this call did not wake the screen: nothing to put back
        if (settings.wakeScreen) {
            command(h, "stopScreensaver");
            command(h, "screenOn");
        }
        final Texts t = texts;
        final String name = settings.name(t);
        log("viewer: open (" + (rang ? "ring" : "by hand") + ")");
        stopMedia();  // a fresh call: nothing from an earlier one may linger
        callPushToTalk = settings.pushToTalk;
        startStream();
        viewerOpen = true;
        fireTrigger("callScreenOpened");
        followCards(settings.cards);
        h.showOverlay(OVERLAY, OverlaySpec.fullScreen().onTop(true).closeOnBack(true), new OverlayFactory() {
            @Override public View create(Context context, KsTheme theme) {
                RingController c = controller;
                State current = c == null ? state : c.state();
                synchronized (EufyPlugin.this) {
                    if (callVolume == null) callVolume = new CallVolume(context);
                    if (callMode == null) callMode = new CallMode(context);
                }
                DoorbellView created = new DoorbellView(context, theme, t, name, current, liveCallVolume, new DoorbellView.Callback() {
                    @Override public void hangUp() { RingController c = controller; if (c != null) c.hangUp(); }
                    @Override public void speaker() { RingController c = controller; if (c != null) c.speaker(); }
                    @Override public void answer() { RingController c = controller; if (c != null) c.answer(); }
                    @Override public void toggleSpeaker() { RingController c = controller; if (c != null) c.toggleSpeaker(); }
                    @Override public void toggleMic() { RingController c = controller; if (c != null) c.toggleMic(); }
                    @Override public void touched() { RingController c = controller; if (c != null) c.touched(); }
                    @Override public void surfaceReady(Surface surface) { attachVideo(this, surface); }
                    @Override public void surfaceGone() { detachVideo(this); }
                    @Override public void volumeChanged(int percent, boolean done) { setCallVolume(percent, done); }
                    @Override public void cardPressed(Cards.Card card) { runCard(card); }
                    @Override public void holdToTalk(boolean pressed) { EufyPlugin.this.holdToTalk(pressed); }
                });
                view = created;  // first, so a state arriving during the replay below reaches this view, not the old one
                for (Map.Entry<String, Map<?, ?>> known : snapshots.entrySet()) created.entityChanged(known.getKey(), known.getValue());
                created.setColumnPosition(settings.columnPosition);
                created.setCallButtons(settings.callButtons);
                created.setCards(settings.cards);
                created.setPushToTalk(callPushToTalk);
                if (c != null) created.update(c.state(), c.speakerOn(), c.micOn());
                created.setCountdown(countdownStart, countdownMillis);
                android.graphics.Bitmap known = snapshot;
                if (known != null && System.currentTimeMillis() - snapshotAt < settings.snapshotMaxAgeMillis) created.setSnapshot(known, snapshotCaption);
                return created;
            }

            @Override public void onThemeChanged(View changed, KsTheme theme) {
                if (changed instanceof DoorbellView) ((DoorbellView) changed).applyTheme(theme);
            }

            @Override public void onDestroy(View destroyed) {
                if (view == destroyed) view = null;
            }
        });
    }

    @Override public void updateViewer(State state, boolean speakerOn, boolean micOn) {
        DoorbellView v = view;
        if (v != null) v.post(() -> v.update(state, speakerOn, micOn));
    }

    @Override public void countdown(long startedAt, long durationMillis) {
        countdownStart = startedAt;
        countdownMillis = durationMillis;
        DoorbellView v = view;
        if (v != null) v.post(() -> v.setCountdown(startedAt, durationMillis));
    }

    @Override public void closeViewer() {
        log("viewer: close");
        MusicPause m = music;
        if (m != null) m.setActive(false);
        PluginHost h = host;
        if (h != null) h.hideOverlay(OVERLAY);
        viewerOpen = false;
        ScreenRestore r = screenRestore;
        ScreenRestore.Before before = r.before();
        String restore = r.close(settings.restoreScreen);
        if (before != null) log("screen: before the ring " + before.name().toLowerCase(Locale.ROOT) + ", " + (restore == null ? "left as it is (Restore the screen: " + settings.restoreScreen + ")" : restore));
        if (h != null && restore != null) command(h, restore);
        fireTrigger("callScreenClosed");
        followCards(Collections.<Cards.Card>emptyList());
        stopMedia();
    }

    // The cards

    /** Follows exactly the cards' entities; KS sends each one's current state first, then every change. */
    private void followCards(List<Cards.Card> cards) {
        PluginHost h = host;
        Set<String> wanted = new HashSet<>();
        for (Cards.Card card : cards) if (card.hasEntity()) wanted.add(card.entityId);
        synchronized (followed) {
            for (String entityId : new HashSet<>(followed)) {
                if (wanted.contains(entityId)) continue;
                followed.remove(entityId);
                snapshots.remove(entityId);
                try {
                    if (h != null) h.unsubscribe("ha.entity." + entityId);
                } catch (RuntimeException gone) {
                    // The session ended; its subscriptions went with it.
                }
            }
            for (String entityId : wanted) {
                if (followed.contains(entityId) || h == null) continue;
                try {
                    h.subscribe("ha.entity." + entityId);
                    followed.add(entityId);
                } catch (RuntimeException refused) {
                    log("card: cannot follow " + entityId + ": " + refused.getMessage());
                }
            }
        }
    }

    /** Runs the action the user chose for the card, and shows on the card how it went. */
    private void runCard(Cards.Card card) {
        PluginHost h = host;
        DoorbellView v = view;
        if (h == null || !card.action) return;
        Texts t = texts;
        if (v != null) v.cardFeedback(card, DoorbellView.RUNNING);
        try {
            h.runAction(card.actionPath(), (ok, data, error) -> {
                log("card: " + (card.label.isEmpty() ? card.actionPath() : card.label) + (ok ? " ran" : " failed: " + error));
                DoorbellView now = view;
                // With a state to follow, the card keeps showing it is running until the new state arrives.
                if (ok && card.hasEntity()) return;
                String shown = ok ? t.cardDone() : t.cardFailed();
                if (now != null) now.post(() -> now.cardFeedback(card, shown));
            });
        } catch (UnsupportedOperationException older) {
            log("card: this Kiosk Satellite version cannot run actions");
            if (v != null) v.cardFeedback(card, t.cardsNeedNewerKs());
        } catch (RuntimeException refused) {
            log("card: " + refused.getMessage());
            if (v != null) v.cardFeedback(card, t.cardFailed());
        }
    }

    @Override public synchronized void setAudio(boolean speakerOn, boolean micOn) {
        if (speakerOn || micOn) screenRestore.answered();  // Speaker or Answer: for Restore the screen's When not answered
        WakeWordGuard guard = wakeWord;
        if (guard != null) guard.setActive(speakerOn || micOn);
        MusicPause m = music;
        if (m != null && (speakerOn || micOn) && !settings.musicPause.equals("Off")) m.setActive(true);
        log("audio: speaker " + speakerOn + ", microphone " + micOn + (stream == null ? ", no stream" : ""));
        // Before the track and the recorder open, so both start in call mode; left again only when the call ends.
        if ((speakerOn || micOn) && settings.callMode && callMode != null) {
            String entered = callMode.enter();
            if (entered != null) log("audio: " + entered);
        }
        if (speakerOn && audioRenderer == null && stream != null) {
            audioRenderer = new AudioRenderer(message -> {
                showNotice(texts.doorbellSoundStopped());
                log(message);
            });
            applyCallVolume(liveCallVolume, true);
            if (talking) audioRenderer.setDuck(Loudness.DUCK);
            stream.attachAudio(audioRenderer);
        } else if (!speakerOn && audioRenderer != null) {
            if (stream != null) stream.attachAudio(null);
            releaseLater(audioRenderer::release);
            audioRenderer = null;
        }
        if (micOn && mic == null && stream != null) {
            if (talk == null) {
                talk = openTalk(settings);
                talk.start();
            }
            if (callPushToTalk) log("microphone: push to talk");
            mic = openMic();
        } else if (!micOn && mic != null) {
            releaseLater(mic::release);
            mic = null;
            if (talk != null && talkFromRing) {
                talk.clear();
            } else if (talk != null) {
                // Gives the doorbell's speaker back (TalkReset), for its own sounds and a Quick Reply.
                logTalk();
                releaseLater(talk::stop);
                talk = null;
                talkGeneration++;  // dropped on purpose: nothing to reopen
            }
        }
    }

    /** The microphone, into the current talk channel; muted in push to talk until the button is held. */
    private MicCapture openMic() {
        return new MicCapture(talk, settings.microphone, settings.micRate, callPushToTalk && !talking, state -> {
            log("microphone: " + state);
            boolean live = state.startsWith("live");
            voiceGoesOut(live);
            if (!live) showNotice(texts.noMicrophone());
        });
    }

    /** The settings page's Check button: asks the bridge for the camera's picture, off the plugin worker, and reports through the setup run. */
    private void check(String run) {
        PluginHost h = host;
        if (h == null) return;
        Settings s = settings;
        Thread thread = new Thread(() -> {
            try {
                h.setupResult(run, "running", "Connecting to the bridge...");
                String result = checkBridge(s);
                h.setupResult(run, result.startsWith("OK") ? "success" : "error", (result.startsWith("OK") ? "✓ " : "✗ ") + result);
            } catch (RuntimeException | LinkageError gone) {
                // The run or the session ended meanwhile.
            }
        }, "eufy-check");
        thread.setDaemon(true);
        thread.start();
    }

    /** Connects to the bridge, starts the camera's livestream and waits for a picture: a sentence that starts with OK, or says what failed. */
    private String checkBridge(Settings s) {
        if (!s.configured()) return "Enter the bridge address, the doorbell's serial number and the camera's serial number first.";
        final java.util.concurrent.CountDownLatch connectedLatch = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch pictureLatch = new java.util.concurrent.CountDownLatch(1);
        final int[] video = new int[2];  // chunks, keyframes
        final boolean[] audio = new boolean[1];
        final EufyBridge[] self = new EufyBridge[1];
        final String[] problem = new String[1];
        EufyBridge probe = new EufyBridge(s.bridgeHost, s.bridgePort, new EufyBridge.Listener() {
            @Override public void onConnection(boolean connected, String detail) {
                if (connected) connectedLatch.countDown();
                else if (!"connecting".equals(detail)) problem[0] = detail;
            }
            @Override public void onRing(String serial, boolean ringing) {}
            @Override public void onDeviceEvent(String serial, String event) {}
            @Override public void onMedia(String serial, boolean isVideo, byte[] data, int fps, int width, int height) {
                if (!serial.equals(s.cameraSerial)) return;
                if (isVideo) {
                    video[0]++;
                    if (EufyStream.keyframe(data)) {
                        video[1]++;
                        pictureLatch.countDown();
                    }
                } else {
                    audio[0] = true;
                }
            }
        });
        self[0] = probe;
        probe.start();
        try {
            if (!connectedLatch.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                return "Cannot reach the bridge at " + s.bridgeHost + ":" + s.bridgePort + (problem[0] == null ? "" : " (" + problem[0] + ")")
                    + ". It is the eufy-security-ws add-on's port, 3002 by default.";
            }
            if (probe.send("device.start_livestream", s.cameraSerial) == null) return "The bridge dropped the connection.";
            boolean got = pictureLatch.await(15, java.util.concurrent.TimeUnit.SECONDS);
            if (!got) {
                return "Connected to the bridge, but the camera " + s.cameraSerial + " sent no picture in 15 s. Check the serial number, and that the "
                    + "camera is shared with the Eufy account the bridge uses (see the add-on's log).";
            }
            return "OK: the bridge is reachable and the camera's picture arrives" + (audio[0] ? " with sound" : " (no sound heard yet)") + ".";
        } catch (InterruptedException stopped) {
            return "The check was interrupted.";
        } finally {
            probe.send("device.stop_livestream", s.cameraSerial);
            probe.stop();
        }
    }

    private static void releaseLater(Runnable release) {
        Thread thread = new Thread(release, "reolink-cleanup");
        thread.setDaemon(true);
        thread.start();
    }

    // The call's media

    /** Starts the camera's livestream at the ring, before the viewer exists, so the first picture is ready sooner. */
    private synchronized void startStream() {
        Settings s = settings;
        if (stream != null || !s.configured() || stopped || bridge == null) return;
        ringAt = System.nanoTime() / 1_000_000;
        log("media: start livestream of " + s.cameraSerial + " through the bridge");
        publishText("first_frame", texts.firstPictureReading(), texts.waiting());
        reconnects = 0;
        decoderRestarts = 0;
        stream = openStream(s, ++streamGeneration);
        stream.start();
    }

    /** The camera's livestream through the bridge. */
    private MediaStream openStream(Settings s, int generation) {
        return new EufyStream(bridge, s.cameraSerial, state -> {
            if (!state.equals("connecting") && !state.equals("streaming")) log("video: " + state);
            if (state.equals("streaming")) streamDelivers(generation);
            if (state.startsWith("stream failed") || state.startsWith("stream ended")) {
                showMessage(videoProblem(state));
                streamLost(generation);
            }
        });
    }

    private synchronized void streamDelivers(int generation) {
        if (generation == streamGeneration) reconnects = 0;
    }

    /**
     * The call's stream ended or failed (the doorbell rebooted, a network hiccup): while the viewer is open, open it
     * again, sooner the first time, at most every 5 s. The viewer's decoder and sound carry on with the new stream, and
     * newestIFrame brings a fresh picture about 0.3 s after it connects.
     */
    private synchronized void streamLost(int generation) {
        if (generation != streamGeneration || stopped || !viewerOpen) return;
        ScheduledExecutorService t = ticker;
        if (t == null) return;
        long delay = Math.min(5_000, 1_000L << Math.min(reconnects, 3));
        reconnects++;
        log("media: reopening the stream in " + delay / 1000 + " s");
        t.schedule(() -> reopenStream(generation), delay, TimeUnit.MILLISECONDS);
    }

    private synchronized void reopenStream(int generation) {
        if (generation != streamGeneration || stopped || !viewerOpen || stream == null) return;
        final MediaStream old = stream;
        stream = openStream(settings, ++streamGeneration);
        if (videoRenderer != null) stream.attachVideo(videoRenderer);
        if (audioRenderer != null) stream.attachAudio(audioRenderer);
        stream.start();
        releaseLater(old::stop);  // it may still say goodbye to the doorbell: off this thread
    }

    /** What the viewer says about a stream state; the protocol's own words only go to the log. */
    private String videoProblem(String state) {
        if (state.contains("H.265")) return texts.videoH265();
        return state.startsWith("stream ended") ? texts.videoStopped() : texts.noVideo();
    }

    /**
     * Talking to the door is not available through the Eufy bridge yet: the channel opens, says so, and drops what the
     * microphone sends. Listening to the camera works.
     */
    private TalkChannel openTalk(Settings s) {
        ++talkGeneration;
        log("talk: not available through the Eufy bridge yet");
        return new TalkChannel() {
            @Override public void start() {
                voiceGoesOut(false);
                showNotice(texts.voiceNotThrough());
            }
            @Override public void stop() {}
            @Override public void write(short[] samples, int count) {}
            @Override public void clear() {}
            @Override public int rate() { return 16000; }
        };
    }

    private synchronized void talkOpened(int generation) {
        if (generation == talkGeneration) talkRetries = 0;
        voiceGoesOut(true);
    }

    /**
     * Talk failed or dropped while it is still wanted (the microphone is on, or ONVIF talk that stays open for the
     * call): say so, and open it again the same way as the stream, with the microphone moved over to the new channel.
     */
    private synchronized void talkLost(int generation) {
        if (generation == talkLostFor) return;
        talkLostFor = generation;
        voiceGoesOut(false);
        showNotice(texts.voiceNotThrough());
        if (generation != talkGeneration || stopped || !viewerOpen || talk == null || (mic == null && !talkFromRing)) return;
        ScheduledExecutorService t = ticker;
        if (t == null) return;
        long delay = Math.min(5_000, 1_000L << Math.min(talkRetries, 3));
        talkRetries++;
        log("talk: reopening in " + delay / 1000 + " s");
        t.schedule(() -> reopenTalk(generation), delay, TimeUnit.MILLISECONDS);
    }

    private synchronized void reopenTalk(int generation) {
        if (generation != talkGeneration || stopped || !viewerOpen || talk == null || (mic == null && !talkFromRing)) return;
        final TalkChannel old = talk;
        logTalk();
        talk = openTalk(settings);
        talk.start();
        releaseLater(old::stop);
        if (mic != null) {
            final MicCapture oldMic = mic;
            releaseLater(oldMic::release);
            mic = openMic();
        }
    }

    /**
     * KS may build a new viewer before it removes the old one, so each viewer only ever detaches its own decoder:
     * owner is the viewer's callback.
     */
    private synchronized void attachVideo(Object owner, Surface surface) {
        log("viewer: surface ready" + (stream == null ? ", no stream" : ""));
        // The old viewer's decoder: released off this (main) thread, which waiting for it would hold up, together
        // with the new viewer's first picture and every tap. Its surface lives until its own viewer goes.
        final VideoRenderer old = videoRenderer;
        final Surface oldSurface = videoSurface;
        videoOwner = owner;
        videoSurface = surface;
        videoRenderer = newRenderer(surface, false);
        if (stream != null) stream.attachVideo(videoRenderer);
        if (old != null || oldSurface != null) releaseLater(() -> release(old, oldSurface));
    }

    /** A decoder for the viewer's surface; a replacement for one that failed does not report a first picture again. */
    private VideoRenderer newRenderer(Surface surface, boolean replacement) {
        final VideoRenderer[] self = new VideoRenderer[1];
        self[0] = new VideoRenderer(surface, new VideoRenderer.Listener() {
            @Override public void onVideoSize(int width, int height) {
                DoorbellView v = view;
                if (v != null) v.post(() -> v.setVideoSize(width, height));
            }

            @Override public void onVideoStats(long firstFrameMillis, double decodeMillis) {
                if (firstFrameMillis >= 0 && !replacement) {
                    long sinceRing = System.nanoTime() / 1_000_000 - ringAt;
                    publishText("first_frame", texts.firstPictureReading(), texts.afterRing(sinceRing));
                }
            }

            @Override public void onVideoError(String message) {
                // null: no keyframe yet. Otherwise the decoder failed, which the log explains.
                showMessage(message == null ? texts.noPictureYet() : texts.videoNotShown());
                log(message == null ? texts.noPictureYet() : message);
                if (message != null) decoderFailed(self[0]);
            }

            @Override public void onVideoLog(String message) {
                log(message);
            }
        });
        return self[0];
    }

    /**
     * The decoder stopped with an error mid-call (a transient codec fault, a stream glitch it could not take): a new
     * one on the same surface, from the stream's last keyframe, at most twice a call. The new one can only connect to
     * the surface once the failed one has let go of it, so it is made after that release, off this thread.
     */
    private synchronized void decoderFailed(VideoRenderer failed) {
        if (failed == null || failed != videoRenderer || videoSurface == null || decoderRestarts >= MAX_DECODER_RESTARTS) return;
        decoderRestarts++;
        final Surface surface = videoSurface;
        log("video decoder: starting a new one");
        releaseLater(() -> {
            failed.release();
            synchronized (EufyPlugin.this) {
                if (videoRenderer != failed || videoSurface != surface) return;  // the viewer or the call went meanwhile
                videoRenderer = newRenderer(surface, true);
                if (stream != null) stream.attachVideo(videoRenderer);
            }
        });
    }

    /** A decoder, then the surface it drew on: the other way round the decoder writes into a surface that is gone. */
    private static void release(VideoRenderer renderer, Surface surface) {
        if (renderer != null) renderer.release();
        if (surface != null) surface.release();
    }

    private synchronized void detachVideo(Object owner) {
        log("viewer: surface gone" + (owner != videoOwner ? " (an older viewer)" : ""));
        if (owner != videoOwner) return;  // an old viewer going away after a new one took over
        videoOwner = null;
        if (stream != null) stream.attachVideo(null);
        release(videoRenderer, videoSurface);  // now: the surface's texture is freed when this callback returns
        videoRenderer = null;
        videoSurface = null;
    }

    /**
     * Forgets the call's media at once and releases it on a background thread: Hang up runs on the main thread, where
     * Android refuses network traffic (the RTSP TEARDOWN) and where waiting for a decoder to stop would stall the UI.
     */
    private synchronized void stopMedia() {
        streamGeneration++;  // a reconnect still due belongs to this call: it must not reopen anything
        talkGeneration++;
        talking = false;
        if (unduck != null) unduck.cancel(false);
        unduck = null;
        if (stream == null && videoRenderer == null && audioRenderer == null && talk == null && mic == null) {
            if (callVolume != null) callVolume.restore();
            if (callMode != null) callMode.leave();
            return;
        }
        log("media: stop (stream " + (stream != null) + ", video " + (videoRenderer != null) + ", audio " + (audioRenderer != null)
            + ", talk " + (talk != null) + ", microphone " + (mic != null) + ")");
        logTalk();
        final MediaStream oldStream = stream;
        final VideoRenderer oldVideo = videoRenderer;
        final Surface oldSurface = videoSurface;
        final AudioRenderer oldAudio = audioRenderer;
        final TalkChannel oldTalk = talk;
        final MicCapture oldMic = mic;
        talk = null;
        mic = null;
        stream = null;
        videoRenderer = null;
        videoSurface = null;
        videoOwner = null;
        audioRenderer = null;
        if (callVolume != null) callVolume.restore();
        if (callMode != null) callMode.leave();  // here, not on the cleanup thread, so it cannot undo a next call's
        Thread cleanup = new Thread(() -> {
            // The decoder first: it needs no network, and a quick next ring may need it back (some panels have few).
            release(oldVideo, oldSurface);
            try {
                if (oldStream != null) oldStream.stop();
            } catch (RuntimeException ignored) {
                // Going away anyway.
            }
            if (oldMic != null) oldMic.release();
            if (oldTalk != null) oldTalk.stop();
            if (oldAudio != null) oldAudio.release();
        }, "reolink-cleanup");
        cleanup.setDaemon(true);
        cleanup.start();
    }

    private void logTalk() {
        // Nothing to report: there is no talk encoder to measure.
    }

    private void setCallVolume(int percent, boolean done) {
        liveCallVolume = percent;
        applyCallVolume(percent, done);
        if (!done) return;
        PluginHost h = host;
        if (h == null) return;
        try {
            h.updateSettings(Collections.<String, Object>singletonMap("callVolume", (double) percent));
            remember("callVolume", (double) percent);
        } catch (RuntimeException | LinkageError older) {
            // Older Kiosk Satellite: the level holds for this call only.
        }
    }

    /**
     * Android's call volume where it can be changed; where it cannot (a fixed-volume device, such as the Echo Show
     * image, or one that keeps its own level), the door's own playback is turned down instead.
     */
    private synchronized void applyCallVolume(int percent, boolean report) {
        CallVolume volume = callVolume;
        String refused = volume == null ? "unavailable" : volume.apply(percent);
        if (audioRenderer != null) audioRenderer.setGain(refused == null ? 1f : Loudness.remaining(percent, volume == null ? 100 : volume.reachedPercent()));
        if (report) {
            log(refused == null ? "audio: call volume " + percent + "%"
                : "audio: call volume " + percent + "%, played by the plugin: Android's call volume " + refused);
        }
    }

    /**
     * Push to talk: the door hears you only while the button is held, and you hear the door turned down meanwhile.
     * Held while it rings, it answers first; held in a viewer opened by hand, it turns the microphone on first. That
     * runs outside this lock: the controller calls back into it.
     */
    private void holdToTalk(boolean pressed) {
        RingController c = controller;
        if (pressed && c != null && !c.micOn()) {
            synchronized (this) {
                talking = true;  // so the microphone that opens starts unmuted, and the door ducked
            }
            c.talk();
        }
        pushToTalk(pressed);
    }

    private synchronized void pushToTalk(boolean pressed) {
        talking = pressed;
        if (mic != null) mic.setMuted(!pressed);
        if (unduck != null) unduck.cancel(false);
        unduck = null;
        if (pressed) {
            if (audioRenderer != null) audioRenderer.setDuck(Loudness.DUCK);
            return;
        }
        ScheduledExecutorService t = ticker;
        if (t == null) return;
        unduck = t.schedule(() -> {
            synchronized (EufyPlugin.this) {
                if (!talking && audioRenderer != null) audioRenderer.setDuck(1f);
            }
        }, talkOverOnvif() ? ONVIF_DUCK_TAIL_MILLIS : DUCK_TAIL_MILLIS, TimeUnit.MILLISECONDS);
    }

    /** Whether the voice goes over the ONVIF backchannel, by setting or because Automatic fell back. */
    private synchronized boolean talkOverOnvif() {
        return false;  // talking to the door goes over no ONVIF backchannel here
    }

    /** Why there is no picture, or why it stopped: over the picture area. */
    private void showMessage(String message) {
        DoorbellView v = view;
        if (v != null) v.post(() -> v.setMessage(message));
    }

    private void voiceGoesOut(boolean works) {
        DoorbellView v = view;
        if (v != null) v.post(() -> v.setVoiceGoesOut(works));
    }

    /** A problem with sound or talking: a short notice that leaves the picture free. */
    private void showNotice(String message) {
        DoorbellView v = view;
        if (v != null) v.post(() -> v.showNotice(message));
    }

    @Override public void ring() {
        MusicPause m = music;
        if (m != null && settings.musicPause.equals("At the ring")) m.setActive(true);
        if (settings.chime) Chime.play(settings.chimeVolume, settings.chimeStream, this::log);
    }

    @Override public void ringTrigger() {
        fireTrigger("doorbellPressed");
    }

    @Override public void answeredTrigger() {
        fireTrigger("answered");
    }

    @Override public void missedTrigger() {
        fireTrigger("missedRing");
    }

    /** Runs the gestures mapped to a trigger of the manifest. */
    private void fireTrigger(String id) {
        PluginHost h = host;
        if (h == null) return;
        try {
            h.fireTrigger(id);
        } catch (RuntimeException | LinkageError older) {
            // Gesture triggers need a newer Kiosk Satellite; the call itself still works.
        }
    }

    private static void command(PluginHost h, String name) {
        try {
            h.executeCommand(name, Collections.<String, Object>emptyMap(), (ok, data, error) -> {});
        } catch (RuntimeException ignored) {
            // A screen that cannot be woken still shows the viewer once it is on.
        }
    }

    // The settings as Kiosk Satellite last handed them over, with what the plugin saved itself since: a control from
    // Home Assistant changes one and applies them all, so they must hold the call volume the slider saved, too.
    private volatile Map<String, Object> values = new HashMap<>();
    private final Object valuesLock = new Object();

    /** A setting the plugin saved itself (updateSettings does not call configure): in values, and the new values. */
    private Map<String, Object> remember(String key, Object value) {
        synchronized (valuesLock) {
            Map<String, Object> changed = new HashMap<>(values);
            changed.put(key, value);
            values = changed;
            return changed;
        }
    }
    // Entity keys (lowercase and underscores only, as Kiosk Satellite requires) and the settings they stand for.
    static final Map<String, String> CONTROLS = new HashMap<>();
    static {
        CONTROLS.put("ring", "ring");
        CONTROLS.put("chime", "chime");
        CONTROLS.put("wake_screen", "wakeScreen");
        CONTROLS.put("chime_volume", "chimeVolume");
    }

    /** Ring on this device, Play a chime, Wake the screen and Chime volume as Home Assistant controls (#19). */
    private void publishControls() {
        PluginHost h = host;
        Settings s = settings;
        Texts t = texts;
        if (h == null || s == null) return;
        try {
            h.publishSwitch("ring", t.ringControl(), s.ring);
            h.publishSwitch("chime", t.chimeControl(), s.chime);
            if (publishes("wake_screen", headless)) h.publishSwitch("wake_screen", t.wakeControl(), s.wakeScreen);
            h.publishNumber("chime_volume", t.chimeVolumeControl(), chimeVolumeRange(), (double) s.chimeVolume);
        } catch (UnsupportedOperationException | LinkageError older) {
            // A Kiosk Satellite without writable entities (an older one lacks the method itself): the settings page has them.
        } catch (RuntimeException refused) {
            log("controls: " + refused.getMessage());
        }
    }

    /** The Chime volume setting's own range, so Home Assistant offers the same steps. */
    private static Map<String, Object> chimeVolumeRange() {
        Map<String, Object> range = new LinkedHashMap<>();
        range.put("min", 5);
        range.put("max", 100);
        range.put("step", 5);
        range.put("unit", "%");
        range.put("mode", "slider");
        return range;
    }

    /**
     * Home Assistant changed a control: saved as the plugin setting it stands for, then applied the way a change on
     * the settings page is, which publishes the applied state back. A setting is the one value, so the two never differ.
     */
    private void control(String event, Map<String, Object> payload) {
        PluginHost h = host;
        if (h == null) return;
        String key = CONTROLS.get(event.substring(event.indexOf('.') + 1));
        Object value;
        if (key == null) return;
        if (event.startsWith("switch.") && !key.equals("chimeVolume") && payload.get("on") instanceof Boolean) {
            value = payload.get("on");
        } else if (event.equals("number.chime_volume") && payload.get("value") instanceof Number) {
            // Home Assistant does not snap to the step: to the setting's own 5s, within its range.
            long volume = Math.round(((Number) payload.get("value")).doubleValue() / 5) * 5;
            value = (int) Math.max(5, Math.min(100, volume));
        } else {
            return;
        }
        // Outside the plugin's lock: configure reaches the controller, which calls back into that lock itself.
        try {
            h.updateSettings(Collections.singletonMap(key, value));
        } catch (RuntimeException | LinkageError failed) {
            log("controls: " + key + " not saved: " + failed.getMessage());
            publishControls();  // the state as it still is
            return;
        }
        log("controls: " + key + " set to " + value + " from Home Assistant");
        apply(remember(key, value));
    }

    private void publishText(String key, String name, String value) {
        PluginHost h = host;
        if (h == null) return;
        try {
            h.publishTextSensor(key, name, value.length() > 512 ? value.substring(0, 512) : value);
        } catch (RuntimeException ignored) {
            // Readings are informational.
        }
    }

    private void status(String message, boolean error) {
        PluginHost h = host;
        if (h != null) h.status(message, error);
    }

    // The doorbell's snapshot

    /** Follows the snapshot entity (and stops following the old one when the setting changes). */
    private synchronized void followSnapshot() {
        PluginHost h = host;
        String wanted = settings.snapshotEntity;
        if (h == null || wanted.equals(snapshotFollowed)) return;
        try {
            if (!snapshotFollowed.isEmpty()) h.unsubscribe("ha.entity." + snapshotFollowed);
            snapshotFollowed = wanted;
            snapshotKey = "";
            snapshot = null;
            if (!wanted.isEmpty()) h.subscribe("ha.entity." + wanted);
        } catch (RuntimeException refused) {
            log("snapshot: cannot follow " + wanted + ": " + refused.getMessage());
        }
    }

    /** The image entity changed: fetch the new picture, off the plugin worker. */
    private void snapshotChanged(Map<String, Object> payload) {
        Object attributes = payload.get("attributes");
        Object picture = attributes instanceof Map ? ((Map<?, ?>) attributes).get("entity_picture") : null;
        String state = String.valueOf(payload.get("state"));
        log("snapshot: " + state + ", picture " + (picture == null ? "none" : "yes"));
        if (!(picture instanceof String) || ((String) picture).isEmpty() || state.equals("unavailable") || state.equals("null")) return;
        String key = state + "|" + picture;
        synchronized (this) {
            if (key.equals(snapshotKey)) return;
            snapshotKey = key;
        }
        Settings s = settings;
        String base = !s.haUrl.isEmpty() ? s.haUrl : "http://" + s.bridgeHost + ":8123";
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        final String url = ((String) picture).startsWith("http") ? (String) picture : base + picture;
        Thread thread = new Thread(() -> fetchSnapshot(url, state), "eufy-snapshot");
        thread.setDaemon(true);
        thread.start();
    }

    private void fetchSnapshot(String url, String state) {
        java.net.HttpURLConnection connection = null;
        try {
            connection = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            int code = connection.getResponseCode();
            if (code != 200) {
                log("snapshot: Home Assistant answered " + code);
                synchronized (this) {
                    snapshotKey = "";  // try again on the next change
                }
                return;
            }
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            try (java.io.InputStream in = connection.getInputStream()) {
                int n;
                while ((n = in.read(buffer)) > 0) {
                    bytes.write(buffer, 0, n);
                    if (bytes.size() > 8 * 1024 * 1024) throw new java.io.IOException("the picture is over 8 MB");
                }
            }
            byte[] data = bytes.toByteArray();
            String time = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
            long takenMillis = System.currentTimeMillis();
            try {
                java.time.OffsetDateTime taken = java.time.OffsetDateTime.parse(state);
                takenMillis = taken.toInstant().toEpochMilli();
                time = taken.atZoneSameInstant(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT));
            } catch (RuntimeException notATimestamp) {
                // The state is not an ISO time: the time of fetching will do.
            }
            showSnapshot(data, takenMillis, time);
        } catch (java.io.IOException | RuntimeException failed) {
            log("snapshot: " + failed);
            synchronized (this) {
                snapshotKey = "";
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * A doorbell picture, from the bridge's event or from Home Assistant's image entity: decoded to a sensible size,
     * remembered for the next call screen, and shown at once on one that is open. takenMillis is the picture's own
     * time: an old one must not pass for the person at the door now.
     */
    private void showSnapshot(byte[] data, long takenMillis, String time) {
        try {
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
            options.inSampleSize = Math.max(1, Math.max(bounds.outWidth, bounds.outHeight) / 1100);
            final android.graphics.Bitmap decoded = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, options);
            if (decoded == null) {
                log("snapshot: the picture does not decode");
                return;
            }
            final String caption = "Doorbell snapshot · " + time;
            snapshot = decoded;
            snapshotCaption = caption;
            snapshotAt = takenMillis;
            log("snapshot: " + decoded.getWidth() + "x" + decoded.getHeight() + " from " + data.length + " bytes, taken " + time);
            DoorbellView v = view;
            final boolean fresh = System.currentTimeMillis() - takenMillis < settings.snapshotMaxAgeMillis;
            if (v != null && fresh) v.post(() -> v.setSnapshot(decoded, caption));
        } catch (RuntimeException failed) {
            log("snapshot: " + failed);
        }
    }

    private void log(String message) {
        PluginHost h = host;
        if (h != null) h.log(message);
    }

    /** The validated settings as plain fields. */
    static final class Settings {
        final long snapshotMaxAgeMillis;
        final String bridgeHost, ringSerial, cameraSerial, snapshotEntity, haUrl, language, microphone, micRate, musicPause, restoreScreen, chimeStream;
        final int bridgePort;
        private final String name;
        final boolean ring, chime, wakeScreen, wakeWordRestore, musicRestore, callMode, pushToTalk;
        final long autoCloseMillis, maxCallMillis;
        final int callVolume, chimeVolume;
        final List<Cards.Card> cards;
        final List<Cards.Card> callButtons;
        final int columnPosition;  // DoorbellView.COLUMN_BOTTOM, COLUMN_CENTRE or COLUMN_TOP

        Settings(Map<String, Object> values) {
            bridgeHost = string(values, "bridgeHost");
            int parsed = 3002;
            try {
                Object port = values.get("bridgePort");
                if (port instanceof Number) parsed = ((Number) port).intValue();
                else if (port != null && !port.toString().trim().isEmpty()) parsed = Integer.parseInt(port.toString().trim());
            } catch (NumberFormatException notANumber) {
                parsed = 3002;
            }
            bridgePort = Math.max(1, Math.min(65535, parsed));
            ringSerial = string(values, "ringDevice");
            cameraSerial = string(values, "cameraDevice");
            snapshotEntity = string(values, "snapshotEntity");
            haUrl = string(values, "haUrl");
            long minutes = 15;
            try {
                String age = string(values, "snapshotMaxAge");
                if (!age.isEmpty()) minutes = Long.parseLong(age);
            } catch (NumberFormatException notANumber) {
                minutes = 15;
            }
            snapshotMaxAgeMillis = Math.max(1, Math.min(10080, minutes)) * 60_000L;
            name = string(values, "name");
            language = string(values, "language");
            String mic = string(values, "microphone");
            microphone = mic.isEmpty() ? "Automatic" : mic;
            String rate = string(values, "micRate");
            micRate = rate.isEmpty() ? "Automatic" : rate;
            pushToTalk = string(values, "talkMode").equals("Push to talk");
            chime = !Boolean.FALSE.equals(values.get("chime"));
            Object chimeLevel = values.get("chimeVolume");
            chimeVolume = chimeLevel instanceof Number ? Math.max(0, Math.min(100, ((Number) chimeLevel).intValue())) : 40;
            String sound = string(values, "chimeStream");
            chimeStream = java.util.Arrays.asList(Chime.STREAMS).contains(sound) ? sound : "Notifications";
            String pause = string(values, "musicPause");
            musicPause = pause.equals("At the ring") || pause.equals("At Speaker or Answer") ? pause : "Off";
            String restore = string(values, "restoreScreen");
            restoreScreen = java.util.Arrays.asList(ScreenRestore.MODES).contains(restore) ? restore : ScreenRestore.WHEN_NOT_ANSWERED;
            wakeScreen = !Boolean.FALSE.equals(values.get("wakeScreen"));
            ring = !Boolean.FALSE.equals(values.get("ring"));
            autoCloseMillis = autoClose(string(values, "autoClose"));
            maxCallMillis = maxCall(string(values, "maxCall"));
            wakeWordRestore = Boolean.TRUE.equals(values.get("wakeWordRestore"));
            musicRestore = Boolean.TRUE.equals(values.get("musicRestore"));
            callMode = !Boolean.FALSE.equals(values.get("callMode"));
            Object volume = values.get("callVolume");
            callVolume = volume instanceof Number ? Math.max(0, Math.min(100, ((Number) volume).intValue())) : 70;
            cards = Cards.parse(values.get("cards"));
            callButtons = Cards.parseCallButtons(values.get("callButtons"));
            String column = string(values, "landscapeColumn");
            columnPosition = column.startsWith("Centred") ? DoorbellView.COLUMN_CENTRE : column.startsWith("At the top") ? DoorbellView.COLUMN_TOP
                : DoorbellView.COLUMN_BOTTOM;
        }

        /** The doorbell's name for the viewer, or the translated default. */
        String name(Texts texts) {
            return name.isEmpty() ? texts.defaultName() : name;
        }

        boolean configured() {
            return !bridgeHost.isEmpty() && !ringSerial.isEmpty() && !cameraSerial.isEmpty();
        }

        /** Whether the bridge session can stay: only its address and the doorbell it listens to matter. */
        boolean sameConnection(Settings other) {
            return bridgeHost.equals(other.bridgeHost) && bridgePort == other.bridgePort && ringSerial.equals(other.ringSerial);
        }

        private static long autoClose(String choice) {
            switch (choice) {
                case "Off": return 0;
                case "30 seconds": return 30_000;
                case "2 minutes": return 120_000;
                case "5 minutes": return 300_000;
                default: return 60_000;
            }
        }

        private static long maxCall(String choice) {
            switch (choice) {
                case "Off": return 0;
                case "5 minutes": return 300_000;
                case "30 minutes": return 1_800_000;
                default: return 600_000;
            }
        }

        private static String string(Map<String, Object> values, String key) {
            Object value = values.get(key);
            return value instanceof String ? ((String) value).trim() : "";
        }
    }
}
