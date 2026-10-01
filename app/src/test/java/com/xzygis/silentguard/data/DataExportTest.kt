package com.xzygis.silentguard.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DataExportTest {
    @Test fun `SMS exports one JSON line with lossless text and explicit null coordinates`() {
        val text = "短信 \"引号\" \\路径\n第二行\r\n\u0001"
        val event = MonitorEvent(id = 42, type = EventType.SMS, title = "通知",
            summary = "摘要", detail = text, timestamp = 123)
        val encoded = DataExport.encode(event)
        assertFalse(encoded.contains('\n'))
        val json = JSONObject(encoded)
        assertEquals(text, json.getString("detail"))
        assertEquals(42L, json.getLong("id"))
        assertEquals(123L, json.getLong("timestamp"))
        assertEquals("SMS", json.getString("type"))
        assertEquals("PENDING", json.getString("status"))
        assertEquals("通知", json.getString("title"))
        assertEquals("摘要", json.getString("summary"))
        listOf("latitude", "longitude", "accuracy").forEach {
            assertTrue(json.has(it))
            assertTrue(json.isNull(it))
        }
    }

    @Test fun `location exports precise numeric coordinates and delivery status`() {
        val event = MonitorEvent(type = EventType.LOCATION, title = "point", summary = "",
            latitude = 39.123456, longitude = 116.654321, accuracy = 12.5f, status = EventStatus.SENT)
        val json = JSONObject(DataExport.encode(event))
        assertEquals(event.latitude!!, json.getDouble("latitude"), 0.0)
        assertEquals(event.longitude!!, json.getDouble("longitude"), 0.0)
        assertEquals(12.5, json.getDouble("accuracy"), 0.0)
        assertEquals("SENT", json.getString("status"))
        assertEquals(10, json.length())
    }
}
