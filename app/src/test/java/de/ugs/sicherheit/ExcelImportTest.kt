package de.ugs.sicherheit

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class ExcelImportTest {
    private fun workbook(sheet: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            val files =
                mapOf(
                    "xl/workbook.xml" to
                        """<workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Personal" r:id="rId7"/></sheets></workbook>""",
                    "xl/_rels/workbook.xml.rels" to
                        """<Relationships><Relationship Id="rId7" Target="worksheets/personal.xml"/></Relationships>""",
                    "xl/sharedStrings.xml" to
                        """<sst><si><t>Vorname</t></si><si><t>Geburtsdatum</t></si><si><t>Erika</t></si></sst>""",
                    "xl/styles.xml" to
                        """<styleSheet><cellXfs><xf numFmtId="0"/><xf numFmtId="14"/></cellXfs></styleSheet>""",
                    "xl/worksheets/personal.xml" to sheet,
                )
            files.forEach { (name, text) ->
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun firstSheetRelationshipSharedStringsMissingCellsAndExcelDates() {
        val data =
            workbook(
                """<worksheet><sheetData><row><c r="A1" t="s"><v>0</v></c><c r="C1" t="s"><v>1</v></c></row><row><c r="A2" t="s"><v>2</v></c><c r="C2" s="1"><v>61</v></c></row></sheetData></worksheet>"""
            )
        val rows = ImportService.xlsx(data)
        assertEquals(listOf("Vorname", "", "Geburtsdatum"), rows[0])
        assertEquals(listOf("Erika", "", "1900-03-01"), rows[1])
    }

    @Test
    fun rejectsDtdBeforeParsing() {
        val data =
            workbook(
                """<!DOCTYPE x [<!ENTITY bomb "danger">]><worksheet><sheetData/></worksheet>"""
            )
        assertThrows(IllegalArgumentException::class.java) { ImportService.xlsx(data) }
    }
}
