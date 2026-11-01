package com.glasssign.app.local

import org.junit.Test
import org.junit.Assert.*

class ScheduleCompletionTest {
    @Test fun previousDayWorkerCannotMarkNewBeijingDayComplete() {
        assertFalse(SchedulePolicy.canCompleteDay("2026-11-01","2026-11-02",false,false))
        assertTrue(SchedulePolicy.canCompleteDay("2026-11-01","2026-11-01",false,false))
    }
    @Test fun networkOrRetryableFailureKeepsCurrentDayOpen() {
        assertFalse(SchedulePolicy.canCompleteDay("2026-11-01","2026-11-01",true,false))
        assertFalse(SchedulePolicy.canCompleteDay("2026-11-01","2026-11-01",false,true))
    }
}
