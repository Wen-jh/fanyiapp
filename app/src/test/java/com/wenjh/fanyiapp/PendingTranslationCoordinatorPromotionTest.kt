package com.wenjh.fanyiapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingTranslationCoordinatorPromotionTest {
    @Test
    fun readyPendingPromotesLongerFinalAfterShorterProvisionalTranslation() {
        val coordinator = PendingTranslationCoordinator()

        coordinator.markInFlight("ありが", provisional = true)
        coordinator.rememberPending("ありがとうございます", provisional = false)

        assertEquals(
            PendingTranslationRequest("ありがとうございます", false),
            coordinator.consumeReadyAfter("ありが")
        )
    }

    @Test
    fun finalPendingForSameTextSurvivesMatchingProvisionalCompletion() {
        val coordinator = PendingTranslationCoordinator()

        coordinator.markInFlight("ありがとう", provisional = true)
        coordinator.rememberPending("ありがとう", provisional = false)

        assertEquals(
            PendingTranslationRequest("ありがとう", false),
            coordinator.consumeReadyAfter("ありがとう")
        )
    }

    @Test
    fun stalePendingForSameFinalTextIsDroppedAfterMatchingCompletion() {
        val coordinator = PendingTranslationCoordinator()

        coordinator.markInFlight("ありがとう", provisional = false)
        coordinator.rememberPending("ありがとう", provisional = false)

        assertNull(coordinator.consumeReadyAfter("ありがとう"))
    }

    @Test
    fun consumeReadyAfterReturnsNullWhenNothingQueued() {
        val coordinator = PendingTranslationCoordinator()
        coordinator.markInFlight("テスト", provisional = false)

        assertNull(coordinator.consumeReadyAfter("別の文"))
    }

    @Test
    fun clearInFlight_preservesPendingCandidate() {
        val coordinator = PendingTranslationCoordinator()
        coordinator.markInFlight("ありが", provisional = true)
        coordinator.rememberPending("ありがとうございます", provisional = false)

        coordinator.requeueInFlight()

        assertEquals(
            PendingTranslationRequest("ありがとうございます", false),
            coordinator.consumeReady()
        )
    }

    @Test
    fun sameLengthFinalFollowUpStillSuppressedWhenQueued() {
        val coordinator = PendingTranslationCoordinator()
        coordinator.markInFlight("ありがとう", provisional = true)
        coordinator.rememberPending("さようなら", provisional = false)

        assertEquals(
            PendingTranslationRequest("さようなら", false),
            coordinator.consumeReadyAfter("ありがとう")
        )
    }
}
