package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NumericPreservationVerifierTest {
    private fun assertValid(input: String, output: String) {
        val normalized = SpokenNumberNormalizer.normalize(input)
        assertTrue(
            "expected numeric preservation for ${normalized.semanticValues}",
            NumericPreservationVerifier.verify(normalized, output) is NumericPreservation.Valid,
        )
    }

    private fun assertRejected(input: String, output: String) {
        val normalized = SpokenNumberNormalizer.normalize(input)
        val verdict = NumericPreservationVerifier.verify(normalized, output)
        assertTrue("expected rejection for ${normalized.semanticValues}", verdict is NumericPreservation.Rejected)
        assertTrue((verdict as NumericPreservation.Rejected).detail.isNotBlank())
    }

    @Test fun `phone punctuation changes pass when the digit sequence is identical`() {
        assertValid("call 2125553476", "Call 212-555-3476.")
        assertValid("call 2125553476", "Call 212.555.3476.")
        assertValid("call two one two five five five three four seven six", "Call (212) 555-3476.")
        assertValid("call 5553476", "Call 555-3476.")
        assertValid("call 12125553476", "Call +1 (212) 555-3476.")
    }

    @Test fun `changed dropped duplicated and reordered values are rejected`() {
        assertRejected("send 450 dollars to account 9372", "Send 150 dollars to account 9372.")
        assertRejected("send 450 dollars to account 9372", "Send 450 dollars.")
        assertRejected("send 450 dollars", "Send 450, actually 450 dollars.")
        assertRejected("codes are 12 then 34", "Codes are 34 then 12.")
    }

    @Test fun `arithmetic evaluation and collapsed arithmetic are rejected`() {
        assertRejected("divide it by 3 and you get 33", "Divide it by 3 and you get 11.")
        assertRejected("100 minus 25 is 75", "75")
    }

    @Test fun `expanded decimal multiplier must survive as its exact value`() {
        val normalized = SpokenNumberNormalizer.normalize("one point two million dollars")
        assertEquals("1200000 dollars", normalized.text)
        assertEquals(listOf("1200000"), normalized.semanticValues)
        assertTrue(NumericPreservationVerifier.verify(normalized, "$1,200,000") is NumericPreservation.Valid)
        assertTrue(NumericPreservationVerifier.verify(normalized, "$1,200") is NumericPreservation.Rejected)
    }

    @Test fun `long decimals keep decimal meaning despite phone-shaped digit counts`() {
        val normalized = SpokenNumberNormalizer.normalize("pi is 3.1415926")
        assertEquals(listOf("3.1415926"), normalized.semanticValues)
        assertTrue(NumericPreservationVerifier.verify(normalized, "Pi is 3.1415926.") is NumericPreservation.Valid)
        assertTrue(NumericPreservationVerifier.verify(normalized, "Pi is 3.1415927.") is NumericPreservation.Rejected)
    }

    @Test fun `number free text has no numeric preservation burden`() {
        val normalized = SpokenNumberNormalizer.normalize("hello there")
        assertTrue(NumericPreservationVerifier.verify(normalized, "Hello there.") is NumericPreservation.Valid)
    }

    // ---- #325: a number written back as a word is the same number ------------------------------

    @Test fun `a small number returned as a word is accepted when the value is unchanged`() {
        assertValid("and finally I really need to go for a run at least three times this week",
            "And finally, I really need to go for a run at least three times this week.")
        assertValid("we leave on the fourth day and stay five nights", "We leave on the fourth day and stay five nights.")
        assertValid("send four hundred and fifty dollars", "Send four hundred and fifty dollars.")
        assertValid("the 1st half was good", "The first half was good.")
    }

    @Test fun `a number returned as a word with a different value is still rejected`() {
        assertRejected("at least three times this week", "At least four times this week.")
        assertRejected("send four hundred and fifty dollars", "Send four hundred and sixty dollars.")
        assertRejected("send four hundred and fifty dollars", "Send one hundred and fifty dollars.")
        assertRejected("stay five nights", "Stay fifty nights.")
    }

    @Test fun `a number dropped, duplicated or reordered is still rejected even when words are allowed`() {
        assertRejected("the three biggest suppliers", "The biggest suppliers.")
        assertRejected("the three biggest suppliers", "The three biggest suppliers, three of them.")
        assertRejected("three apples then seven pears", "Seven pears then three apples.")
        assertRejected("a 1st and a 2nd place", "A second and a first place.")
        // Spelling out a number and also adding a different one.
        assertRejected("on the fourth day", "On the fourth day, five hours later.")
    }

    // ---- Phase 2D formatting & bare spoken equivalences ----------------------------------------

    @Test fun `leading zeros and trailing zero decimals compare canonically`() {
        assertValid("check in at 08", "Check in at 8.")
        assertValid("the price is 12.00 dollars", "The price is $12.")
        assertValid("the version is 12.0", "Version 12")
        assertRejected("version 12.5", "Version 12")
    }

    @Test fun `on-the-hour clock time matches AM-PM hour rendering while changed minutes still reject`() {
        assertValid("meet at 12:00", "Meet at 12 PM.")
        assertValid("alarm at 08:00", "Alarm at 8 AM.")
        assertRejected("meet at 12:00", "Meet at 12:30.")
        assertRejected("meet at 12:00", "Meet at 12:05.")
    }

    @Test fun `one and 1 are equivalent when numeric while altered or dropped explicit 1 is rejected`() {
        assertValid("pick 1 option", "Pick one option.")
        assertValid("pick one option", "Pick 1 option.")
        assertValid("one of the things matters", "The thing matters.")
        assertRejected("pick 1 option", "Pick 2 options.")
        assertRejected("pick 1 option", "Pick an option.")
    }

    @Test fun `context-free spoken clock time matches digit colon rendering and rejects altered time`() {
        assertValid("four thirty works for me", "4:30 works for me.")
        assertValid("twelve oh five works for me", "12:05 works for me.")
        assertRejected("four thirty works for me", "5:30 works for me.")
        assertRejected("four thirty works for me", "4:45 works for me.")
    }
}
