package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.bridge.ShivoidDeviceHelper
import com.example.data.ShivoidPreferences
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context matches SHI V01D and developer`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        val developerName = context.getString(R.string.developer_name)
        assertEquals("SHI.V01D", appName)
        assertEquals("SHIVANSH THAKUR", developerName)
    }

    @Test
    fun `preferences stores and retrieves last url`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = ShivoidPreferences(context)

        val testUrl = "https://custom.shivoid.app/dashboard"
        prefs.lastUrl = testUrl
        assertEquals(testUrl, prefs.lastUrl)

        val bookmarks = prefs.getBookmarks()
        assertTrue(bookmarks.isNotEmpty())
    }

    @Test
    fun `device helper produces valid json with SHI V01D and SHIVANSH THAKUR`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = ShivoidDeviceHelper(context)

        val devJsonStr = helper.getDeviceInfoJson()
        val devJson = JSONObject(devJsonStr)
        assertEquals("SHI.V01D", devJson.optString("appName"))
        assertEquals("SHIVANSH THAKUR", devJson.optString("developer"))
        assertNotNull(devJson.optJSONObject("screen"))

        val battJsonStr = helper.getBatteryInfoJson()
        val battJson = JSONObject(battJsonStr)
        assertTrue(battJson.has("level") || battJson.has("error"))
    }

    @Test
    fun `preferences stores and updates automate endpoint correctly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = ShivoidPreferences(context)

        // Default endpoint should be standard http://127.0.0.1:8080/
        assertEquals("http://127.0.0.1:8080/", prefs.automateEndpoint)

        prefs.automateEndpoint = "http://127.0.0.1:8080/task"
        assertEquals("http://127.0.0.1:8080/task", prefs.automateEndpoint)
    }

    @Test
    fun `http helper sanitizes URLs and resolves relative Automate paths`() {
        val helper = com.example.bridge.ShivoidHttpHelper()

        assertEquals("http://127.0.0.1:8080/", helper.sanitizeUrl(""))
        assertEquals("http://127.0.0.1:8080/task", helper.sanitizeUrl("/task", "http://127.0.0.1:8080/"))
        assertEquals("http://localhost:8080/", helper.sanitizeUrl("localhost:8080/"))
        assertEquals("http://127.0.0.1:8080/", helper.sanitizeUrl("127.0.0.1:8080/"))
        assertEquals("https://api.my-brain.com/sync", helper.sanitizeUrl("https://api.my-brain.com/sync"))
    }
}
