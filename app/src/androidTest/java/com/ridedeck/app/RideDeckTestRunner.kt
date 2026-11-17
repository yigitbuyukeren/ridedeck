package com.ridedeck.app

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/**
 * AndroidJUnitRunner that ends the instrumentation process explicitly after reporting results.
 *
 * Several AndroidX/Compose test hosts leave a main looper (and occasionally a test activity)
 * alive after the last test. `am instrument -w` then waits for the process pipe forever even
 * though the suite is green, which is what made the 170+ test run look like a crash/hang. The
 * results are delivered by `super.finish` before this exit; a real crash still bypasses it.
 */
class RideDeckTestRunner : AndroidJUnitRunner() {
    override fun finish(resultCode: Int, results: Bundle?) {
        super.finish(resultCode, results)
        System.exit(0)
    }
}
