package dev.yu.mwi.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginStateOwnershipPolicyTest {
    @Test
    void unknownLoginLiveMayOnlyBootstrapTheFirstProfileForAShare() {
        assertTrue(LoginStateOwnershipPolicy.mayBootstrapUnknownLive(false));
        assertFalse(LoginStateOwnershipPolicy.mayBootstrapUnknownLive(true));
    }
}
