package de.wandern.app.ui

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.wandern.app.R
import org.junit.Test
import org.junit.runner.RunWith

/** Covers the prompt that offers to finish the tour once the hiker stands at the destination. */
@RunWith(AndroidJUnit4::class)
class ArrivalFinishPromptFlowTest {
    @Test
    fun arrivalAtDestinationOffersToFinishTheTour() {
        launchArrivalScene().use {
            awaitDisplayed(R.string.arrival_finish_title)
            onView(withText(R.string.arrival_finish_message)).check(matches(isDisplayed()))
            onView(withText(R.string.arrival_finish_tour)).check(matches(isDisplayed()))
            onView(withText(R.string.arrival_keep_recording)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun keepingTheRecordingDoesNotPromptAgainForTheSameSession() {
        launchArrivalScene().use { scenario ->
            awaitDisplayed(R.string.arrival_finish_title)
            onView(withText(R.string.arrival_keep_recording)).perform(click())
            awaitGone(R.string.arrival_finish_title)
            // Coming back to the foreground re-checks the arrival state. The same recording
            // session must not ask a second time.
            scenario.onActivity { activity -> activity.onWindowFocusChanged(true) }
            onView(withText(R.string.arrival_finish_title)).check(doesNotExist())
        }
    }

    private fun launchArrivalScene(): ActivityScenario<MainActivity> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_DEBUG_SCENARIO)
            .putExtra(MainActivity.EXTRA_DEBUG_SCENARIO, "route-arrived-collapsed")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return ActivityScenario.launch(intent)
    }

    private fun awaitDisplayed(textRes: Int) = await("Ankunftsdialog wurde nicht angezeigt") {
        onView(withText(textRes)).check(matches(isDisplayed()))
    }

    private fun awaitGone(textRes: Int) = await("Ankunftsdialog wurde nicht geschlossen") {
        onView(withText(textRes)).check(doesNotExist())
    }

    private fun await(message: String, assertion: () -> Unit) {
        var lastFailure: Throwable? = null
        repeat(ATTEMPTS) {
            try {
                assertion()
                return
            } catch (failure: Throwable) {
                lastFailure = failure
                SystemClock.sleep(ATTEMPT_DELAY_MILLIS)
            }
        }
        throw AssertionError("$message: ${lastFailure?.message}")
    }

    private companion object {
        const val ATTEMPTS = 50
        const val ATTEMPT_DELAY_MILLIS = 100L
    }
}
