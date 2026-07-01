package com.mamba.picme.domain.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.Calendar

class QueryParserTimeTest {

    @Test
    fun `parse last year march`() {
        QueryParser.currentYear = 2025
        QueryParser.currentMonth = 6

        val range = QueryParser.parseTimeRange("去年3月")
        assertNotNull(range)

        val cal = Calendar.getInstance()
        cal.timeInMillis = range!!.startMs
        assertEquals(2024, cal.get(Calendar.YEAR))
        assertEquals(2, cal.get(Calendar.MONTH)) // 0-based

        cal.timeInMillis = range.endMs
        assertEquals(2024, cal.get(Calendar.YEAR))
        assertEquals(2, cal.get(Calendar.MONTH))
        assertEquals(31, cal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `parse this year may`() {
        QueryParser.currentYear = 2025
        QueryParser.currentMonth = 6

        val range = QueryParser.parseTimeRange("今年5月")
        assertNotNull(range)

        val cal = Calendar.getInstance()
        cal.timeInMillis = range!!.startMs
        assertEquals(2025, cal.get(Calendar.YEAR))
        assertEquals(4, cal.get(Calendar.MONTH))
    }

    @Test
    fun `parse specific year and month`() {
        val range = QueryParser.parseTimeRange("2024年3月")
        assertNotNull(range)

        val cal = Calendar.getInstance()
        cal.timeInMillis = range!!.startMs
        assertEquals(2024, cal.get(Calendar.YEAR))
        assertEquals(2, cal.get(Calendar.MONTH))
    }

    @Test
    fun `parse full year`() {
        QueryParser.currentYear = 2025
        QueryParser.currentMonth = 6

        val range = QueryParser.parseTimeRange("去年")
        assertNotNull(range)

        val cal = Calendar.getInstance()
        cal.timeInMillis = range!!.startMs
        assertEquals(2024, cal.get(Calendar.YEAR))
        assertEquals(0, cal.get(Calendar.MONTH))

        cal.timeInMillis = range.endMs
        assertEquals(2024, cal.get(Calendar.YEAR))
        assertEquals(11, cal.get(Calendar.MONTH))
    }

    @Test
    fun `parse last month`() {
        QueryParser.currentYear = 2025
        QueryParser.currentMonth = 6

        val range = QueryParser.parseTimeRange("上个月")
        assertNotNull(range)

        val cal = Calendar.getInstance()
        cal.timeInMillis = range!!.startMs
        assertEquals(2025, cal.get(Calendar.YEAR))
        assertEquals(4, cal.get(Calendar.MONTH))
    }

    @Test
    fun `parse this week`() {
        val range = QueryParser.parseTimeRange("本周")
        assertNotNull(range)

        val cal = Calendar.getInstance()
        cal.timeInMillis = range!!.startMs
        assertEquals(Calendar.MONDAY, cal.get(Calendar.DAY_OF_WEEK))
    }

    @Test
    fun `parse chinese month may`() {
        QueryParser.currentYear = 2025
        QueryParser.currentMonth = 6

        val range = QueryParser.parseTimeRange("五月")
        assertNotNull(range)

        val cal = Calendar.getInstance()
        cal.timeInMillis = range!!.startMs
        assertEquals(2025, cal.get(Calendar.YEAR))
        assertEquals(4, cal.get(Calendar.MONTH))

        cal.timeInMillis = range.endMs
        assertEquals(2025, cal.get(Calendar.YEAR))
        assertEquals(4, cal.get(Calendar.MONTH))
        assertEquals(31, cal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `parse last year chinese month may`() {
        QueryParser.currentYear = 2025
        QueryParser.currentMonth = 6

        val range = QueryParser.parseTimeRange("去年五月")
        assertNotNull(range)

        val cal = Calendar.getInstance()
        cal.timeInMillis = range!!.startMs
        assertEquals(2024, cal.get(Calendar.YEAR))
        assertEquals(4, cal.get(Calendar.MONTH))
    }
}
