package com.kroflafps.s26downloadorganizer

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

/** Checks both app source and AGP's merged package boundary, including library components. */
class ManifestContractTest {
    private val android = "http://schemas.android.com/apk/res/android"
    private val applicationId = "com.kroflafps.s26downloadorganizer"
    private fun manifest(property: String): Document = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(File(requireNotNull(System.getProperty(property))))
    private fun elements(document: Document, tag: String): List<Element> {
        val nodes = document.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }
    private fun names(document: Document, tag: String) = elements(document, tag).map { it.getAttributeNS(android, "name") }.toSet()
    private fun checkLauncher(document: Document) {
        val activities = elements(document, "activity")
        assertEquals(1, activities.size)
        val activity = activities.single()
        assertEquals("$applicationId.MainActivity", activity.getAttributeNS(android, "name").let { if (it.startsWith('.')) applicationId + it else it })
        assertEquals("true", activity.getAttributeNS(android, "exported"))
        val filter = activity.getElementsByTagName("intent-filter").item(0) as Element
        assertEquals("android.intent.action.MAIN", (filter.getElementsByTagName("action").item(0) as Element).getAttributeNS(android, "name"))
        assertEquals("android.intent.category.LAUNCHER", (filter.getElementsByTagName("category").item(0) as Element).getAttributeNS(android, "name"))
        assertEquals(emptySet<String>(), names(document, "activity-alias"))
    }

    @Test fun sourceOnlyDeclaresApprovedAllFilesPermissionAndNoBackgroundComponents() {
        val source = manifest("s26.sourceManifest")
        assertEquals(setOf("android.permission.MANAGE_EXTERNAL_STORAGE"), names(source, "uses-permission"))
        for (tag in listOf("uses-permission-sdk-23", "uses-permission-sdk-m", "receiver", "provider")) {
            assertEquals("Unexpected source $tag", 0, elements(source, tag).size)
        }
        val removal = elements(source, "service").single()
        assertEquals("androidx.room.MultiInstanceInvalidationService", removal.getAttributeNS(android, "name"))
        assertEquals("remove", removal.getAttributeNS("http://schemas.android.com/tools", "node"))
        checkLauncher(source)
    }

    @Test fun mergedManifestHasOnlyApprovedPermissionAndKnownAndroidxComponents() {
        val merged = manifest("s26.mergedManifest")
        assertEquals(
            setOf("android.permission.MANAGE_EXTERNAL_STORAGE", "$applicationId.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"),
            names(merged, "uses-permission"),
        )
        for (tag in listOf("uses-permission-sdk-23", "uses-permission-sdk-m", "service")) {
            assertEquals("Unexpected merged $tag", 0, elements(merged, tag).size)
        }
        assertEquals(setOf("androidx.profileinstaller.ProfileInstallReceiver"), names(merged, "receiver"))
        val receiver = elements(merged, "receiver").single()
        assertEquals("android.permission.DUMP", receiver.getAttributeNS(android, "permission"))
        assertEquals(setOf("androidx.startup.InitializationProvider"), names(merged, "provider"))
        assertEquals("false", elements(merged, "provider").single().getAttributeNS(android, "exported"))
        assertEquals("signature", elements(merged, "permission").single().getAttributeNS(android, "protectionLevel"))
        assertEquals("$applicationId.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", names(merged, "permission").single())
        assertEquals(applicationId, merged.documentElement.getAttribute("package"))
        assertEquals("4", merged.documentElement.getAttributeNS(android, "versionCode"))
        val gate = elements(merged, "meta-data").single { it.getAttributeNS(android, "name") == "$applicationId.MUTATIONS_ENABLED" }
        assertEquals("false", gate.getAttributeNS(android, "value"))
        checkLauncher(merged)
    }
}
