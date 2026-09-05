package com.tcms.crm;

import java.util.UUID;

/**
 * A project on the client page.
 *
 * <p><strong>Four fields, and the shortfall is deliberate.</strong> {@code client.html} also draws
 * Lead, Next release, Hours and Completion for each project. None of those is on any project read
 * shape today — they sit on plan 008 T19's deferred list — and plan 009 §9.3 names a column over a
 * number the response does not carry as the defect to avoid. So this record carries exactly what
 * {@code Project} already serves.
 */
public record ClientProjectView(UUID id, String key, String name, boolean archived) {
}
