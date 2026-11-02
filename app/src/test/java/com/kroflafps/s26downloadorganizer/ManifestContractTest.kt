package com.kroflafps.s26downloadorganizer

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

/** Checks Build 1's declared app boundary, not Android storage behavior. */
class ManifestContractTest {
    private val manifest = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(File(System.getProperty("s26.sourceManifest")))

    @Test
    fun foundationDeclaresNoPermissionsOrBackgroundComponents() {
        for (element in listOf("uses-permission", "uses-permission-sdk-23", "service", "receiver")) {
            assertEquals("Build 1 must not declare $element", 0, manifest.getElementsByTagName(element).length)
        }
    }

    @Test
    fun dashboardIsTheSingleExportedLauncherActivity() {
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        val activities = manifest.getElementsByTagName("activity")
        assertEquals(1, activities.length)
        val activity = activities.item(0) as org.w3c.dom.Element
        assertEquals(".MainActivity", activity.getAttributeNS(androidNamespace, "name"))
        assertEquals("true", activity.getAttributeNS(androidNamespace, "exported"))
        val filter = activity.getElementsByTagName("intent-filter").item(0) as org.w3c.dom.Element
        val action = filter.getElementsByTagName("action").item(0) as org.w3c.dom.Element
        val category = filter.getElementsByTagName("category").item(0) as org.w3c.dom.Element
        assertEquals("android.intent.action.MAIN", action.getAttributeNS(androidNamespace, "name"))
        assertEquals("android.intent.category.LAUNCHER", category.getAttributeNS(androidNamespace, "name"))
    }
}
