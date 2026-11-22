package com.kutv.music

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class TvManifestCompatibilityTest {
    @Test
    fun `TV features remain optional while the Leanback launcher stays enabled`() {
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue("AndroidManifest.xml must be available to the JVM test", manifest.isFile)

        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(manifest)

        val features = document.getElementsByTagName("uses-feature")
        val featureRequirements = buildMap {
            for (index in 0 until features.length) {
                val feature = features.item(index) as Element
                put(
                    feature.androidAttribute("name"),
                    feature.androidAttribute("required"),
                )
            }
        }

        assertEquals("false", featureRequirements["android.hardware.type.television"])
        assertEquals("false", featureRequirements["android.software.leanback"])
        assertEquals("false", featureRequirements["android.hardware.touchscreen"])
        assertEquals("false", featureRequirements["android.hardware.microphone"])

        val categories = document.getElementsByTagName("category")
        val hasLeanbackLauncher = (0 until categories.length).any { index ->
            (categories.item(index) as Element).androidAttribute("name") ==
                "android.intent.category.LEANBACK_LAUNCHER"
        }
        assertTrue("The TV launcher entry must remain enabled", hasLeanbackLauncher)

        val application = document.getElementsByTagName("application").item(0) as? Element
        assertNotNull("The manifest must define an application", application)
        assertTrue(
            "A TV banner is required by Leanback launchers",
            application!!.androidAttribute("banner").isNotBlank(),
        )

        val activities = document.getElementsByTagName("activity")
        val mainActivity = (0 until activities.length)
            .map { activities.item(it) as Element }
            .firstOrNull { it.androidAttribute("name") == ".MainActivity" }
        assertNotNull("The manifest must declare MainActivity", mainActivity)
        assertEquals("landscape", mainActivity!!.androidAttribute("screenOrientation"))
    }

    @Test
    fun `TV banner remains a localized 320 by 180 xhdpi bitmap`() {
        val banner = File("src/main/res/drawable-xhdpi/tv_banner.png")
        assertTrue("The TV banner must live in drawable-xhdpi", banner.isFile)
        assertTrue(
            "Do not restore the oversized vector fallback",
            !File("src/main/res/drawable/tv_banner.xml").exists(),
        )
        val pngHeader = banner.inputStream().use { it.readNBytes(24) }
        assertEquals("The TV banner must contain a PNG IHDR header", 24, pngHeader.size)
        assertEquals("IHDR", String(pngHeader, 12, 4, StandardCharsets.US_ASCII))
        val dimensions = ByteBuffer.wrap(pngHeader).order(ByteOrder.BIG_ENDIAN)
        assertEquals(320, dimensions.getInt(16))
        assertEquals(180, dimensions.getInt(20))
    }

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS(ANDROID_NAMESPACE, name)

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
