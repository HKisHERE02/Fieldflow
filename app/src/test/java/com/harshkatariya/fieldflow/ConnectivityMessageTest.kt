package com.harshkatariya.fieldflow

import com.harshkatariya.fieldflow.ui.connectivityMessage
import org.junit.Assert.*
import org.junit.Test

class ConnectivityMessageTest {
    @Test fun availableInternetDoesNotClaimBackendConnection() {
        assertEquals("NETWORK AVAILABLE  ·  Work saved locally", connectivityMessage(false, true))
        assertFalse(connectivityMessage(false, true).contains("CONNECTED"))
    }
    @Test fun offlineMessageStillConfirmsLocalStorage() {
        assertEquals("OFFLINE  ·  Your work is saved here", connectivityMessage(false, false))
    }
    @Test fun demoMessageDoesNotDependOnNetwork() {
        assertEquals("LOCAL DEMO  ·  Saved on this device", connectivityMessage(true, true))
        assertEquals(connectivityMessage(true, true), connectivityMessage(true, false))
    }
}
