package io.github.tigaotg.openkinguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MedicalArchiveTest {
    @Test fun portableTextRoundtripDoesNotInventAttachments() {
        val row = MedicalEntry("stable-id", mapOf("name" to "试用药", "dose" to "一片",
            "notes" to "按医生处方"))
        val source = MedicalVault.emptyState().copy(profile = mapOf("blood" to "未知"),
            entries = mapOf(MedicalKind.MEDICINE to listOf(row)))
        val txt = MedicalArchive.text(source)
        assertTrue(txt.contains("药品名称：试用药"))
        val parsed = MedicalArchive.previewText(ByteArrayInputStream(txt.toByteArray()))
        assertEquals("未知", parsed.state.profile["blood"])
        assertEquals(row.values, parsed.state.records(MedicalKind.MEDICINE).single().values)
        assertTrue(parsed.state.files.isEmpty())
    }

    @Test fun singleCategoryTextRoundtrip() {
        val record = MedicalEntry("one", mapOf("hospital" to "医院甲", "diagnosis" to "待明确"))
        val source = MedicalVault.emptyState().copy(entries =
            mapOf(MedicalKind.ADMISSION to listOf(record)))
        val text = MedicalArchive.text(source, MedicalKind.ADMISSION)
        val imported = MedicalArchive.previewText(ByteArrayInputStream(text.toByteArray()))
        assertEquals(record.values, imported.state.records(MedicalKind.ADMISSION).single().values)
    }

    @Test fun csvEscapingIsReadable() {
        val row = MedicalEntry("one", mapOf("medicineName" to "甲,乙\"药", "status" to "已跳过"))
        val text = MedicalArchive.csv(MedicalVault.emptyState().copy(entries =
            mapOf(MedicalKind.DOSE to listOf(row))))
        assertTrue(text.contains("\"甲,乙\"\"药\""))
        assertTrue(text.contains("已跳过"))
    }

    @Test fun standardZipManifestCanBeReadWithoutAnyAppSpecificFormat() {
        val state = MedicalVault.emptyState().copy(profile = mapOf("name" to "测试用户"))
        val archive = ByteArrayOutputStream()
        ZipOutputStream(archive).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(MedicalVault.encode(state).toString().toByteArray())
            zip.closeEntry()
        }
        val restored = MedicalArchive.previewZip(ByteArrayInputStream(archive.toByteArray()))
        assertEquals("测试用户", restored.state.profile["name"])
    }

    @Test fun maliciousZipTraversalPathIsRejected() {
        val archive = ByteArrayOutputStream()
        ZipOutputStream(archive).use { zip ->
            zip.putNextEntry(ZipEntry("../outside.txt"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
        assertThrows(IllegalArgumentException::class.java) {
            MedicalArchive.previewZip(ByteArrayInputStream(archive.toByteArray()))
        }
    }
}
