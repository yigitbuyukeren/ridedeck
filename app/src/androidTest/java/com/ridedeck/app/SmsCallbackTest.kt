package com.ridedeck.app

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.telephony.SmsManager
import androidx.test.platform.app.InstrumentationRegistry
import com.ridedeck.app.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Only fake transport is used: these tests cannot submit SMS to SmsManager or a SIM. */
class SmsCallbackTest {
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
    private class TrackedContext(base: Context) : ContextWrapper(base) {
        val registered = mutableSetOf<BroadcastReceiver>()
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter, flags: Int): Intent? {
            receiver?.let { registered += it }; return super.registerReceiver(receiver, filter, flags)
        }
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter, permission: String?, handler: Handler?, flags: Int): Intent? {
            receiver?.let { registered += it }; return super.registerReceiver(receiver, filter, permission, handler, flags)
        }
        override fun unregisterReceiver(receiver: BroadcastReceiver) { registered -= receiver; super.unregisterReceiver(receiver) }
    }
    private class FakeGateway(val submitter: (String, ArrayList<PendingIntent>) -> Unit) : SmsGateway {
        var submits = 0
        val callbacks = mutableListOf<PendingIntent>()
        override fun parts(text: String) = arrayListOf("part one", "part two")
        override fun submit(number: String, parts: ArrayList<String>, sent: ArrayList<PendingIntent>) {
            submits++; callbacks += sent; submitter(number, sent)
        }
    }

    @Test fun modemCallbacksConfirmEveryPartAndKeepPerContactFailuresSeparate() = runBlocking {
        val context = TrackedContext(base)
        val fake = FakeGateway { number, callbacks ->
            callbacks.reversed().forEach { it.send(context, if (number == "15550000001") Activity.RESULT_OK else SmsManager.RESULT_ERROR_NO_SERVICE, null) }
        }
        val sender = AndroidSmsSender(context, { fake }, { true }, 5_000)
        val result = sender.send(listOf("15550000001", "15550000002"), "test only")
        assertEquals(mapOf("15550000001" to SmsStatus.SENT, "15550000002" to SmsStatus.FAILED), result.recipients)
        assertEquals(2, fake.submits)
        assertTrue(context.registered.isEmpty())
    }

    @Test fun missingCallbacksAreUnknownAndPendingIntentsAreCancelledWithoutRetries() = runBlocking {
        val context = TrackedContext(base)
        val fake = FakeGateway { _, _ -> }
        val result = AndroidSmsSender(context, { fake }, { true }, 100).send(listOf("15550000001"), "test only")
        assertEquals(SmsStatus.UNKNOWN, result.recipients["15550000001"])
        assertEquals(1, fake.submits)
        assertTrue(context.registered.isEmpty())
        try { fake.callbacks.first().send(); fail("late callback must be cancelled") } catch (_: PendingIntent.CanceledException) {}
    }

    @Test fun permissionLossBetweenRecipientsDoesNotDispatchToTheSecondContact() = runBlocking {
        val context = TrackedContext(base)
        var allowed = true
        val fake = FakeGateway { _, callbacks -> callbacks.forEach { it.send(context, Activity.RESULT_OK, null) }; allowed = false }
        val result = AndroidSmsSender(context, { fake }, { allowed }, 5_000).send(listOf("15550000001", "15550000002"), "test only")
        assertEquals(1, fake.submits)
        assertEquals(SmsProblem.PERMISSION, result.problem)
        assertEquals(SmsStatus.SENT, result.recipients["15550000001"])
        assertEquals(SmsStatus.FAILED, result.recipients["15550000002"])
        assertTrue(context.registered.isEmpty())
    }

    @Test fun deniedPermissionNeverEvenConstructsTheTransport() = runBlocking {
        var constructed = false
        val fake = FakeGateway { _, _ -> fail("must never dispatch") }
        val result = AndroidSmsSender(base, { constructed = true; fake }, { false }).send(listOf("15550000001"), "test only")
        assertFalse(constructed)
        assertEquals(SmsProblem.PERMISSION, result.problem)
    }

    @Test fun cancellationAlwaysReleasesReceiverAndCannotRetryTheMessage() = runBlocking {
        val context = TrackedContext(base)
        val submitted = CompletableDeferred<Unit>()
        val fake = FakeGateway { _, _ -> submitted.complete(Unit) }
        val job = launch { AndroidSmsSender(context, { fake }, { true }).send(listOf("15550000001"), "test only") }
        submitted.await(); job.cancelAndJoin()
        assertTrue(context.registered.isEmpty())
        assertEquals(1, fake.submits)
        try { fake.callbacks.last().send(); fail("cancelled attempt must not accept callbacks") } catch (_: PendingIntent.CanceledException) {}
    }
}
