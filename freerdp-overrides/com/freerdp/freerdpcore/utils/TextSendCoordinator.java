package com.freerdp.freerdpcore.utils;

import java.text.Normalizer;
import java.util.concurrent.atomic.AtomicLong;

/** One explicit text send. Mutations run on the session's UI thread. */
public final class TextSendCoordinator {
    public enum PasteMode {
        TERMINAL("Terminal Linux (Ctrl+Shift+V)", 0x11, 0x10, 0x56),
        DESKTOP("Windows / ứng dụng thường (Ctrl+V)", 0x11, 0x56),
        SHIFT_INSERT("Ứng dụng hỗ trợ Shift+Insert", 0x10, 0x2D),
        MAC("macOS (Command+V)", 0x5B, 0x56);

        private final String label;
        private final int[] keys;

        PasteMode(String label, int... keys) {
            this.label = label;
            this.keys = keys;
        }

        @Override public String toString() { return label; }
    }

    public enum Result { SENT, CLIPBOARD_FAILED, KEY_FAILED, TIMEOUT, CLIPBOARD_CHANGED }

    public interface Transport {
        boolean sendClipboard(String text, long requestId);
        boolean sendKey(int key, boolean down);
    }

    public interface Listener { void onFinished(Result result); }

    private static final AtomicLong NEXT_ID = new AtomicLong();
    private final Transport transport;
    private final Listener listener;
    private volatile long pendingId;
    private PasteMode mode;

    public TextSendCoordinator(Transport transport, Listener listener) {
        this.transport = transport;
        this.listener = listener;
    }

    public long begin(String draft, PasteMode pasteMode) {
        if (pendingId != 0 || draft.isEmpty()) return 0;
        pendingId = NEXT_ID.incrementAndGet();
        mode = pasteMode;
        if (!transport.sendClipboard(Normalizer.normalize(draft, Normalizer.Form.NFC), pendingId))
            finish(Result.CLIPBOARD_FAILED);
        return pendingId;
    }

    public boolean isPending() { return pendingId != 0; }

    public void onClipboardReady(long requestId, boolean accepted) {
        if (pendingId == 0 || requestId != pendingId) return;
        if (!accepted) {
            finish(Result.CLIPBOARD_FAILED);
            return;
        }
        boolean sent = true;
        int attempted = 0;
        try {
            for (int key : mode.keys) {
                attempted++;
                if (!transport.sendKey(key, true)) {
                    sent = false;
                    break;
                }
            }
        } finally {
            // Always release every attempted key, even if a key-down was rejected.
            for (int i = attempted - 1; i >= 0; i--)
                sent = transport.sendKey(mode.keys[i], false) && sent;
            finish(sent ? Result.SENT : Result.KEY_FAILED);
        }
    }

    public void timeout(long requestId) {
        if (pendingId != 0 && pendingId == requestId) finish(Result.TIMEOUT);
    }

    public void clipboardChanged() {
        if (isPending()) finish(Result.CLIPBOARD_CHANGED);
    }

    public void cancel() { pendingId = 0; }

    private void finish(Result result) {
        pendingId = 0;
        listener.onFinished(result);
    }
}
