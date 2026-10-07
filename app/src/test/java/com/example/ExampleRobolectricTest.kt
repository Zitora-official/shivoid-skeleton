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
    fun `read string from context matches SHIVOID`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("SHIVOID", appName)
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
    fun `device helper produces valid json`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = ShivoidDeviceHelper(context)

        val devJsonStr = helper.getDeviceInfoJson()
        val devJson = JSONObject(devJsonStr)
        assertEquals("SHIVOID", devJson.optString("appName"))
        assertNotNull(devJson.optJSONObject("screen"))

        val battJsonStr = helper.getBatteryInfoJson()
        val battJson = JSONObject(battJsonStr)
        assertTrue(battJson.has("level") || battJson.has("error"))
    }
}
