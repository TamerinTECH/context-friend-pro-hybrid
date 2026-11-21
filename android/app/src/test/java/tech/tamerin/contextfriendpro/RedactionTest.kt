package tech.tamerin.contextfriendpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic, clearly fictional data only. */
object Synthetic {
    val roster = listOf(KnownName("Mila Sonnenberg"), KnownName("Petra Sonnenberg"), KnownName("Jonas Testmann"),
        KnownName("Lea Meyer-Lindqvist"), KnownName("Will Example"))
    val sensitive = listOf("Mila", "Sonnenberg", "Petra", "Jonas", "Testmann", "Meyer-Lindqvist", "Lindqvist",
        "petra.sonnenberg@example.org", "+49 30 0000000", "12.03.2015", "Musterstraße 12", "10115 Berlin",
        "DE89 3704 0044 0532 0130 00", "Goethe-Gymnasium", "4711123")
    val meeting = listOf(
        SourceRow("s1", "Jonas Testmann", "Thank you for coming in, Mrs Sonnenberg. Mila's reading has improved a lot this term."),
        SourceRow("s2", "Petra Sonnenberg", "Thanks. Milas Hausaufgaben dauern aber oft zwei Stunden. You can reach me at petra.sonnenberg@example.org or +49 30 0000000."),
        SourceRow("s3", "Jonas Testmann", "Her date of birth is 12.03.2015 and we still have Musterstraße 12, 10115 Berlin on file."),
        SourceRow("s4", "Petra Sonnenberg", "She sits next to Lea Meyer-Lindqvist. The fee goes to DE89 3704 0044 0532 0130 00."),
        SourceRow("s5", "Jonas Testmann", "I will talk to the Goethe-Gymnasium about the transfer. Her pupil number is 4711123. Note 4 in Mathe, Klasse 7b."),
    )
}

class RedactionTest {
    private fun p(extra: List<String> = emptyList()) = Pseudonymizer(Synthetic.roster, extra)

    @Test fun fullNameReplaced() {
        val out = p().redact("Mila Sonnenberg joined.")
        assertEquals("[PERSON_1] joined.", out)
    }

    @Test fun firstNameOfUniquePersonSharesPlaceholder() {
        val ps = p()
        assertEquals("[PERSON_1] and [PERSON_1]", ps.redact("Mila Sonnenberg and Mila"))
    }

    @Test fun germanGenitiveKeepsSuffix() = assertEquals("[PERSON_1]s Heft", p().redact("Milas Heft"))

    @Test fun englishPossessiveKeepsSuffix() = assertEquals("[PERSON_1]'s book", p().redact("Mila's book"))

    @Test fun caseInsensitiveForOrdinaryNames() = assertEquals("[PERSON_1] said hi", p().redact("mila sonnenberg said hi"))

    @Test fun hyphenatedSurnameAndItsParts() {
        val ps = p()
        assertEquals("[PERSON_1] / [PERSON_1] / [PERSON_1]", ps.redact("Lea Meyer-Lindqvist / Meyer-Lindqvist / Lindqvist"))
    }

    @Test fun sharedSurnameGetsItsOwnPlaceholder() {
        val ps = p()
        val out = ps.redact("Mila Sonnenberg, Petra Sonnenberg and the Sonnenberg family")
        // Numbers follow detection order (longest name first), not text order.
        assertEquals("[PERSON_2], [PERSON_1] and the [PERSON_3] family", out)
        assertEquals("Sonnenberg", ps.reidentify("[PERSON_3]"))
        assertEquals("Mila Sonnenberg", ps.reidentify("[PERSON_2]"))
    }

    @Test fun nameThatIsAnEnglishWordMatchesOnlyCapitalised() {
        val ps = p()
        assertEquals("I will call. [PERSON_1] agreed.", ps.redact("I will call. Will agreed."))
    }

    @Test fun emailReplaced() = assertEquals("Write to [EMAIL_1].", p().redact("Write to someone@example.org."))

    @Test fun internationalPhoneReplaced() = assertEquals("Call [PHONE_1] today", p().redact("Call +49 30 0000000 today"))

    @Test fun slashPhoneReplaced() = assertEquals("Tel. [PHONE_1]", p().redact("Tel. 030/1234567"))

    @Test fun germanNumericDateReplaced() = assertEquals("born [DATE_1]", p().redact("born 12.03.2015"))

    @Test fun isoDateReplaced() = assertEquals("born [DATE_1]", p().redact("born 2015-03-12"))

    @Test fun writtenDatesReplaced() {
        val ps = p()
        val out = ps.redact("12 March 2015, March 12, 2015 and 12. März")
        assertEquals(3, ps.counts()[Entity.DATE])
        assertTrue(out.matches(Regex("""\[DATE_\d], \[DATE_\d] and \[DATE_\d]""")))
    }

