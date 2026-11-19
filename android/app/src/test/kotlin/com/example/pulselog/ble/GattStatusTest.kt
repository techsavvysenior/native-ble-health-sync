package com.example.pulselog.ble

import com.example.pulselog.core.DisconnectCause
import org.junit.Assert.assertEquals
import org.junit.Test

class GattStatusTest {
    @Test
    fun `UC-04 Android disconnect status codes map to the shared causes`() {
        assertEquals(DisconnectCause.LINK_LOSS, GattStatus.cause(GattStatus.CONNECTION_TIMEOUT))
        assertEquals(DisconnectCause.LINK_LOSS, GattStatus.cause(GattStatus.PEER_TERMINATED))
        assertEquals(DisconnectCause.LINK_LOSS, GattStatus.cause(GattStatus.SUCCESS))
        assertEquals(DisconnectCause.TIMEOUT, GattStatus.cause(GattStatus.ESTABLISH_TIMEOUT))
        assertEquals(DisconnectCause.TIMEOUT, GattStatus.cause(GattStatus.FAILED_TO_ESTABLISH))
        assertEquals(DisconnectCause.GATT_ERROR, GattStatus.cause(GattStatus.GATT_ERROR))
        assertEquals(DisconnectCause.GATT_ERROR, GattStatus.cause(257))
    }
}
