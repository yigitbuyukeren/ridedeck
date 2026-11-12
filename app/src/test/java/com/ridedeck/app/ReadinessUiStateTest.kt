package com.ridedeck.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.ridedeck.app.ui.ReadinessUiState

/** Pure gate used by the Dashboard for motion/open/action/close; no Android state is involved. */
class ReadinessUiStateTest {
    @Test fun motionClosesAnOpenPanelAndBlocksOpenAndSettingsActions() {
        val closed = ReadinessUiState()
        assertFalse(closed.requestOpen(inputLocked = true).open)
        assertFalse(closed.allowsSettingsAction(inputLocked = false))

        val open = closed.requestOpen(inputLocked = false)
        assertTrue(open.open)
        assertTrue(open.allowsSettingsAction(inputLocked = false))
        assertFalse(open.allowsSettingsAction(inputLocked = true))
        assertFalse("locked reopen must fail closed", open.requestOpen(inputLocked = true).open)

        val afterMotion = open.afterMotion(inputLocked = true)
        assertFalse(afterMotion.open)
        assertFalse(afterMotion.allowsSettingsAction(inputLocked = true))
    }

    @Test fun closeDoesNotReopen() {
        val open = ReadinessUiState().requestOpen(inputLocked = false)
        assertTrue(open.open)
        assertFalse(open.dismiss().open)
        assertFalse(open.afterMotion(inputLocked = false).dismiss().open)
    }
}
