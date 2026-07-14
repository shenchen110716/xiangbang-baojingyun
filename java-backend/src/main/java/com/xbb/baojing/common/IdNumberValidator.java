package com.xbb.baojing.common;

import java.time.LocalDate;

/** GB 11643-1999 18-digit resident ID number format/checksum validation.
 *
 * This is a local, offline format check only — it confirms the number is
 * structurally well-formed (birth date, checksum digit), not that it
 * belongs to the person whose name was entered. Verifying a name-to-ID
 * match requires a real-name verification provider, which this system has
 * no integration for, so that part is out of scope here. */
public final class IdNumberValidator {
    private static final int[] WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    private static final String CHECK_CODES = "10X98765432";

    private IdNumberValidator() {}

    public static boolean isValid(String raw) {
        if (raw == null) return false;
        String value = raw.strip().toUpperCase();
        if (!value.matches("\\d{17}[\\dX]")) return false;
        try {
            LocalDate birth = LocalDate.of(Integer.parseInt(value.substring(6, 10)), Integer.parseInt(value.substring(10, 12)), Integer.parseInt(value.substring(12, 14)));
            if (birth.isAfter(LocalDate.now())) return false;
        } catch (NumberFormatException | java.time.DateTimeException e) {
            return false;
        }
        int total = 0;
        for (int i = 0; i < 17; i++) total += (value.charAt(i) - '0') * WEIGHTS[i];
        return CHECK_CODES.charAt(total % 11) == value.charAt(17);
    }
}
