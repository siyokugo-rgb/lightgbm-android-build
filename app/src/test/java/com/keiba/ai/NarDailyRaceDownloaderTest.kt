package com.keiba.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NarDailyRaceDownloaderTest {

    @Test
    fun parsesExpectedDailyZip() {
        val zip =
            zipOf(
                "20260829_racelist.csv" to
                    "競馬場,競走年月日,レース番号\n大井,20260829,1\n"
                        .toByteArray(Charsets.UTF_8),
                "20260829_horselist.csv" to
                    "競馬場,競走年月日,レース番号,馬番\n大井,20260829,1,1\n"
                        .toByteArray(Charsets.UTF_8)
            )

        val data =
            NarDailyRaceDownloader
                .parseZipForTest(zip)

        assertEquals(
            "20260829",
            data.date
        )
        assertEquals(
            true,
            data.racelistCsv.contains("大井")
        )
        assertEquals(
            true,
            data.horselistCsv.contains("大井")
        )
        assertNull(data.paybackCsv)
        assertNull(data.sourceFileName)
        assertNull(data.sourceTimestampEpochSecond)
        assertNull(data.downloadedAtEpochMillis)
        assertNull(data.serverDateEpochMillis)
    }

    @Test
    fun parsesOfficialSourceMetadata() {
        val metadata =
            NarDailyRaceDownloader
                .parseSourceMetadataForTest(
                    "attachment; filename=\"20260829_1788001087_race.zip\""
                )

        assertEquals(
            "20260829",
            metadata.date
        )
        assertEquals(
            "20260829_1788001087_race.zip",
            metadata.fileName
        )
        assertEquals(
            1788001087L,
            metadata.timestampEpochSecond
        )
    }

    @Test
    fun rejectsMissingSourceMetadata() {
        assertThrows(
            IllegalStateException::class.java
        ) {
            NarDailyRaceDownloader
                .parseSourceMetadataForTest(
                    null
                )
        }
    }

    @Test
    fun rejectsMalformedSourceFilename() {
        assertThrows(
            IllegalStateException::class.java
        ) {
            NarDailyRaceDownloader
                .parseSourceMetadataForTest(
                    "attachment; filename=\"race.zip\""
                )
        }
    }

    @Test
    fun rejectsSourceTimestampDateMismatch() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarDailyRaceDownloader
                .parseSourceMetadataForTest(
                    "attachment; filename=\"20260828_1788001087_race.zip\""
                )
        }
    }

    @Test
    fun rejectsUnexpectedEntry() {
        val zip =
            zipOf(
                "20260829_racelist.csv" to
                    "a,b\n1,2\n".toByteArray(),
                "20260829_horselist.csv" to
                    "a,b\n1,2\n".toByteArray(),
                "readme.txt" to
                    "unexpected".toByteArray()
            )

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarDailyRaceDownloader
                .parseZipForTest(zip)
        }
    }

    @Test
    fun rejectsNestedEntryPath() {
        val zip =
            zipOf(
                "folder/20260829_racelist.csv" to
                    "a,b\n1,2\n".toByteArray(),
                "20260829_horselist.csv" to
                    "a,b\n1,2\n".toByteArray()
            )

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarDailyRaceDownloader
                .parseZipForTest(zip)
        }
    }

    @Test
    fun rejectsDuplicateRacelist() {
        val zip =
            zipOf(
                "20260829_racelist.csv" to
                    "a,b\n1,2\n".toByteArray(),
                "20260829_horselist.csv" to
                    "a,b\n1,2\n".toByteArray(),
                "20260829_payback.csv" to
                    "a,b\n1,2\n".toByteArray(),
                "20260830_racelist.csv" to
                    "a,b\n3,4\n".toByteArray()
            )

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarDailyRaceDownloader
                .parseZipForTest(zip)
        }
    }

    @Test
    fun rejectsDateMismatch() {
        val zip =
            zipOf(
                "20260829_racelist.csv" to
                    "a,b\n1,2\n".toByteArray(),
                "20260830_horselist.csv" to
                    "a,b\n1,2\n".toByteArray()
            )

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarDailyRaceDownloader
                .parseZipForTest(zip)
        }
    }

    @Test
    fun rejectsMalformedUtf8() {
        val zip =
            zipOf(
                "20260829_racelist.csv" to
                    byteArrayOf(
                        0xC3.toByte(),
                        0x28
                    ),
                "20260829_horselist.csv" to
                    "a,b\n1,2\n".toByteArray()
            )

        assertThrows(
            Exception::class.java
        ) {
            NarDailyRaceDownloader
                .parseZipForTest(zip)
        }
    }

    @Test
    fun rejectsOversizedUncompressedEntry() {
        val oversized =
            ByteArray(
                (
                    NarDailyRaceDownloader
                        .MAX_ENTRY_BYTES + 1L
                    ).toInt()
            ) {
                'A'.code.toByte()
            }

        val zip =
            zipOf(
                "20260829_racelist.csv" to
                    oversized,
                "20260829_horselist.csv" to
                    "a,b\n1,2\n".toByteArray()
            )

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarDailyRaceDownloader
                .parseZipForTest(zip)
        }
    }

    private fun zipOf(
        vararg entries: Pair<String, ByteArray>
    ): ByteArray {
        val output =
            ByteArrayOutputStream()

        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(
                    ZipEntry(name)
                )
                zip.write(bytes)
                zip.closeEntry()
            }
        }

        return output.toByteArray()
    }
}
