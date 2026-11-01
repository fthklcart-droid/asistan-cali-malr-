package com.fkartmedia.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PricingTest {
    @Test fun opusCost() = assertEquals(6.0, Pricing.cost("claude-opus-5-5", 1_000_000, 100_000)!!, 1e-9)
    @Test fun webSearchAdds() = assertEquals(0.03, Pricing.cost("claude-haiku-5-5", 0, 0, webSearches = 3)!!, 1e-9)
    @Test fun unknownModel() = assertNull(Pricing.cost("bilinmeyen", 1, 1))
    @Test fun prefixMatch() = assertEquals(2.0, Pricing.cost("claude-sonnet-5-5-x", 1_000_000, 0)!!, 1e-9)
}
