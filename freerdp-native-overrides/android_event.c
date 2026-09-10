/**
 * FreeRDP: A Remote Desktop Protocol Implementation
 * Android Event System
 *
 * Copyright 2010-2012 Marc-Andre Moreau <marcandre.moreau@gmail.com>
 * Copyright 2013 Thincast Technologies GmbH, Author: Martin Fleisz
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

#include <freerdp/config.h>

#include <winpr/crt.h>

#include <freerdp/freerdp.h>
#include <freerdp/log.h>

#define TAG CLIENT_TAG("android")

#include "android_freerdp.h"
#include "android_cliprdr.h"
#include "android_jni_callback.h"

BOOL android_push_event(freerdp* inst, ANDROID_EVENT* event)
{
	androidContext* aCtx = (androidContext*)inst->context;

	if (aCtx->event_queue->count >= aCtx->event_queue->size)
	{
		size_t new_size = aCtx->event_queue->size;
		do
		{
			if (new_size >= SIZE_MAX - 128ull)
				return FALSE;

			new_size += 128ull;
		} while (new_size <= aCtx->event_queue->count);
		void* new_events =
		    realloc((void*)aCtx->event_queue->events, sizeof(ANDROID_EVENT*) * new_size);

		if (!new_events)
			return FALSE;

		aCtx->event_queue->events = new_events;
		aCtx->event_queue->size = new_size;
	}

	aCtx->event_queue->events[(aCtx->event_queue->count)++] = event;
	return SetEvent(aCtx->event_queue->isSet);
}

static ANDROID_EVENT* android_peek_event(ANDROID_EVENT_QUEUE* queue)
{
	ANDROID_EVENT* event;

	if (queue->count < 1)
		return nullptr;

	event = queue->events[0];
	return event;
}

static ANDROID_EVENT* android_pop_event(ANDROID_EVENT_QUEUE* queue)
{
	ANDROID_EVENT* event;

	if (queue->count < 1)
		return nullptr;

	event = queue->events[0];
	(queue->count)--;

	for (size_t i = 0; i < queue->count; i++)
	{
		queue->events[i] = queue->events[i + 1];
	}

	return event;
}

static BOOL android_process_event(ANDROID_EVENT_QUEUE* queue, freerdp* inst)
{
	rdpContext* context;

	WINPR_ASSERT(queue);
	WINPR_ASSERT(inst);

	context = inst->context;
	WINPR_ASSERT(context);

	while (android_peek_event(queue))
	{
		BOOL rc = FALSE;
		androidContext* afc = (androidContext*)context;
		ANDROID_EVENT* event = android_pop_event(queue);

		WINPR_ASSERT(event);

		switch (event->type)
		{
			case EVENT_TYPE_KEY:
			{
				ANDROID_EVENT_KEY* key_event = (ANDROID_EVENT_KEY*)event;

				rc = freerdp_input_send_keyboard_event(context->input, key_event->flags,
				                                       key_event->scancode);
			}
			break;

			case EVENT_TYPE_KEY_UNICODE:
			{
				ANDROID_EVENT_KEY* key_event = (ANDROID_EVENT_KEY*)event;

				rc = freerdp_input_send_unicode_keyboard_event(context->input, key_event->flags,
				                                               key_event->scancode);
			}
			break;

			case EVENT_TYPE_CURSOR:
			{
				ANDROID_EVENT_CURSOR* cursor_event = (ANDROID_EVENT_CURSOR*)event;

				rc = freerdp_input_send_mouse_event(context->input, cursor_event->flags,
				                                    cursor_event->x, cursor_event->y);
			}
			break;

			case EVENT_TYPE_CLIPBOARD:
			{
				ANDROID_EVENT_CLIPBOARD* clipboard_event = (ANDROID_EVENT_CLIPBOARD*)event;
				const char* mimeType = clipboard_event->mimeType;
				android_cliprdr_complete_text_send(afc, FALSE);
				afc->clipboardRequestId = clipboard_event->requestId;
				if (!afc->clipboard || !afc->cliprdr || !afc->clipboardSync)
				{
					android_cliprdr_complete_text_send(afc, FALSE);
					rc = TRUE; /* Clipboard unavailable must not disconnect the desktop. */
					break;
				}
				UINT32 formatId = ClipboardRegisterFormat(afc->clipboard, mimeType);
				UINT32 size = clipboard_event->data_length;

				/* Drop old cached conversions (Unicode, HTML, images) before replacing text. */
				ClipboardEmpty(afc->clipboard);
				if (size && (!formatId ||
				    !ClipboardSetData(afc->clipboard, formatId, clipboard_event->data, size)))
				{
					android_cliprdr_complete_text_send(afc, FALSE);
					rc = TRUE;
					break;
				}

				rc = (android_cliprdr_send_client_format_list(afc->cliprdr) == CHANNEL_RC_OK);
				if (!rc)
					android_cliprdr_complete_text_send(afc, FALSE);
			}
			break;

			case EVENT_TYPE_DISCONNECT:
			default:
				break;
		}

		android_event_free(event);

		if (!rc)
			return FALSE;
	}

	return TRUE;
}

