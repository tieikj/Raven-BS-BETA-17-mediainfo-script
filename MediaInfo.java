// MediaInfo - minimal "now playing" HUD for Raven bS (Minecraft font, no thumbnail).
// Needs media-helper.ps1 running on your PC (Windows) and the Manager's HTTP setting enabled.
// Put this file in <minecraftDir>/keystrokes/scripts/

final String HELPER_URL = "http://127.0.0.1:8765/";
final long POLL_MS = 1000L;

volatile String title = "";
volatile String artist = "";
volatile long lengthMs = 0L;
volatile long anchorPos = 0L;
volatile long anchorAt = 0L;
volatile long lastReportedPos = 0L;
volatile boolean playing = false;
volatile boolean offline = false;
volatile String offlineMsg = "Media: helper offline";

long lastPoll = 0L;
volatile boolean polling = false;

void onLoad() {
    modules.registerButton("Show time", true);
    modules.registerButton("Progress bar", false);
    modules.registerButton("Background", false);
    modules.registerSlider("Theme", 1, new String[]{"Rainbow", "Cherry", "Cotton_candy", "Flare", "Flower", "Gold", "Grayscale", "Royal", "Sky", "Vine"});
    modules.registerSlider("X", 6.0, 0.0, 1000.0, 1.0);
    modules.registerSlider("Y", 40.0, 0.0, 1000.0, 1.0);
    modules.registerSlider("Scale", "x", 1.0, 0.5, 2.0, 0.1);
}

void onEnable() {
    lastPoll = 0L;
    offline = false;
}

void onDisable() {
    clearTrack();
    offline = false;
}

String getSuffix() {
    if (title.isEmpty()) return null;
    return playing ? "Playing" : "Paused";
}

// ---- polling (once per second, off the render/tick thread) ----

void onPreUpdate() {
    long now = client.time();
    if (polling || now - lastPoll < POLL_MS) return;
    lastPoll = now;
    polling = true;
    client.async(() -> {
        try {
            poll();
        } catch (Exception e) {
            clearTrack();
            offlineMsg = "Media: error talking to helper";
            offline = true;
        } finally {
            polling = false;
        }
    });
}

void poll() {
    Request req = new Request("GET", HELPER_URL);
    req.setConnectTimeout(800);
    req.setReadTimeout(1500);
    Response r = req.fetch();
    if (r == null) {
        clearTrack();
        offlineMsg = "Media: can't reach helper (is media-helper.ps1 running?)";
        offline = true;
        return;
    }
    if (r.code() != 200) {
        clearTrack();
        String body = r.string();
        if (r.code() == 404 && (body == null || body.isEmpty())) {
            offlineMsg = "Media: HTTP is disabled in Manager";
        } else {
            offlineMsg = "Media: helper returned HTTP " + r.code();
        }
        offline = true;
        return;
    }
    offline = false;

    Json j = r.json();
    String t = j.get("title").asString();
    if (t == null || t.trim().isEmpty()) {
        clearTrack();
        return;
    }
    String a = j.get("artist").asString();
    long len = j.get("length").asLong();
    long pos = j.get("position").asLong();
    boolean p = j.get("playing").asBoolean();
    applyTrack(t.trim(), a == null ? "" : a.trim(), len, pos, p);
}

void applyTrack(String t, String a, long len, long pos, boolean p) {
    long now = client.time();
    boolean same = t.equals(title) && a.equals(artist);
    long est = playing ? anchorPos + (now - anchorAt) : anchorPos;

    // Re-anchor on a new track, a play/pause change, or a real seek
    // (reported position changed AND disagrees with our estimate).
    boolean seeked = pos != lastReportedPos && Math.abs(est - pos) > 2500L;
    if (!same || p != playing || seeked) {
        anchorPos = pos;
        anchorAt = now;
    }
    lastReportedPos = pos;

    title = t;
    artist = a;
    lengthMs = len;
    playing = p;
}

void clearTrack() {
    title = "";
    artist = "";
    playing = false;
    lengthMs = 0L;
}

// ---- HUD ----

String fit(String str, int maxW) {
    if (render.getFontWidth(str) <= maxW) return str;
    String cut = str;
    while (cut.length() > 1 && render.getFontWidth(cut + "..") > maxW) {
        cut = cut.substring(0, cut.length() - 1);
    }
    return cut + "..";
}

String fmt(long ms) {
    long total = ms / 1000L;
    long m = total / 60L;
    long sec = total % 60L;
    return m + ":" + (sec < 10L ? "0" : "") + sec;
}

void onRenderTick(float partialTicks) {
    if (client.getPlayer() == null) return;

    float s = (float) modules.getSlider(scriptName, "Scale");
    float x = (float) modules.getSlider(scriptName, "X");
    float y = (float) modules.getSlider(scriptName, "Y");

    if (offline) {
        render.text2d(offlineMsg, x, y, s, 0xFFFF5555, true);
        return;
    }

    String t = title;
    if (t.isEmpty()) return;
    String a = artist;

    boolean showTime = modules.getButton(scriptName, "Show time");
    boolean showBar = modules.getButton(scriptName, "Progress bar");
    boolean showBg = modules.getButton(scriptName, "Background");

    long len = lengthMs;
    long pos = playing ? anchorPos + (client.time() - anchorAt) : anchorPos;
    pos = len > 0L ? Math.max(0L, Math.min(pos, len)) : Math.max(0L, pos);

    int theme = render.getThemeRGB(modules.getSliderString(scriptName, "Theme")) | 0xFF000000;

    String line1 = fit(t, 160);
    String line2 = fit(a, 160);
    if (showTime) {
        String time = fmt(pos) + (len > 0L ? " / " + fmt(len) : "");
        String fa = fit(a, 100);
        line2 = fa.isEmpty() ? time : fa + "  " + time;
    }

    float lh = (render.getFontHeight() + 2) * s;
    float w = Math.max(render.getFontWidth(line1), render.getFontWidth(line2)) * s;
    float barH = Math.max(1f, s);
    float h = lh * 2f + (showBar && len > 0L ? barH + 2f * s : 0f);

    if (showBg) {
        render.rect(x - 2f * s, y - 2f * s, x + w + 2f * s, y + h, 0x90000000);
    }

    render.text2d(line1, x, y, s, playing ? theme : 0xFF808088, true);
    render.text2d(line2, x, y + lh, s, 0xFFB8B8C0, true);

    if (showBar && len > 0L) {
        float barY = y + lh * 2f + 1f * s;
        float fill = w * ((float) pos / (float) len);
        render.rect(x, barY, x + w, barY + barH, 0x50FFFFFF);
        render.rect(x, barY, x + fill, barY + barH, playing ? theme : 0xFF808088);
    }
}
