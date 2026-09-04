package com.tcms.time;

import java.time.LocalDate;

/** One half-month, derived rather than stored — see {@link Periods}. */
record PeriodBounds(LocalDate startsOn, LocalDate endsOn) {
}
