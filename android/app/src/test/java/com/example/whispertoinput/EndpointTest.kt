// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.whispertoinput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointTest {
    @Test fun legacyDefaultIsMigrated() {
        assertEquals(DEFAULT_ENDPOINT, Endpoint.migrate("http://100.124.60.71:8814/inference"))
        assertEquals(DEFAULT_ENDPOINT, Endpoint.migrate(null))
        assertEquals(DEFAULT_ENDPOINT, Endpoint.migrate(""))
    }

    @Test fun customEndpointIsKept() {
        assertEquals("http://10.0.2.2:8815/inference", Endpoint.migrate(" http://10.0.2.2:8815/inference "))
    }

    @Test fun validation() {
        assertTrue(Endpoint.isValid("http://100.124.60.71:8815/inference"))
        assertTrue(Endpoint.isValid("https://desktop.tail937cd7.ts.net:8443/inference"))
        assertFalse(Endpoint.isValid("100.124.60.71:8815"))
        assertFalse(Endpoint.isValid("ftp://host/inference"))
        assertFalse(Endpoint.isValid("http://"))
        assertFalse(Endpoint.isValid("not a url"))
    }

    @Test fun healthUrlIsServerRoot() {
        assertEquals("http://100.124.60.71:8815/", Endpoint.healthUrl("http://100.124.60.71:8815/inference"))
        assertEquals("https://h.ts.net/", Endpoint.healthUrl("https://h.ts.net/v1/inference"))
        assertNull(Endpoint.healthUrl("garbage"))
    }
}
