package com.moscilez.track.ui

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moscilez.track.MainActivity
import com.moscilez.track.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    /** A fresh install lands on onboarding; a configured one lands on the Home tab. */
    @Test
    fun launchesIntoOnboardingOrHome() {
        val skip = rule.activity.getString(R.string.onb_skip)
        val home = rule.activity.getString(R.string.tab_home)
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasText(skip)).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodes(hasText(home)).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
