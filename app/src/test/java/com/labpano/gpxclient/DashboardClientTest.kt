package com.labpano.gpxclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class DashboardClientTest {
    private val client = DashboardClient()

    @Test
    fun addsDefaultSchemeAndPort() {
        assertEquals("http://192.168.1.25:1100", client.normalizeAddress("192.168.1.25"))
    }

    @Test
    fun preservesExplicitHttpsPort() {
        assertEquals("https://camera.local:8443", client.normalizeAddress("https://camera.local:8443/"))
    }

    @Test
    fun rejectsUnsupportedScheme() {
        assertThrows(IllegalArgumentException::class.java) {
            client.normalizeAddress("ftp://192.168.1.25")
        }
    }

    @Test
    fun rejectsPathAndCredentials() {
        assertThrows(IllegalArgumentException::class.java) {
            client.normalizeAddress("http://user:pass@192.168.1.25/api")
        }
    }
    @Test
    fun parsesMonitoringAndReportHealthDiagnostics() {
        val dashboard = client.parse(JSONObject("""
            {
              "apiVersion":3,
              "appVersion":"0.5.22",
              "monitoringDirectory":"/sdcard/DCIM/Videos/Stitched",
              "outputFolder":"/new-output",
              "monitoring":{"requested":true,"serviceRunning":true,"lastStatus":"monitoring"},
              "reportHealth":{
                "destination":"/sdcard/DCIM/Videos/Stitched",
                "destinationType":"filesystem",
                "available":true,"writable":true,
                "lastSuccessAt":123,"lastFailureAt":0,"lastOperation":"read-good","lastError":"",
                "files":[{"name":"GOOD.TXT","exists":true,"readable":true,"writable":true,"sizeBytes":50}]
              },
              "internalStorage":{},"battery":{},"cameraRecording":{},
              "error":[],"failed":[],"good":[],"transfers":[],"storageWriteAlerts":[]
            }
        """.trimIndent()))
        assertEquals(true, dashboard.monitoring.serviceRunning)
        assertEquals("/new-output", dashboard.outputFolder)
        assertEquals(true, dashboard.reportHealth.available)
        assertEquals("GOOD.TXT", dashboard.reportHealth.files.single().name)
        assertEquals(50L, dashboard.reportHealth.files.single().sizeBytes)
    }


    @Test
    fun oldMainFallsBackToReportDestinationForOutputFolder() {
        val dashboard = client.parse(JSONObject("""
            {
              "apiVersion":3,"appVersion":"0.5.23","monitoringDirectory":"/x",
              "reportHealth":{"destination":"/legacy-output","destinationType":"filesystem","available":true,"writable":true,"files":[]},
              "internalStorage":{},"battery":{},"cameraRecording":{},
              "error":[],"failed":[],"good":[],"transfers":[]
            }
        """.trimIndent()))
        assertEquals("/legacy-output", dashboard.outputFolder)
    }

    @Test
    fun oldDashboardWithoutDiagnosticsRemainsCompatible() {
        val dashboard = client.parse(JSONObject("""
            {
              "apiVersion":3,"appVersion":"0.5.21","monitoringDirectory":"/x",
              "internalStorage":{},"battery":{},"cameraRecording":{},
              "error":[],"failed":[],"good":[],"transfers":[]
            }
        """.trimIndent()))
        assertEquals(false, dashboard.monitoring.available)
        assertEquals(false, dashboard.reportHealth.supported)
        assertEquals(null, dashboard.deviceDiagnostics)
    }
    @Test
    fun parsesBluetoothGpsDiagnostics() {
        val dashboard = client.parse(JSONObject("""
            {
              "apiVersion":3,"appVersion":"0.5.25","monitoringDirectory":"/x",
              "internalStorage":{},"battery":{},"cameraRecording":{},
              "error":[],"failed":[],"good":[],"transfers":[],
              "deviceDiagnostics":{
                "bluetooth":{
                  "available":true,"enabled":true,
                  "devices":[{
                    "name":"GNSS Receiver","address":"00:11:22:33:44:55",
                    "transport":"Classic/system","likelyGps":true,
                    "rssiAvailable":true,"rssiDbm":-61,"rssiObservedAt":1000,
                    "rssiNote":"Passively observed Bluetooth RSSI"
                  }]
                },
                "location":{
                  "available":true,"permissionGranted":true,"fresh":true,
                  "sourceType":"EXTERNAL_BLUETOOTH_MOCK",
                  "sourceLabel":"External Bluetooth GPS via mocked location",
                  "provider":"gps","mocked":true,"lastFixAt":2000,
                  "accuracyMeters":1.5,"inferredExternalBluetoothDevice":"GNSS Receiver"
                },
                "gnss":{
                  "supported":true,"permissionGranted":true,"running":true,"fresh":true,
                  "satellitesVisible":12,"satellitesUsedInFix":8,
                  "averageCn0DbHz":31.5,"maxCn0DbHz":44.0,"firstFixMs":2100,
                  "updatedAt":3000,"activeLocationMocked":true,
                  "signalMatchesActiveLocationSource":false,
                  "constellations":{"GPS":7,"GLONASS":5},
                  "usedConstellations":{"GPS":5,"GLONASS":3}
                }
              }
            }
        """.trimIndent()))
        val diagnostics = dashboard.deviceDiagnostics!!
        assertEquals("GNSS Receiver", diagnostics.bluetooth.devices.single().name)
        assertEquals(-61, diagnostics.bluetooth.devices.single().rssiDbm)
        assertEquals(true, diagnostics.location.mocked)
        assertEquals(12, diagnostics.gnss.satellitesVisible)
        assertEquals(5, diagnostics.gnss.constellations["GLONASS"])
        assertEquals(false, diagnostics.gnss.signalMatchesActiveLocationSource)
    }

    @Test
    fun parsesStructuredCameraFragmentStorageSize() {
        val dashboard = client.parse(JSONObject("""
            {
              "apiVersion":3,"appVersion":"0.5.37","monitoringDirectory":"/sdcard/DCIM/Videos/Stitched",
              "internalStorage":{},"battery":{},"cameraRecording":{},
              "error":[],"failed":[],"good":[],"transfers":[],
              "fragmentStorage":{
                "available":true,"enabled":true,"display":"wrong display should not win",
                "mode":"stitched","rawValue":"6gb","limitType":"size","sizeGb":6,
                "durationMinutes":null,"updatedAt":123,"revision":7,
                "processStartedElapsedRealtime":9000,"source":"camera-efs-video.properties",
                "stitched":{
                  "known":true,"enabled":true,"rawValue":"6gb","displayValue":"6 GB",
                  "limitType":"size","sizeGb":6,"durationMinutes":null
                }
              }
            }
        """.trimIndent()))

        assertEquals(true, dashboard.fragmentStorage.available)
        assertEquals("stitched", dashboard.fragmentStorage.mode)
        assertEquals("6gb", dashboard.fragmentStorage.rawValue)
        assertEquals("size", dashboard.fragmentStorage.limitType)
        assertEquals(6, dashboard.fragmentStorage.sizeGb)
        assertEquals("6 GB", dashboard.fragmentStorage.display)
        assertEquals(9000L, dashboard.fragmentStorage.processStartedElapsedRealtime)
        assertEquals(6, dashboard.fragmentStorage.stitched.sizeGb)
    }

    @Test
    fun derivesCameraSizeFromLegacyRawValueWhenStructuredFieldsAreMissing() {
        val dashboard = client.parse(JSONObject("""
            {
              "apiVersion":3,"appVersion":"0.5.35","monitoringDirectory":"/sdcard/DCIM/Videos/Stitched",
              "internalStorage":{},"battery":{},"cameraRecording":{},
              "error":[],"failed":[],"good":[],"transfers":[],
              "fragmentStorage":{
                "available":true,"enabled":true,"display":"10 GB","rawValue":"10gb",
                "updatedAt":123,"revision":4,"source":"camera-efs-video.properties"
              }
            }
        """.trimIndent()))

        assertEquals("size", dashboard.fragmentStorage.limitType)
        assertEquals(10, dashboard.fragmentStorage.sizeGb)
        assertEquals("10 GB", dashboard.fragmentStorage.display)
    }

    @Test
    fun parsesEveryPendingGpxItemOnAPage() {
        val items = client.pendingGpxItems(JSONArray("""
            [
              {"id":"a","status":"GOOD","completedAt":"2026-08-16T10:30:00.000Z","videoName":"260816_102735266.mp4","videoPath":"/a.mp4","gpxName":"a.gpx","gpxPath":"/a.gpx","gpxSizeBytes":123,"downloadUrl":"/a"},
              {"id":"b","status":"FAILED","completedAt":"2026-08-16T10:40:00.000Z","videoName":"260816_103735266.mp4","videoPath":"/b.mp4","gpxName":"b.gpx","gpxPath":"/b.gpx","gpxSizeBytes":456,"downloadUrl":"/b"}
            ]
        """.trimIndent()))

        assertEquals(2, items.size)
        assertEquals("260816_102735266.mp4", items[0].videoName)
        assertEquals(456L, items[1].gpxSizeBytes)
    }

}
