package com.bks.feature.account

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InstallationIdStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun randomInstallationUuidPersistsAcrossStoreRecreation() {
        context.getSharedPreferences("account_installation", Context.MODE_PRIVATE)
            .edit().clear().commit()

        val first = InstallationIdStore(context).getOrCreate()
        val second = InstallationIdStore(context).getOrCreate()

        assertEquals(first, second)
        assertEquals(first, UUID.fromString(first).toString())
        assertNotEquals(android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ), first)
    }
}
