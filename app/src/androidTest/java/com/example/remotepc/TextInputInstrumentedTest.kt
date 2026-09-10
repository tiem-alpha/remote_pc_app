package com.example.remotepc

import android.inputmethodservice.KeyboardView
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.freerdp.freerdpcore.presentation.ScrollView2D
import com.freerdp.freerdpcore.presentation.SessionInputManager
import com.freerdp.freerdpcore.presentation.SessionView
import com.freerdp.freerdpcore.presentation.TouchPointerView
import com.freerdp.freerdpcore.services.LibFreeRDP
import org.hamcrest.Matchers.not
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextInputInstrumentedTest {
    @Test
    fun packagedClipboardJniRejectsMissingSession() {
        // Exercises the APK's new JNI entry point, including native library loading.
        assertFalse(LibFreeRDP.sendClipboardDataWithId(0, "Tiếng Việt 😀", 1))
    }

    @Suppress("DEPRECATION")
    @Test
    fun singleSendButtonAndDraftSurviveCloseAndDisconnectedSend() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var manager: SessionInputManager
            scenario.onActivity { activity ->
                manager = SessionInputManager(
                    activity, ScrollView2D(activity), SessionView(activity),
                    TouchPointerView(activity), KeyboardView(activity, null),
                    KeyboardView(activity, null)
                )
                manager.toggleSystemKeyboard()
            }
            onView(withText("Gửi")).check(matches(not(isEnabled())))
            onView(withText("Chuyển sang remote")).check(doesNotExist())
            onView(withText("Dán (Ctrl+V)")).check(doesNotExist())
            val draft = "Tiếng Việt: Trường Nguyễn, Đặng Thị Hồng 😀\n  Dòng hai"
            onView(isAssignableFrom(EditText::class.java)).perform(replaceText(draft))
            onView(withText("Gửi")).check(matches(isEnabled()))
            onView(withText("Đóng")).perform(click())
            scenario.onActivity { manager.toggleSystemKeyboard() }
            onView(withText(draft)).check(matches(isDisplayed()))
            onView(withText("Gửi")).perform(click())
            onView(withText(draft)).check(matches(isDisplayed()))
            scenario.onActivity { manager.cancelPendingEvents() }
        }
    }
}
