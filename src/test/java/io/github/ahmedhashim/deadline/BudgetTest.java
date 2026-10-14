package io.github.ahmedhashim.deadline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BudgetTest {

    @Test
    void absentIsEmpty() {
        assertTrue(Budget.parse(null).isEmpty());
    }

    @Test
    void wholeMillisecondsWithSpacesAllowed() {
        assertEquals(250, Budget.parse(" 250 ").getAsLong());
        assertEquals(0, Budget.parse("0").getAsLong());
        assertEquals(-5, Budget.parse("-5").getAsLong());
    }

    @Test
    void hugeBudgetsAreClamped() {
        assertEquals(Budget.MAX_MS, Budget.parse("999999999999").getAsLong());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "1.5", "1e3", "abc", "250ms", "0x10", "9999999999999999999", "--5", "5-"})
    void anythingElseIsRejected(String v) {
        assertThrows(IllegalArgumentException.class, () -> Budget.parse(v));
    }
}