    @Test fun germanAddressAndPostcodeReplaced() =
        assertEquals("[ADDRESS_1], [ADDRESS_2]", p().redact("Musterstraße 12, 10115 Berlin"))

    @Test fun englishAddressReplaced() = assertEquals("lives at [ADDRESS_1].", p().redact("lives at 12 Example Road."))

    @Test fun ibanReplaced() = assertEquals("IBAN [IBAN_1].", p().redact("IBAN DE89 3704 0044 0532 0130 00."))

    @Test fun urlReplaced() = assertEquals("see [URL_1]", p().redact("see https://example.org/class/7b?id=1"))

    @Test fun longIdNumberReplaced() = assertEquals("number [ID_1]", p().redact("number 4711123"))

    @Test fun schoolNamesReplaced() {
        val ps = p()
        assertEquals("[SCHOOL_1] and [SCHOOL_2]", ps.redact("Goethe-Gymnasium and Example Primary School"))
    }

    @Test fun honorificWithUnknownNameReplaced() {
        val ps = p()
        assertEquals("Frau [PERSON_1], Mr. [PERSON_2] and Herr [PERSON_3]",
            ps.redact("Frau Beispiel, Mr. Smith and Herr Dr. Muster"))
    }

    @Test fun cueWordWithUnknownNameReplaced() {
        val ps = p()
        assertEquals("my son [PERSON_1] and meine Tochter [PERSON_2]", ps.redact("my son Oskar and meine Tochter Frieda"))
    }

    @Test fun schoolContentStays() {
        val text = "Note 4 in Mathe, Klasse 7b, 3 children, at 10:30 in 2015."
        assertEquals(text, p().redact(text))
    }

    @Test fun ordinaryWordsAreNotNames() {
        val text = "On Monday the teacher said the English homework is fine."
        assertEquals(text, p().redact(text))
    }

    @Test fun reidentifyRestoresCanonicalNames() {
        val ps = p()
        val redacted = ps.redact("Mila Sonnenberg met Frau Beispiel on 12.03.2015.")
        assertEquals("Mila Sonnenberg met Frau Beispiel on 12.03.2015.", ps.reidentify(redacted))
    }

    @Test fun reidentifyToleratesMissingBrackets() {
        val ps = p()
        ps.redact("Mila")
        // A first name re-identifies to the person's full name: same person, never a different one.
        assertEquals("Mila Sonnenberg will practise.", ps.reidentify("PERSON_1 will practise."))
    }

    @Test fun reidentifyLeavesUnknownPlaceholders() = assertEquals("[PERSON_9] stays", p().reidentify("[PERSON_9] stays"))

    @Test fun userTermsAreRedacted() = assertEquals("[PERSON_1] came", p(listOf("Kalle")).redact("Kalle came"))

    @Test fun countsPerType() {
        val ps = p()
        ps.redact("Mila, someone@example.org, 12.03.2015, Mila")
        assertEquals(mapOf(Entity.PERSON to 1, Entity.EMAIL to 1, Entity.DATE to 1), ps.counts())
    }

    @Test fun meetingFixtureLeavesNoSensitiveValue() {
        val ps = p()
        val outgoing = Prompts.notes("en", MeetingTemplate.PARENT_TEACHER, Prompts.redactedRows(Synthetic.meeting, ps), redacted = true)
        Synthetic.sensitive.forEach { value -> assertFalse("leaked: $value", outgoing.contains(value, ignoreCase = true)) }
        assertTrue(outgoing.contains("[PERSON_"))
        assertTrue(outgoing.contains("Klasse 7b"))
    }

    @Test fun residualRiskIsZeroAfterRedaction() {
        val ps = p()
        val redacted = Synthetic.meeting.joinToString("\n") { ps.redact(it.text) }
        assertEquals(0, ps.residualRisk(redacted, "en").detectorHits)
    }

    @Test fun residualRiskListsUnknownCapitalisedNamesInEnglish() {
        val ps = p()
        val redacted = ps.redact("We talked about it. Then Oskar asked a question.")
        assertTrue(ps.residualRisk(redacted, "en").possibleNames.contains("Oskar"))
    }

    @Test fun residualRiskSkipsNameGuessingForGerman() {
        val ps = p()
        val redacted = ps.redact("Die Hausaufgaben dauern lange. Dann fragte Oskar etwas.")
        assertTrue(ps.residualRisk(redacted, "de").possibleNames.isEmpty())
    }

    @Test fun replacementsListOnlyLivesInTheObject() {
        val ps = p()
        ps.redact("Mila")
        assertEquals(listOf(Replacement("[PERSON_1]", "Mila Sonnenberg", Entity.PERSON)), ps.replacements)
        assertTrue(Pseudonymizer(Synthetic.roster).replacements.isEmpty())
    }
}
