package com.example.remotepc;

import com.freerdp.freerdpcore.utils.TextSendCoordinator;
import com.freerdp.freerdpcore.utils.TextSendCoordinator.PasteMode;
import com.freerdp.freerdpcore.utils.TextSendCoordinator.Result;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class TextSendCoordinatorTest {
    private static class FakeTransport implements TextSendCoordinator.Transport {
        String clipboard;
        long requestId;
        boolean clipboardAvailable = true;
        int failKey = -1;
        List<String> keys = new ArrayList<>();
        @Override public boolean sendClipboard(String text, long id) {
            clipboard = text;
            requestId = id;
            return clipboardAvailable;
        }
        @Override public boolean sendKey(int key, boolean down) {
            keys.add(key + (down ? "+" : "-"));
            return !(down && key == failKey);
        }
    }

    @Test public void terminalSendWaitsForMatchingAckAndPastesExactlyOnce() {
        FakeTransport transport = new FakeTransport();
        List<Result> results = new ArrayList<>();
        TextSendCoordinator sender = new TextSendCoordinator(transport, results::add);
        long id = sender.begin("echo Tiếng Việt\n  dòng 2\n", PasteMode.TERMINAL);
        assertTrue(sender.isPending());
        assertTrue(transport.keys.isEmpty());
        sender.onClipboardReady(id + 1, true);
        assertTrue(transport.keys.isEmpty());
        sender.onClipboardReady(id, true);
        assertEquals(Arrays.asList("162+", "160+", "86+", "86-", "160-", "162-"), transport.keys);
        assertEquals("echo Tiếng Việt\n  dòng 2\n", transport.clipboard);
        assertEquals(Arrays.asList(Result.SENT), results);
        assertFalse(sender.isPending());
        sender.onClipboardReady(id, true);
        assertEquals(6, transport.keys.size());
        // No Enter key: newlines travel as clipboard data, not shell submissions.
        assertFalse(transport.keys.contains("13+"));
    }

    @Test public void normalizationPreservesVietnameseEmojiAndWhitespace() {
        FakeTransport transport = new FakeTransport();
        TextSendCoordinator sender = new TextSendCoordinator(transport, result -> {});
        sender.begin("  Tie\u0302\u0301ng Vie\u0323\u0302t\t😀\r\n\n", PasteMode.TERMINAL);
        assertEquals("  Tiếng Việt\t😀\r\n\n", transport.clipboard);
        byte[] encoded = transport.clipboard.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(transport.clipboard, new String(encoded, java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test public void failedOrMissingClipboardAckNeverSendsKeys() {
        for (boolean timeout : new boolean[] {false, true}) {
            FakeTransport transport = new FakeTransport();
            List<Result> results = new ArrayList<>();
            TextSendCoordinator sender = new TextSendCoordinator(transport, results::add);
            long id = sender.begin("draft", PasteMode.DESKTOP);
            if (timeout) sender.timeout(id);
            else sender.onClipboardReady(id, false);
            sender.onClipboardReady(id, true);
            assertTrue(transport.keys.isEmpty());
            assertEquals(Arrays.asList(timeout ? Result.TIMEOUT : Result.CLIPBOARD_FAILED), results);
        }
    }

    @Test public void cancellationDisconnectAndOldAckCannotPasteIntoNextSend() {
        FakeTransport transport = new FakeTransport();
        TextSendCoordinator sender = new TextSendCoordinator(transport, result -> {});
        long oldId = sender.begin("old", PasteMode.TERMINAL);
        assertEquals(0, sender.begin("double tap", PasteMode.DESKTOP));
        assertEquals("old", transport.clipboard);
        sender.cancel();
        long newId = sender.begin("new", PasteMode.SHIFT_INSERT);
        assertNotEquals(oldId, newId);
        sender.onClipboardReady(oldId, true);
        sender.timeout(oldId);
        assertTrue(sender.isPending());
        assertTrue(transport.keys.isEmpty());
        sender.onClipboardReady(newId, true);
        assertEquals(Arrays.asList("160+", "45+", "45-", "160-"), transport.keys);
    }

    @Test public void clipboardReplacementCancelsWithoutTryingAnotherShortcut() {
        FakeTransport transport = new FakeTransport();
        List<Result> results = new ArrayList<>();
        TextSendCoordinator sender = new TextSendCoordinator(transport, results::add);
        long id = sender.begin("draft", PasteMode.TERMINAL);
        sender.clipboardChanged();
        sender.onClipboardReady(id, true);
        assertTrue(transport.keys.isEmpty());
        assertEquals(Arrays.asList(Result.CLIPBOARD_CHANGED), results);
    }

    @Test public void failedKeyDownReleasesModifiersAndDoesNotRetry() {
        FakeTransport transport = new FakeTransport();
        transport.failKey = 0x56;
        List<Result> results = new ArrayList<>();
        TextSendCoordinator sender = new TextSendCoordinator(transport, results::add);
        long id = sender.begin("draft", PasteMode.TERMINAL);
        sender.onClipboardReady(id, true);
        assertEquals(Arrays.asList("162+", "160+", "86+", "86-", "160-", "162-"), transport.keys);
        assertEquals(Arrays.asList(Result.KEY_FAILED), results);
    }

    @Test public void desktopAndMacUseOnlySelectedPasteChord() {
        for (PasteMode mode : new PasteMode[] {PasteMode.DESKTOP, PasteMode.MAC}) {
            FakeTransport transport = new FakeTransport();
            TextSendCoordinator sender = new TextSendCoordinator(transport, result -> {});
            long id = sender.begin("draft", mode);
            sender.onClipboardReady(id, true);
            String modifier = mode == PasteMode.DESKTOP ? "162" : "91";
            assertEquals(Arrays.asList(modifier + "+", "86+", "86-", modifier + "-"), transport.keys);
        }
    }

    @Test public void queueRejectionAndEmptyDraftDoNotLeaveSendBusy() {
        FakeTransport transport = new FakeTransport();
        transport.clipboardAvailable = false;
        List<Result> results = new ArrayList<>();
        TextSendCoordinator sender = new TextSendCoordinator(transport, results::add);
        assertEquals(0, sender.begin("", PasteMode.TERMINAL));
        assertTrue(results.isEmpty());
        assertEquals(0, sender.begin("draft", PasteMode.TERMINAL));
        assertFalse(sender.isPending());
        assertEquals(Arrays.asList(Result.CLIPBOARD_FAILED), results);
        assertTrue(transport.keys.isEmpty());
    }
}
