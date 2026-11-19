package com.example.pulselog.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebaseConfigTest {
    @Test
    fun `UC-11 the app runs local-only unless all three Firebase values are set`() {
        assertFalse(FirestoreUploader.isConfigured("", "", ""))
        assertFalse(FirestoreUploader.isConfigured("demo-project", "1:123:android:abc", " "))
        assertFalse(FirestoreUploader.isConfigured("demo-project", "", "key"))
        assertTrue(FirestoreUploader.isConfigured("demo-project", "1:123:android:abc", "key"))
    }
}
