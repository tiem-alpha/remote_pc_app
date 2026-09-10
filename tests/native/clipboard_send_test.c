/* Exercise the actual Android clipboard/event code with a simulated RDP peer.
 * No desktop connection is needed. Run inside ART for WinPR's Unicode conversion. */
#include <assert.h>
#include <stdio.h>
#include <stdarg.h>
#include "android_cliprdr.c"
#undef TAG
#include "android_event.c"

static int callback_count;
static jlong last_request;
static int last_accepted;

void freerdp_callback(const char* name, const char* signature, ...)
{
    assert(strcmp(name, "OnClipboardSendResult") == 0);
    assert(strcmp(signature, "(JJZ)V") == 0);
    va_list args;
    va_start(args, signature);
    assert(va_arg(args, jlong) != 0);
    last_request = va_arg(args, jlong);
    last_accepted = va_arg(args, int);
    va_end(args);
    callback_count++;
}

static UINT accept_format_list(CliprdrClientContext* context, const CLIPRDR_FORMAT_LIST* list)
{
    assert(list->numFormats > 0);
    return CHANNEL_RC_OK;
}

static void send_text(freerdp* instance, const char* text, UINT64 request)
{
    androidContext* afc = (androidContext*)instance->context;
    ANDROID_EVENT_CLIPBOARD* event = android_event_clipboard_new(text, strlen(text), "text/plain");
    assert(event);
    event->requestId = request;
    assert(android_push_event(instance, (ANDROID_EVENT*)event));
    assert(android_process_event(afc->event_queue, instance));
}

static void ack(CliprdrClientContext* context, BOOL accepted)
{
    CLIPRDR_FORMAT_LIST_RESPONSE response = {0};
    response.common.msgFlags = accepted ? CB_RESPONSE_OK : CB_RESPONSE_FAIL;
    assert(android_cliprdr_server_format_list_response(context, &response) == CHANNEL_RC_OK);
}

JNIEXPORT jint JNICALL Java_ClipboardProtocolTest_runTests(JNIEnv* env, jclass cls)
{
    freerdp instance = {0};
    androidContext afc = {0};
    CliprdrClientContext cliprdr = {0};
    instance.context = &afc.common.context;
    afc.common.context.instance = &instance;
    afc.clipboard = ClipboardCreate();
    assert(afc.clipboard);
    afc.cliprdr = &cliprdr;
    afc.clipboardSync = TRUE;
    cliprdr.custom = &afc;
    cliprdr.ClientFormatList = accept_format_list;
    assert(android_event_queue_init(&instance));

    send_text(&instance, "old clipboard", 0);
    // Force cached Unicode conversion of the old clipboard before replacing it.
    UINT32 size = 0;
    void* old = ClipboardGetData(afc.clipboard, CF_UNICODETEXT, &size);
    assert(old);
    free(old);
    send_text(&instance, "Tiếng Việt 😀\n  dòng 2", 101);
    assert(callback_count == 0);
    ack(&cliprdr, TRUE); // ACK for ordinary clipboard sync must not trigger paste.
    assert(callback_count == 0);
    ack(&cliprdr, TRUE);
    assert(callback_count == 1 && last_request == 101 && last_accepted);
    UINT32 format = ClipboardRegisterFormat(afc.clipboard, "text/plain");
    char* text = ClipboardGetData(afc.clipboard, format, &size);
    assert(text && strcmp(text, "Tiếng Việt 😀\n  dòng 2") == 0);
    free(text);
    const UINT16 expected[] = { 'T','i',0x1EBF,'n','g',' ','V','i',0x1EC7,'t',' ',
                               0xD83D,0xDE00,13,10,' ',' ','d',0xF2,'n','g',' ','2',0 };
    UINT16* unicode = ClipboardGetData(afc.clipboard, CF_UNICODETEXT, &size);
    assert(unicode && size == sizeof(expected));
    assert(memcmp(unicode, expected, size) == 0);
    free(unicode);

    send_text(&instance, "pending", 102);
    send_text(&instance, "another local copy", 0);
    assert(callback_count == 2 && last_request == 102 && !last_accepted);
    ack(&cliprdr, TRUE);
    ack(&cliprdr, TRUE);
    assert(callback_count == 2);

    send_text(&instance, "rejected", 103);
    ack(&cliprdr, FALSE);
    assert(callback_count == 3 && last_request == 103 && !last_accepted);

    send_text(&instance, "remote copy wins", 104);
    CLIPRDR_FORMAT_LIST remote_list = {0};
    assert(android_cliprdr_server_format_list(&cliprdr, &remote_list) == CHANNEL_RC_OK);
    assert(callback_count == 4 && last_request == 104 && !last_accepted);
    ack(&cliprdr, TRUE);
    assert(callback_count == 4);

    afc.clipboardSync = FALSE;
    send_text(&instance, "clipboard disabled", 105);
    assert(callback_count == 5 && last_request == 105 && !last_accepted);
    afc.clipboardSync = TRUE;
    send_text(&instance, "channel still works", 106);
    ack(&cliprdr, TRUE);
    assert(callback_count == 6 && last_request == 106 && last_accepted);

    android_event_queue_uninit(&instance);
    ClipboardDestroy(afc.clipboard);
    puts("PASS: ACK ordering, UTF-8/UTF-16 Vietnamese + emoji, stale conversion, replacement, rejection, disabled channel");
    return 0;
}
