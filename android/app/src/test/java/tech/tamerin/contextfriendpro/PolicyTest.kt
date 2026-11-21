package tech.tamerin.contextfriendpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyTest {
    private fun opts(nano: NanoState = NanoState.AVAILABLE, gemma: Boolean = true, fits: Boolean = true) = OnDeviceOptions(nano, gemma, fits)
    private val clean = ResidualRisk(0, emptyList(), "")

    @Test fun autoEnglishUsesNano() =
        assertEquals(LocalChoice.Use(NotesEngine.GEMINI_NANO, "auto_nano"), Policy.chooseOnDevice("en", EnginePreference.AUTO, opts()))

    @Test fun autoGermanPrefersGemmaBecauseNanoIsNotValidated() =
        assertEquals(LocalChoice.Use(NotesEngine.GEMMA_LOCAL, "auto_language_not_validated_for_nano"),
            Policy.chooseOnDevice("de", EnginePreference.AUTO, opts()))

    @Test fun autoGermanWithoutGemmaUsesNanoWithWarning() =
        assertEquals(LocalChoice.Use(NotesEngine.GEMINI_NANO, "auto_only_nano_available", "nano_language_unvalidated"),
            Policy.chooseOnDevice("de", EnginePreference.AUTO, opts(gemma = false)))

    @Test fun autoEnglishWithNanoNotDownloadedUsesGemma() =
        assertEquals(LocalChoice.Use(NotesEngine.GEMMA_LOCAL, "auto_nano_not_ready"),
            Policy.chooseOnDevice("en", EnginePreference.AUTO, opts(nano = NanoState.DOWNLOADABLE)))

    @Test fun autoWithNothingIsBlocked() =
        assertEquals(LocalChoice.Blocked("no_on_device_engine"),
            Policy.chooseOnDevice("en", EnginePreference.AUTO, opts(nano = NanoState.UNAVAILABLE, gemma = false)))

    @Test fun chosenNanoNotDownloadedIsBlocked() =
        assertEquals(LocalChoice.Blocked("nano_not_downloaded"),
            Policy.chooseOnDevice("en", EnginePreference.GEMINI_NANO, opts(nano = NanoState.DOWNLOADABLE)))

    @Test fun chosenNanoWithoutFirebaseIsBlocked() =
        assertEquals(LocalChoice.Blocked("firebase_not_configured"),
            Policy.chooseOnDevice("en", EnginePreference.GEMINI_NANO, opts(nano = NanoState.NOT_CONFIGURED)))

    @Test fun chosenGemmaMissingOrTooBig() {
        assertEquals(LocalChoice.Blocked("gemma_missing"), Policy.chooseOnDevice("en", EnginePreference.GEMMA_LOCAL, opts(gemma = false)))
        assertEquals(LocalChoice.Blocked("gemma_needs_more_memory"), Policy.chooseOnDevice("en", EnginePreference.GEMMA_LOCAL, opts(fits = false)))
    }

    @Test fun automaticRoutingNeverChoosesTheCloud() {
        for (language in listOf("en", "de", "he", "auto", "ko"))
            for (pref in EnginePreference.entries)
                for (nano in NanoState.entries)
                    for (gemma in listOf(true, false))
                        for (fits in listOf(true, false)) {
                            val choice = Policy.chooseOnDevice(language, pref, opts(nano, gemma, fits))
                            if (choice is LocalChoice.Use) {
                                assertNotEquals(NotesEngine.CLOUD_REDACTED, choice.engine)
                                assertTrue(choice.engine.onDevice)
                            }
                        }
    }

    @Test fun cloudKillSwitchWins() =
        assertEquals(CloudDecision.Block("cloud_disabled"), Policy.cloudDecision(HybridConfig(cloudEnabled = false), true, true, clean))

    @Test fun cloudNeedsFirebase() =
        assertEquals(CloudDecision.Block("firebase_not_configured"), Policy.cloudDecision(HybridConfig(), false, true, clean))

    @Test fun cloudBlockedWhenRedactionMissedSomething() =
        assertEquals(CloudDecision.Block("redaction_incomplete"), Policy.cloudDecision(HybridConfig(), true, true, ResidualRisk(1, emptyList(), "")))

    @Test fun cloudNeedsConsent() =
        assertEquals(CloudDecision.Block("consent_required"), Policy.cloudDecision(HybridConfig(), true, false, clean))

    @Test fun cloudAllowedOnlyWithEverything() =
        assertEquals(CloudDecision.Allow, Policy.cloudDecision(HybridConfig(), true, true, clean))

    @Test(expected = PolicyViolation::class) fun nanoAnsweredInCloudIsViolation() = Policy.verifySource(NotesEngine.GEMINI_NANO, false)

    @Test(expected = PolicyViolation::class) fun nanoWithUnknownSourceIsViolation() = Policy.verifySource(NotesEngine.GEMINI_NANO, null)

    @Test(expected = PolicyViolation::class) fun cloudAnsweredOnDeviceIsViolation() = Policy.verifySource(NotesEngine.CLOUD_REDACTED, true)

    @Test fun matchingSourcesPass() {
        Policy.verifySource(NotesEngine.GEMINI_NANO, true)
        Policy.verifySource(NotesEngine.CLOUD_REDACTED, false)
        Policy.verifySource(NotesEngine.GEMMA_LOCAL, null)
    }

    @Test fun nanoChunkIsBounded() {
        assertEquals(1000, Policy.chunkChars(NotesEngine.GEMINI_NANO, HybridConfig(nanoChunkChars = 10)))
        assertEquals(8000, Policy.chunkChars(NotesEngine.GEMINI_NANO, HybridConfig(nanoChunkChars = 99999)))
        assertEquals(3500, Policy.chunkChars(NotesEngine.GEMINI_NANO, HybridConfig()))
    }

    @Test fun nanoLanguageValidation() {
        assertTrue(Policy.nanoValidated("en"))
        assertTrue(Policy.nanoValidated("en-GB"))
        assertTrue(!Policy.nanoValidated("de"))
    }

    @Test fun promptForOnDeviceKeepsNamesAndHasNoPlaceholderRule() {
        val prompt = Prompts.notes("en", MeetingTemplate.PARENT_TEACHER, Synthetic.meeting, redacted = false)
        assertTrue(prompt.contains("Mila"))
        assertTrue(!prompt.contains("Placeholders"))
        assertTrue(prompt.contains("parent-teacher conference"))
    }
}
