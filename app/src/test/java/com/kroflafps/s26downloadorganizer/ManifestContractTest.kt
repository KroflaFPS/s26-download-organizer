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
    private val codePackage = "com.kroflafps.s26downloadorganizer"
    private val telemetry = BuildConfig.TELEMETRY_ENABLED
    private val applicationId = codePackage + if (telemetry) ".telemetry" else ""
    private fun manifest(property: String): Document = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(File(requireNotNull(System.getProperty(property))))
    private fun elements(document: Document, tag: String): List<Element> {
        val nodes = document.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }
    private fun names(document: Document, tag: String) = elements(document, tag).map { it.getAttributeNS(android, "name") }.toSet()
    private fun checkLauncher(document: Document, isTelemetry: Boolean = false) {
        val activities = elements(document, "activity")
        assertEquals(1, activities.size)
        val activity = activities.single()
        assertEquals(if (isTelemetry) "$codePackage.telemetry.TelemetryActivity" else "$codePackage.MainActivity", activity.getAttributeNS(android, "name").let { if (it.startsWith('.')) codePackage + it else it })
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
            setOf("android.permission.MANAGE_EXTERNAL_STORAGE", "$applicationId.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION") + if (telemetry) setOf("android.permission.WAKE_LOCK", "android.permission.ACCESS_NETWORK_STATE", "android.permission.RECEIVE_BOOT_COMPLETED") else emptySet(),
            names(merged, "uses-permission"),
        )
        for (tag in listOf("uses-permission-sdk-23", "uses-permission-sdk-m")) {
            assertEquals("Unexpected merged $tag", 0, elements(merged, tag).size)
        }
        val workReceivers = setOf("androidx.work.impl.utils.ForceStopRunnable\$BroadcastReceiver",
            "androidx.work.impl.background.systemalarm.RescheduleReceiver", "$codePackage.telemetry.TelemetryRestoreReceiver")
        assertEquals(setOf("androidx.profileinstaller.ProfileInstallReceiver") + if (telemetry) workReceivers else emptySet(), names(merged, "receiver"))
        assertEquals(if (telemetry) setOf("androidx.work.impl.background.systemjob.SystemJobService", "$codePackage.telemetry.TelemetryJobService") else emptySet<String>(), names(merged, "service"))
        elements(merged, "service").forEach { assertEquals("android.permission.BIND_JOB_SERVICE", it.getAttributeNS(android, "permission")) }
        val receiver = elements(merged, "receiver").single { it.getAttributeNS(android, "name") == "androidx.profileinstaller.ProfileInstallReceiver" }
        assertEquals("android.permission.DUMP", receiver.getAttributeNS(android, "permission"))
        assertEquals(setOf("androidx.startup.InitializationProvider"), names(merged, "provider"))
        assertEquals("false", elements(merged, "provider").single().getAttributeNS(android, "exported"))
        assertEquals("signature", elements(merged, "permission").single().getAttributeNS(android, "protectionLevel"))
        assertEquals("$applicationId.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", names(merged, "permission").single())
        assertEquals(applicationId, merged.documentElement.getAttribute("package"))
        assertEquals("7", merged.documentElement.getAttributeNS(android, "versionCode"))
        val gate = elements(merged, "meta-data").single { it.getAttributeNS(android, "name") == "$codePackage.MUTATIONS_ENABLED" }
        assertEquals("false", gate.getAttributeNS(android, "value"))
        assertEquals("", merged.documentElement.getAttributeNS(android, "sharedUserId"))
        assertEquals(applicationId, BuildConfig.APPLICATION_ID)
        assertEquals(false, BuildConfig.DIAGNOSTIC_COPY_ENABLED)
        assertEquals("false", elements(merged, "meta-data").single { it.getAttributeNS(android, "name") == "$codePackage.DIAGNOSTIC_COPY_ENABLED" }.getAttributeNS(android, "value"))
        assertEquals(telemetry.toString(), elements(merged, "meta-data").single { it.getAttributeNS(android, "name") == "$codePackage.TELEMETRY_ENABLED" }.getAttributeNS(android, "value"))
        checkLauncher(merged, telemetry)
    }
}