/* Use real UTF-8 bytes, not JNI modified UTF-8, so supplementary characters
 * such as emoji survive the clipboard's UTF-8 -> UTF-16 conversion. */
JNIEXPORT jboolean JNICALL
Java_com_freerdp_freerdpcore_services_LibFreeRDP_freerdp_1send_1clipboard_1data_1with_1id(
    JNIEnv* env, jclass cls, jlong instance, jbyteArray data, jlong requestId)
{
	WINPR_UNUSED(cls);
	if (!instance || !data || requestId <= 0)
		return JNI_FALSE;
	jsize length = (*env)->GetArrayLength(env, data);
	jbyte* bytes = (*env)->GetByteArrayElements(env, data, nullptr);
	if (!bytes)
		return JNI_FALSE;
	ANDROID_EVENT_CLIPBOARD* event =
	    android_event_clipboard_new(bytes, (size_t)length, "text/plain");
	(*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
	if (!event)
		return JNI_FALSE;
	event->requestId = (UINT64)requestId;
	if (!android_push_event((freerdp*)instance, (ANDROID_EVENT*)event))
	{
		android_event_free((ANDROID_EVENT*)event);
		return JNI_FALSE;
	}
	return JNI_TRUE;
}

HANDLE android_get_handle(freerdp* inst)
{
	androidContext* aCtx;

	if (!inst || !inst->context)
		return nullptr;

	aCtx = (androidContext*)inst->context;

	if (!aCtx->event_queue || !aCtx->event_queue->isSet)
		return nullptr;

	return aCtx->event_queue->isSet;
}

BOOL android_check_handle(freerdp* inst)
{
	androidContext* aCtx;

	if (!inst || !inst->context)
		return FALSE;

	aCtx = (androidContext*)inst->context;

	if (!aCtx->event_queue || !aCtx->event_queue->isSet)
		return FALSE;

	if (WaitForSingleObject(aCtx->event_queue->isSet, 0) == WAIT_OBJECT_0)
	{
		if (!ResetEvent(aCtx->event_queue->isSet))
			return FALSE;

		if (!android_process_event(aCtx->event_queue, inst))
			return FALSE;
	}

	return TRUE;
}

ANDROID_EVENT_KEY* android_event_key_new(int flags, UINT16 scancode)
{
	ANDROID_EVENT_KEY* event = (ANDROID_EVENT_KEY*)calloc(1, sizeof(ANDROID_EVENT_KEY));

	if (!event)
		return nullptr;

	event->type = EVENT_TYPE_KEY;
	event->flags = flags;
	event->scancode = scancode;
	return event;
}

static void android_event_key_free(ANDROID_EVENT_KEY* event)
{
	free(event);
}

ANDROID_EVENT_KEY* android_event_unicodekey_new(UINT16 flags, UINT16 key)
{
	ANDROID_EVENT_KEY* event;
	event = (ANDROID_EVENT_KEY*)calloc(1, sizeof(ANDROID_EVENT_KEY));

	if (!event)
		return nullptr;

	event->type = EVENT_TYPE_KEY_UNICODE;
	event->flags = flags;
	event->scancode = key;
	return event;
}

static void android_event_unicodekey_free(ANDROID_EVENT_KEY* event)
{
	free(event);
}

ANDROID_EVENT_CURSOR* android_event_cursor_new(UINT16 flags, UINT16 x, UINT16 y)
{
	ANDROID_EVENT_CURSOR* event;
	event = (ANDROID_EVENT_CURSOR*)calloc(1, sizeof(ANDROID_EVENT_CURSOR));

	if (!event)
		return nullptr;

	event->type = EVENT_TYPE_CURSOR;
	event->x = x;
	event->y = y;
	event->flags = flags;
	return event;
}

static void android_event_cursor_free(ANDROID_EVENT_CURSOR* event)
{
	free(event);
}

ANDROID_EVENT* android_event_disconnect_new(void)
{
	ANDROID_EVENT* event;
	event = (ANDROID_EVENT*)calloc(1, sizeof(ANDROID_EVENT));

	if (!event)
		return nullptr;

	event->type = EVENT_TYPE_DISCONNECT;
	return event;
}

static void android_event_disconnect_free(ANDROID_EVENT* event)
{
	free(event);
}

ANDROID_EVENT_CLIPBOARD* android_event_clipboard_new(const void* data, size_t data_length,
                                                     const char* mimeType)
{
	ANDROID_EVENT_CLIPBOARD* event;
	event = (ANDROID_EVENT_CLIPBOARD*)calloc(1, sizeof(ANDROID_EVENT_CLIPBOARD));

	if (!event)
		return nullptr;

	event->type = EVENT_TYPE_CLIPBOARD;
	event->mimeType = mimeType ? _strdup(mimeType) : nullptr;

	if (mimeType && !event->mimeType)
	{
		free(event);
		return nullptr;
	}

	if (data && data_length > 0)
	{
		const BOOL isText = !mimeType || strcmp(mimeType, "text/plain") == 0;
		/* Text data needs a null terminator; image data is stored as-is. */
		event->data = isText ? calloc(data_length + 1, sizeof(char)) : malloc(data_length);

		if (!event->data)
		{
			free(event->mimeType);
			free(event);
			return nullptr;
		}

		memcpy(event->data, data, data_length);
		event->data_length = isText ? data_length + 1 : data_length;
	}

	return event;
}

static void android_event_clipboard_free(ANDROID_EVENT_CLIPBOARD* event)
{
	if (event)
	{
		free(event->data);
		free(event->mimeType);
		free(event);
	}
}

BOOL android_event_queue_init(freerdp* inst)
{
	androidContext* aCtx = (androidContext*)inst->context;
	ANDROID_EVENT_QUEUE* queue;
	queue = (ANDROID_EVENT_QUEUE*)calloc(1, sizeof(ANDROID_EVENT_QUEUE));

	if (!queue)
	{
		WLog_ERR(TAG, "android_event_queue_init: memory allocation failed");
		return FALSE;
	}

	queue->size = 16;
	queue->count = 0;
	queue->isSet = CreateEventA(nullptr, TRUE, FALSE, nullptr);

	if (!queue->isSet)
	{
		free(queue);
		return FALSE;
	}

	queue->events = (ANDROID_EVENT**)calloc(queue->size, sizeof(ANDROID_EVENT*));

	if (!queue->events)
	{
		WLog_ERR(TAG, "android_event_queue_init: memory allocation failed");
		(void)CloseHandle(queue->isSet);
		free(queue);
		return FALSE;
	}

	aCtx->event_queue = queue;
	return TRUE;
}

void android_event_queue_uninit(freerdp* inst)
{
	androidContext* aCtx;
	ANDROID_EVENT_QUEUE* queue;

	if (!inst || !inst->context)
		return;

	aCtx = (androidContext*)inst->context;
	queue = aCtx->event_queue;

	if (queue)
	{
		if (queue->isSet)
		{
			(void)CloseHandle(queue->isSet);
			queue->isSet = nullptr;
		}

		if (queue->events)
		{
			free(queue->events);
			queue->events = nullptr;
			queue->size = 0;
			queue->count = 0;
		}

		free(queue);
	}
}

void android_event_free(ANDROID_EVENT* event)
{
	if (!event)
		return;

	switch (event->type)
	{
		case EVENT_TYPE_KEY:
			android_event_key_free((ANDROID_EVENT_KEY*)event);
			break;

		case EVENT_TYPE_KEY_UNICODE:
			android_event_unicodekey_free((ANDROID_EVENT_KEY*)event);
			break;

		case EVENT_TYPE_CURSOR:
			android_event_cursor_free((ANDROID_EVENT_CURSOR*)event);
			break;

		case EVENT_TYPE_DISCONNECT:
			android_event_disconnect_free((ANDROID_EVENT*)event);
			break;

		case EVENT_TYPE_CLIPBOARD:
			android_event_clipboard_free((ANDROID_EVENT_CLIPBOARD*)event);
			break;

		default:
			break;
	}
}
