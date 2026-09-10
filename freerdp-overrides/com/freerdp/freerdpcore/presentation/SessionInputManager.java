/*
   Android Session Input Manager

   Copyright 2026 Ibrahim Sevinc <ibrahim.sevinc.mail@gmail.com>

   This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
   If a copy of the MPL was not distributed with this file, You can obtain one at
   http://mozilla.org/MPL/2.0/.
*/

package com.freerdp.freerdpcore.presentation;

import android.content.Context;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.Toast;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import com.freerdp.freerdpcore.utils.TextSendCoordinator;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

import com.freerdp.freerdpcore.R;
import com.freerdp.freerdpcore.services.LibFreeRDP;
import com.freerdp.freerdpcore.utils.KeyboardMapper;
import com.freerdp.freerdpcore.utils.Mouse;

import java.util.List;

public class SessionInputManager
    implements SessionView.SessionViewListener, TouchPointerView.TouchPointerListener,
               KeyboardMapper.KeyProcessingListener, KeyboardView.OnKeyboardActionListener
{
	private static final String TAG = "FreeRDP.SessionInputManager";

	private static final int SCROLLING_TIMEOUT = 16;
	private static final int SCROLLING_DISTANCE = 12;
	private static final int SCROLLING_EDGE_MARGIN = 16;
	private static final int MAX_DISCARDED_MOVE_EVENTS = 3;
	private static final int SEND_MOVE_EVENT_TIMEOUT = 150;

	private static final int MSG_SEND_MOVE_EVENT = 1;
	private static final int MSG_SCROLLING_REQUESTED = 2;

	private final Context context;
	private final KeyboardMapper keyboardMapper;
	private final ScrollView2D scrollView;
	private final SessionView sessionView;
	private final TouchPointerView touchPointerView;
	private final KeyboardView keyboardView;
	private final KeyboardView modifiersKeyboardView;
	private final PinchZoomListener pinchZoomListener = new PinchZoomListener();

	private Keyboard modifiersKeyboard;
	private Keyboard specialkeysKeyboard;
	private Keyboard numpadKeyboard;
	private Keyboard cursorKeyboard;

	private int safeInsetLeft = 0;
	private int safeInsetTop = 0;

	public void setSafeInsets(int left, int top)
	{
		safeInsetLeft = left;
		safeInsetTop = top;
	}

	// Native FreeRDP instance handle. 0 until attachSession() is called (i.e. before connect).
	private long instance = 0;
	private Bitmap bitmap;
	private int screenWidth;
	private int screenHeight;
	private int discardedMoveEvents = 0;

	// keyboard visibility flags
	private boolean sysKeyboardVisible = false;
	private AlertDialog textDialog;
	private String textDraft = "";
	private EditText textEditor;
	private Spinner pasteModePicker;
	private TextSendCoordinator textSender;
	private Runnable textSendTimeout;
	private boolean extKeyboardVisible = false;

	private final Handler handler;

	public SessionInputManager(Context context, ScrollView2D scrollView, SessionView sessionView,
	                           TouchPointerView touchPointerView, KeyboardView keyboardView,
	                           KeyboardView modifiersKeyboardView)
	{
		this.context = context;
		this.scrollView = scrollView;
		this.sessionView = sessionView;
		this.touchPointerView = touchPointerView;
		this.keyboardView = keyboardView;
		this.modifiersKeyboardView = modifiersKeyboardView;
		this.handler = new InputHandler();
		this.textSender = new TextSendCoordinator(new TextSendCoordinator.Transport() {
			@Override public boolean sendClipboard(String text, long requestId) {
				return instance != 0 && LibFreeRDP.sendClipboardDataWithId(instance, text, requestId);
			}
			@Override public boolean sendKey(int key, boolean down) {
				return instance != 0 && LibFreeRDP.sendKeyEvent(instance, key, down);
			}
		}, this::onTextSendFinished);

		this.keyboardMapper = new KeyboardMapper();
		this.keyboardMapper.init(context);

		loadKeyboards();
		keyboardView.setKeyboard(specialkeysKeyboard);
		modifiersKeyboardView.setKeyboard(modifiersKeyboard);

		keyboardView.setOnKeyboardActionListener(this);
		modifiersKeyboardView.setOnKeyboardActionListener(this);
	}

	private void loadKeyboards()
	{
		Context app = context.getApplicationContext();
		modifiersKeyboard = new Keyboard(app, R.xml.modifiers_keyboard);
		specialkeysKeyboard = new Keyboard(app, R.xml.specialkeys_keyboard);
		numpadKeyboard = new Keyboard(app, R.xml.numpad_keyboard);
		cursorKeyboard = new Keyboard(app, R.xml.cursor_keyboard);
	}

	// Binds this manager to a live FreeRDP session. Until called, all input events are dropped.
	public void attachSession(long instance, Bitmap surface)
	{
		if (this.instance != 0) LibFreeRDP.setClipboardSendListener(this.instance, null);
		cancelTextSend();
		this.instance = instance;
		LibFreeRDP.setClipboardSendListener(instance, (requestId, accepted) ->
		    handler.post(() -> {
			    if (this.instance == instance) textSender.onClipboardReady(requestId, accepted);
		    }));
		this.bitmap = surface;
		keyboardMapper.reset(this);
	}

	// Called when the session bitmap is created or replaced (OnSettingsChanged / OnGraphicsResize).
	public void setBitmap(Bitmap bitmap)
	{
		this.bitmap = bitmap;
	}

	// Returns a listener that can be wired into a ScaleGestureDetector for pinch-to-zoom.
	public ScaleGestureDetector.OnScaleGestureListener getPinchZoomListener()
	{
		return pinchZoomListener;
	}

	// Called once the screen dimensions are known (onGlobalLayout) and on bindSession.
	public void setScreenSize(int width, int height)
	{
		this.screenWidth = width;
		this.screenHeight = height;
	}

	// Called from onConfigurationChanged when keyboard resources need to be reloaded
	// (e.g. after orientation change).
	public void reloadKeyboards()
	{
		loadKeyboards();
		keyboardView.setKeyboard(specialkeysKeyboard);
		modifiersKeyboardView.setKeyboard(modifiersKeyboard);
	}

	// IME composition and dictation stay local until the user explicitly sends the draft.
	public void toggleSystemKeyboard()
	{
		if (textDialog != null) return;
		showKeyboard(false, false);
		final LinearLayout layout = new LinearLayout(context);
		layout.setOrientation(LinearLayout.VERTICAL);
		int padding = (int)(20 * context.getResources().getDisplayMetrics().density);
		layout.setPadding(padding, 0, padding, 0);
		final EditText editor = new EditText(context);
		editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
		                    | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
		editor.setMinLines(4);
		editor.setMaxLines(8);
		editor.setGravity(android.view.Gravity.TOP);
		editor.setText(textDraft);
		editor.setSelection(editor.length());
		editor.setHint("Gõ, dán hoặc dùng micro trên bàn phím để nhập tiếng Việt");
		layout.addView(editor, new LinearLayout.LayoutParams(-1, -2));
		TextView label = new TextView(context);
		label.setText("Dán vào ứng dụng đang chọn trên máy remote:");
		layout.addView(label);
		final Spinner picker = new Spinner(context);
		ArrayAdapter<TextSendCoordinator.PasteMode> modes = new ArrayAdapter<>(context,
		    android.R.layout.simple_spinner_item, TextSendCoordinator.PasteMode.values());
		modes.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		picker.setAdapter(modes);
		String savedMode = context.getSharedPreferences("remote_text_input", Context.MODE_PRIVATE)
		    .getString("paste_mode", TextSendCoordinator.PasteMode.TERMINAL.name());
		try { picker.setSelection(TextSendCoordinator.PasteMode.valueOf(savedMode).ordinal()); }
		catch (IllegalArgumentException ignored) { picker.setSelection(0); }
		layout.addView(picker);
		final AlertDialog dialog = new AlertDialog.Builder(context)
		    .setTitle("Soạn văn bản")
		    .setView(layout)
		    .setPositiveButton("Gửi", null)
		    .setNegativeButton("Đóng", null)
		    .create();
		textDialog = dialog;
		textEditor = editor;
		pasteModePicker = picker;
		dialog.setOnDismissListener(ignored -> {
			textDraft = editor.getText().toString();
			cancelTextSend();
			InputMethodManager ime =
			    (InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE);
			ime.hideSoftInputFromWindow(editor.getWindowToken(), 0);
			textDialog = null;
			textEditor = null;
			pasteModePicker = null;
		});
		// Only tapping the editor opens the IME.
		dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
		                                  | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
		dialog.setOnShowListener(ignored -> {
			editor.addTextChangedListener(new android.text.TextWatcher() {
				public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
				public void onTextChanged(CharSequence s, int start, int before, int count) {
					dialog.getButton(AlertDialog.BUTTON_POSITIVE)
					    .setEnabled(s.length() > 0 && !textSender.isPending());
				}
				public void afterTextChanged(android.text.Editable s) {}
			});
			dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(editor.length() > 0);
			dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
				textDraft = editor.getText().toString();
				if (textSender.isPending() || textDraft.isEmpty()) return;
				if (instance == 0) {
					Toast.makeText(context, "Chưa kết nối máy remote.", Toast.LENGTH_LONG).show();
					return;
				}
				TextSendCoordinator.PasteMode mode =
				    (TextSendCoordinator.PasteMode)picker.getSelectedItem();
				context.getSharedPreferences("remote_text_input", Context.MODE_PRIVATE).edit()
				    .putString("paste_mode", mode.name()).apply();
				keyboardMapper.reset(this);
				setTextSendBusy(true);
				long requestId = textSender.begin(textDraft, mode);
				if (requestId != 0) {
					textSendTimeout = () -> textSender.timeout(requestId);
					handler.postDelayed(textSendTimeout, 10000);
				}
			});
		});
		dialog.show();
	}

	private void setTextSendBusy(boolean busy)
	{
		if (textDialog == null) return;
		textEditor.setEnabled(!busy);
		pasteModePicker.setEnabled(!busy);
		textDialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(busy ? "Đang gửi…" : "Gửi");
		textDialog.getButton(AlertDialog.BUTTON_POSITIVE)
		    .setEnabled(!busy && textEditor.length() > 0);
	}

	private void cancelTextSend()
	{
		textSender.cancel();
		if (textSendTimeout != null) handler.removeCallbacks(textSendTimeout);
		textSendTimeout = null;
	}

	private void onTextSendFinished(TextSendCoordinator.Result result)
	{
		if (textSendTimeout != null) handler.removeCallbacks(textSendTimeout);
		textSendTimeout = null;
		if (result == TextSendCoordinator.Result.SENT) {
			if (textDialog != null) textDialog.dismiss();
			return;
		}
		setTextSendBusy(false);
		String message;
		switch (result) {
			case TIMEOUT:
				message = "Remote chưa xác nhận clipboard. Chưa dán; bản nháp vẫn được giữ.";
				break;
			case CLIPBOARD_CHANGED:
				message = "Clipboard trên remote đã thay đổi. Chưa dán; hãy gửi lại.";
				break;
			case KEY_FAILED:
				message = "Không gửi đủ thao tác dán. Kiểm tra remote trước khi gửi lại.";
				break;
			default:
				message = "Không chuyển được văn bản. Kiểm tra clipboard RDP rồi gửi lại.";
		}
		Toast.makeText(context, message, Toast.LENGTH_LONG).show();
	}

	public boolean isTextSendPending() { return textSender.isPending(); }

	public void onRemoteClipboardReplaced()
	{
		handler.post(() -> textSender.clipboardChanged());
	}

	// Toggles the extended (special keys / function / numpad / cursor) keyboard.
	public void toggleExtendedKeyboard()
	{
		showKeyboard(false, !extKeyboardVisible);
	}

	// Hides any visible keyboards (called from onPause and back-press handling).
	public void hideKeyboards()
	{
		if (textDialog != null) textDialog.dismiss();
		showKeyboard(false, false);
	}

	// True if either the system or extended keyboard is currently shown.
	public boolean isAnyKeyboardVisible()
	{
		return textDialog != null || sysKeyboardVisible || extKeyboardVisible;
	}

	// displays either the system or the extended keyboard or none of them
	private void showKeyboard(boolean showSystemKeyboard, boolean showExtendedKeyboard)
	{
		if (showSystemKeyboard)
		{
			// hide extended keyboard
			keyboardView.setVisibility(View.GONE);
			// show system keyboard
			setSoftInputState(true);

			// show modifiers keyboard
			modifiersKeyboardView.setVisibility(View.VISIBLE);
		}
		else if (showExtendedKeyboard)
		{
			// hide system keyboard
			setSoftInputState(false);

			// show extended keyboard
			keyboardView.setKeyboard(specialkeysKeyboard);
			keyboardView.setVisibility(View.VISIBLE);
			modifiersKeyboardView.setVisibility(View.VISIBLE);
		}
		else
		{
			// hide both
			setSoftInputState(false);
			keyboardView.setVisibility(View.GONE);
			modifiersKeyboardView.setVisibility(View.GONE);

			// clear any active key modifiers
			keyboardMapper.clearlAllModifiers();
		}

		sysKeyboardVisible = showSystemKeyboard;
		extKeyboardVisible = showExtendedKeyboard;
	}

	private void setSoftInputState(boolean state)
	{
		InputMethodManager mgr =
		    (InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE);

		if (state)
		{
			sessionView.requestFocus();
			mgr.showSoftInput(sessionView, InputMethodManager.SHOW_IMPLICIT);
		}
		else
		{
			mgr.hideSoftInputFromWindow(sessionView.getWindowToken(), 0);
		}
	}

	// Cancels any pending delayed-move events; called on connection failure / disconnect.
	public void cancelPendingEvents()
	{
		handler.removeMessages(MSG_SEND_MOVE_EVENT);
		cancelTextSend();
		if (instance != 0) LibFreeRDP.setClipboardSendListener(instance, null);
		instance = 0;
		if (textDialog != null) textDialog.dismiss();
	}

	// Forwards a physical-mouse scroll event (e.g. external mouse wheel) into the session.
	public boolean onGenericMotionEvent(MotionEvent e)
	{
		if (instance == 0)
			return false;
		if (e.getAction() != MotionEvent.ACTION_SCROLL)
			return false;

		final float vScroll = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
		if (vScroll < 0)
			LibFreeRDP.sendCursorEvent(instance, 0, 0, Mouse.getScrollEvent(context, false));
		else if (vScroll > 0)
			LibFreeRDP.sendCursorEvent(instance, 0, 0, Mouse.getScrollEvent(context, true));
		return true;
	}

	// Forwards an Android hardware-keyboard event into the session.
	public boolean onAndroidKeyEvent(KeyEvent event)
	{
		if (instance == 0)
			return false;
		return keyboardMapper.processAndroidKeyEvent(event);
	}

	// Handles a long-press on the BACK key by disconnecting the active session.
	// Returns true if the event was consumed.
	public boolean onAndroidKeyLongPress(int keyCode)
	{
		if (instance == 0)
			return false;
		if (keyCode == KeyEvent.KEYCODE_BACK)
		{
			LibFreeRDP.disconnect(instance);
			return true;
		}
		return false;
	}

	// If the "use back as Alt+F4" preference is enabled, sends Alt+F4 and returns true.
	public boolean handleBackAsAltF4()
	{
		if (instance == 0)
			return false;
		if (!ApplicationSettingsActivity.getUseBackAsAltf4(context))
			return false;
		keyboardMapper.sendAltF4();
		return true;
	}

	// Toggles touch-pointer overlay visibility (driven by the menu).
	public void toggleTouchPointer()
	{
		if (touchPointerView.getVisibility() == View.VISIBLE)
		{
			touchPointerView.setVisibility(View.INVISIBLE);
			sessionView.setTouchPointerPadding(0, 0);
		}
		else
		{
			touchPointerView.setVisibility(View.VISIBLE);
			sessionView.setTouchPointerPadding(touchPointerView.getPointerWidth(),
			                                   touchPointerView.getPointerHeight());
		}
	}

	// ****************************************************************************
	// Private helpers

	private void sendDelayedMoveEvent(int x, int y)
	{
		if (handler.hasMessages(MSG_SEND_MOVE_EVENT))
		{
			handler.removeMessages(MSG_SEND_MOVE_EVENT);
			discardedMoveEvents++;
		}
		else
			discardedMoveEvents = 0;

		if (discardedMoveEvents > MAX_DISCARDED_MOVE_EVENTS)
			LibFreeRDP.sendCursorEvent(instance, x, y, Mouse.getMoveEvent());
		else
			handler.sendMessageDelayed(Message.obtain(null, MSG_SEND_MOVE_EVENT, x, y),
			                           SEND_MOVE_EVENT_TIMEOUT);
	}

	private void cancelDelayedMoveEvent()
	{
		handler.removeMessages(MSG_SEND_MOVE_EVENT);
	}

	private Point mapScreenCoordToSessionCoord(int x, int y)
	{
		int usableW =
		    scrollView.getWidth() - scrollView.getPaddingLeft() - scrollView.getPaddingRight();
		int usableH =
		    scrollView.getHeight() - scrollView.getPaddingTop() - scrollView.getPaddingBottom();
		int contentW = sessionView.getWidth() - sessionView.getTouchPointerPaddingWidth();
		int contentH = sessionView.getHeight() - sessionView.getTouchPointerPaddingHeight();
		int centerOffsetX = Math.max(0, (usableW - contentW) / 2);
		int centerOffsetY = Math.max(0, (usableH - contentH) / 2);
		int mappedX = (int)((float)(x - safeInsetLeft - centerOffsetX + scrollView.getScrollX()) /
		                    sessionView.getZoom());
		int mappedY = (int)((float)(y - safeInsetTop - centerOffsetY + scrollView.getScrollY()) /
		                    sessionView.getZoom());
		if (bitmap != null)
		{
			if (mappedX < 0)
				mappedX = 0;
			if (mappedY < 0)
				mappedY = 0;
			if (mappedX > bitmap.getWidth())
				mappedX = bitmap.getWidth();
			if (mappedY > bitmap.getHeight())
				mappedY = bitmap.getHeight();
		}
		return new Point(mappedX, mappedY);
	}

	private void updateModifierKeyStates()
	{
		List<Keyboard.Key> keys = modifiersKeyboard.getKeys();
		for (Keyboard.Key curKey : keys)
		{
			if (curKey.sticky)
			{
				switch (keyboardMapper.getModifierState(curKey.codes[0]))
				{
					case KeyboardMapper.KEYSTATE_ON:
						curKey.on = true;
						curKey.pressed = false;
						break;

					case KeyboardMapper.KEYSTATE_OFF:
						curKey.on = false;
						curKey.pressed = false;
						break;

					case KeyboardMapper.KEYSTATE_LOCKED:
						curKey.on = true;
						curKey.pressed = true;
						break;
				}
			}
		}
		modifiersKeyboardView.invalidateAllKeys();
	}

	// ****************************************************************************
	// SessionView.SessionViewListener

	@Override public void onSessionViewBeginTouch()
	{
		scrollView.setScrollEnabled(false);
	}

	@Override public void onSessionViewEndTouch()
	{
		scrollView.setScrollEnabled(true);
	}

	@Override public void onSessionViewLeftTouch(int x, int y, boolean down)
	{
		if (instance == 0)
			return;
		if (!down)
			cancelDelayedMoveEvent();
		LibFreeRDP.sendCursorEvent(instance, x, y, Mouse.getLeftButtonEvent(context, down));
	}

	@Override public void onSessionViewMiddleTouch(int x, int y, boolean down)
	{
		if (instance == 0)
			return;
		LibFreeRDP.sendCursorEvent(instance, x, y, Mouse.getMiddleButtonEvent(down));
	}

	@Override public void onSessionViewRightTouch(int x, int y, boolean down)
	{
		if (instance == 0)
			return;
		LibFreeRDP.sendCursorEvent(instance, x, y, Mouse.getRightButtonEvent(context, down));
	}

	@Override public void onSessionViewMove(int x, int y)
	{
		if (instance == 0)
			return;
		sendDelayedMoveEvent(x, y);
	}

	@Override public void onSessionViewMouseMove(int x, int y)
	{
		if (instance == 0)
			return;
		LibFreeRDP.sendCursorEvent(instance, x, y, Mouse.getMoveEvent());
	}

	@Override public void onSessionViewScroll(boolean down)
	{
		if (instance == 0)
			return;
		LibFreeRDP.sendCursorEvent(instance, 0, 0, Mouse.getScrollEvent(context, down));
	}

	@Override public void onSessionViewHScroll(boolean right)
	{
		if (instance == 0)
			return;
		LibFreeRDP.sendCursorEvent(instance, 0, 0, Mouse.getHScrollEvent(context, right));
	}

	// ****************************************************************************
	// TouchPointerView.TouchPointerListener

	@Override public void onTouchPointerClose()
	{
		touchPointerView.setVisibility(View.INVISIBLE);
		sessionView.setTouchPointerPadding(0, 0);
	}

	@Override public void onTouchPointerLeftClick(int x, int y, boolean down)
	{
		if (instance == 0)
			return;
		Point p = mapScreenCoordToSessionCoord(x, y);
		LibFreeRDP.sendCursorEvent(instance, p.x, p.y, Mouse.getLeftButtonEvent(context, down));
	}

	@Override public void onTouchPointerRightClick(int x, int y, boolean down)
	{
		if (instance == 0)
			return;
		Point p = mapScreenCoordToSessionCoord(x, y);
		LibFreeRDP.sendCursorEvent(instance, p.x, p.y, Mouse.getRightButtonEvent(context, down));
	}

	@Override public void onTouchPointerMove(int x, int y)
	{
		if (instance == 0)
			return;
		Point p = mapScreenCoordToSessionCoord(x, y);
		LibFreeRDP.sendCursorEvent(instance, p.x, p.y, Mouse.getMoveEvent());

		if (ApplicationSettingsActivity.getAutoScrollTouchPointer(context) &&
		    !handler.hasMessages(MSG_SCROLLING_REQUESTED))
		{
			handler.sendEmptyMessageDelayed(MSG_SCROLLING_REQUESTED, SCROLLING_TIMEOUT);
		}
	}

	@Override public void onTouchPointerMoveEnd()
	{
		handler.removeMessages(MSG_SCROLLING_REQUESTED);
	}

	@Override public void onTouchPointerScroll(boolean down)
	{
		if (instance == 0)
			return;
		LibFreeRDP.sendCursorEvent(instance, 0, 0, Mouse.getScrollEvent(context, down));
	}

	@Override public void onTouchPointerToggleKeyboard()
	{
		toggleSystemKeyboard();
	}

	@Override public void onTouchPointerToggleExtKeyboard()
	{
		toggleExtendedKeyboard();
	}

	@Override public void onTouchPointerResetScrollZoom()
	{
		sessionView.setZoom(1.0f);
		scrollView.scrollTo(0, 0);
	}

	// ****************************************************************************
	// KeyboardMapper.KeyProcessingListener

	@Override public void processVirtualKey(int virtualKeyCode, boolean down)
	{
		if (instance == 0)
			return;
		LibFreeRDP.sendKeyEvent(instance, virtualKeyCode, down);
	}

	@Override public void processUnicodeKey(int unicodeKey)
	{
		if (instance == 0)
			return;
		if (LibFreeRDP.isUnicodeInputSupported(instance))
		{
			LibFreeRDP.sendUnicodeKeyEvent(instance, unicodeKey, true);
			LibFreeRDP.sendUnicodeKeyEvent(instance, unicodeKey, false);
		}
		else
			keyboardMapper.processUnicodeFallback(unicodeKey);
	}

	@Override public void switchKeyboard(int keyboardType)
	{
		switch (keyboardType)
		{
			case KeyboardMapper.KEYBOARD_TYPE_FUNCTIONKEYS:
				keyboardView.setKeyboard(specialkeysKeyboard);
				break;

			case KeyboardMapper.KEYBOARD_TYPE_NUMPAD:
				keyboardView.setKeyboard(numpadKeyboard);
				break;

			case KeyboardMapper.KEYBOARD_TYPE_CURSOR:
				keyboardView.setKeyboard(cursorKeyboard);
				break;

			default:
				break;
		}
	}

	@Override public void modifiersChanged()
	{
		updateModifierKeyStates();
	}

	// ****************************************************************************
	// KeyboardView.OnKeyboardActionListener (extended/modifiers keyboards)

	@Override public void onKey(int primaryCode, int[] keyCodes)
	{
		keyboardMapper.processCustomKeyEvent(primaryCode);
	}

	@Override public void onText(CharSequence text)
	{
	}

	@Override public void swipeRight()
	{
	}

	@Override public void swipeLeft()
	{
	}

	@Override public void swipeDown()
	{
	}

	@Override public void swipeUp()
	{
	}

	@Override public void onPress(int primaryCode)
	{
	}

	@Override public void onRelease(int primaryCode)
	{
	}

	// ****************************************************************************
	// Internal delayed-event handler

	private class InputHandler extends Handler
	{
		InputHandler()
		{
			super(Looper.getMainLooper());
		}

		@Override public void handleMessage(Message msg)
		{
			switch (msg.what)
			{
				case MSG_SEND_MOVE_EVENT:
					if (instance == 0)
						break;
					LibFreeRDP.sendCursorEvent(instance, msg.arg1, msg.arg2, Mouse.getMoveEvent());
					break;

				case MSG_SCROLLING_REQUESTED:
				{
					int scrollX = 0;
					int scrollY = 0;
					float[] pointerPos = touchPointerView.getPointerPosition();
					final int ow = touchPointerView.getWidth();
					final int oh = touchPointerView.getHeight();
					final int pw = touchPointerView.getPointerWidth();
					final int ph = touchPointerView.getPointerHeight();

					if (pointerPos[0] >= ow - pw - SCROLLING_EDGE_MARGIN)
						scrollX = SCROLLING_DISTANCE;
					else if (pointerPos[0] <= SCROLLING_EDGE_MARGIN)
						scrollX = -SCROLLING_DISTANCE;

					if (pointerPos[1] >= oh - ph - SCROLLING_EDGE_MARGIN)
						scrollY = SCROLLING_DISTANCE;
					else if (pointerPos[1] <= SCROLLING_EDGE_MARGIN)
						scrollY = -SCROLLING_DISTANCE;

					scrollView.scrollBy(scrollX, scrollY);

					final int maxX = sessionView.getWidth() - scrollView.getWidth();
					final int maxY = sessionView.getHeight() - scrollView.getHeight();
					if ((scrollX < 0 && scrollView.getScrollX() <= 0) ||
					    (scrollX > 0 && scrollView.getScrollX() >= maxX))
						scrollX = 0;
					if ((scrollY < 0 && scrollView.getScrollY() <= 0) ||
					    (scrollY > 0 && scrollView.getScrollY() >= maxY))
						scrollY = 0;

					if (scrollX != 0 || scrollY != 0)
						handler.sendEmptyMessageDelayed(MSG_SCROLLING_REQUESTED, SCROLLING_TIMEOUT);
					break;
				}
			}
		}
	}

	// ****************************************************************************
	// Pinch-to-zoom listener (wired into SessionView's ScaleGestureDetector)

	private class PinchZoomListener extends ScaleGestureDetector.SimpleOnScaleGestureListener
	{
		private float scaleFactor = 1.0f;

		@Override public boolean onScaleBegin(ScaleGestureDetector detector)
		{
			scrollView.setScrollEnabled(false);
			return true;
		}

		@Override public boolean onScale(ScaleGestureDetector detector)
		{
			// calc scale factor
			scaleFactor *= detector.getScaleFactor();
			scaleFactor = Math.max(SessionView.MIN_SCALE_FACTOR,
			                       Math.min(scaleFactor, SessionView.MAX_SCALE_FACTOR));
			sessionView.setZoom(scaleFactor);

			if (!sessionView.isAtMinZoom() && !sessionView.isAtMaxZoom())
			{
				// transform scroll origin to the new zoom space
				float transOriginX = scrollView.getScrollX() * detector.getScaleFactor();
				float transOriginY = scrollView.getScrollY() * detector.getScaleFactor();

				// transform center point to the zoomed space
				float transCenterX =
				    (scrollView.getScrollX() + detector.getFocusX()) * detector.getScaleFactor();
				float transCenterY =
				    (scrollView.getScrollY() + detector.getFocusY()) * detector.getScaleFactor();

				// scroll by the difference between the distance of the
				// transformed center/origin point and their old distance
				// (focusX/Y)
				scrollView.scrollBy((int)((transCenterX - transOriginX) - detector.getFocusX()),
				                    (int)((transCenterY - transOriginY) - detector.getFocusY()));
			}

			return true;
		}

		@Override public void onScaleEnd(ScaleGestureDetector de)
		{
			scrollView.setScrollEnabled(true);
		}
	}
}
