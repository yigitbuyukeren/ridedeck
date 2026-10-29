package com.ridedeck.app

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.ridedeck.app.data.PhoneRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

@SdkSuppress(minSdkVersion = 29)
class PhonePermissionTest {
    @Test fun revokingContactPermissionClearsPreviouslyDisplayedFakeContacts() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        var granted = true
        var queries = 0
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor {
                queries++
                return MatrixCursor(arrayOf("display_name", "data1", "starred")).apply { addRow(arrayOf("Fake contact", "15550000001", 0)) }
            }
            override fun getType(uri: Uri): String? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
        }
        provider.attachInfo(base, ProviderInfo().apply { authority = "com.android.contacts" })
        val fakeResolver = ContentResolver.wrap(provider)
        val context = object : ContextWrapper(base) {
            override fun getContentResolver(): ContentResolver = fakeResolver
            override fun checkSelfPermission(permission: String): Int = if (permission == Manifest.permission.READ_CONTACTS && granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val repo = PhoneRepository(context, scope)
            repo.refresh()
            withTimeout(5_000) { repo.contacts.first { it.isNotEmpty() } }
            assertTrue(queries > 0)
            assertEquals("Fake contact", repo.contacts.value.single().name)
            granted = false; repo.refresh()
            assertTrue(repo.contacts.value.isEmpty())
            assertTrue(repo.recents.value.isEmpty())
        } finally { scope.cancel() }
    }
}
