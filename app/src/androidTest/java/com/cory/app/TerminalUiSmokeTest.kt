package com.cory.app

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase-1 instrumentation smoke test — the durable answer to the
 * "no test caught the black-screen bug" problem.
 *
 * This test deliberately does NOT introspect the Compose tree. The
 * terminal renders into a SurfaceView, whose pixels are not visible
 * to Compose UI test APIs. Instead we rely on UiAutomation to take
 * a real screenshot of the device after launching the activity and
 * we count distinct pixel colors in it.
 *
 * Rationale: the bugs the user hit on v0.0.10 all manifested as
 * "the screen is one solid color" (black, or theme-bg cream). A
 * working terminal has at least dozens of distinct colors (text
 * pixels, anti-aliasing artifacts, cursor, status bar, prompt
 * coloring, etc.). Counting distinct colors is a crude but very
 * effective lie detector — it catches:
 *   - Surface { } not filling its parent (everything is the
 *     theme bg color)
 *   - SurfaceView returning 0×0 from initial measurement (the
 *     surface never composites)
 *   - Activity crashes during onCreate (Android shows the launcher
 *     or a system error screen, both have very few colors)
 *   - JNI loadLibrary failures (loading screen never advances)
 *
 * It does NOT catch logic bugs in commands (those need phase 2 —
 * actually typing input and reading output, which requires solving
 * the SurfaceView introspection problem).
 *
 * Threshold of 50 distinct colors is empirical — a working terminal
 * with text rendering produces hundreds. A solid color gives 1. A
 * launcher with a few icons gives ~30. 50 is well above the noise
 * floor and well below a real terminal.
 */
@RunWith(AndroidJUnit4::class)
class TerminalUiSmokeTest {

    private lateinit var device: UiDevice

    @Before
    fun setup() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    }

    @Test
    fun activityLaunchesAndScreenIsNotASolidVoid() {
        // Cold-launch the app via package manager.
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = ctx.packageManager
            .getLaunchIntentForPackage("com.cory.app")
            ?.apply { addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK or android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
        assertNotNull("no launch intent for com.cory.app", intent)
        ctx.startActivity(intent!!)

        // Wait for the package to be in the foreground. Generous
        // timeout because first launch on a Device Farm device runs
        // CoryTerminalRuntime.ensureReady() which extracts ~100 MB
        // of assets to filesDir on the very first invocation.
        val foreground = device.wait(
            Until.hasObject(By.pkg("com.cory.app").depth(0)),
            60_000L
        )
        assertTrue("com.cory.app never reached foreground within 60s", foreground)

        // Give the terminal a few seconds after activity foreground
        // to: (a) finish ensureReady, (b) spawn bash via PTY, (c)
        // draw the first frame on the SurfaceView. Without this the
        // screenshot might capture mid-transition.
        SystemClock.sleep(8_000L)
        device.waitForIdle(2_000L)

        // Take a real device screenshot via UiAutomation.
        val screenshot = InstrumentationRegistry
            .getInstrumentation()
            .uiAutomation
            .takeScreenshot()
        assertNotNull("UiAutomation.takeScreenshot returned null", screenshot)

        val distinct = countDistinctColors(screenshot!!)
        screenshot.recycle()

        // 50 is well above the "solid color" floor (1) and well
        // below what a working terminal produces (hundreds).
        assertTrue(
            "screen has only $distinct distinct colors — looks like " +
                "a solid void. Either the activity crashed, the Compose " +
                "Surface didn't fill its parent, the SurfaceView never " +
                "got measured, or the terminal failed to draw its first " +
                "frame. Check logcat for TerminalBootstrap, " +
                "CoryTerminalRuntime, AndroidRuntime, and SurfaceView.",
            distinct >= 50
        )
    }

    /**
     * Count distinct ARGB int colors in the bitmap. We sample every
     * pixel — bitmaps for a phone screen are ~1080×2400 ≈ 2.6M
     * pixels which is fast enough to brute-force.
     */
    private fun countDistinctColors(bmp: Bitmap): Int {
        val w = bmp.width
        val h = bmp.height
        if (w == 0 || h == 0) return 0
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val seen = HashSet<Int>(1024)
        for (p in pixels) {
            seen.add(p)
            // Early-out once we're confident the screen isn't blank;
            // no need to count millions of unique colors precisely.
            if (seen.size > 200) return seen.size
        }
        return seen.size
    }
}
