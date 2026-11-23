package com.saas.omnicanal.whatsapp;

import static org.junit.jupiter.api.Assertions.*;

import com.saas.omnicanal.whatsapp.model.ChatIds;
import org.junit.jupiter.api.Test;

class ChatIdsTests {
    @Test
    void normalizesPhonesForBothProviders() {
        assertEquals("51987654321", ChatIds.normalizePhone("+51 987 654 321"));
        assertEquals("51987654321", ChatIds.normalizePhone("51-987-654-321"));
        assertNull(ChatIds.normalizePhone("987"));
        assertNull(ChatIds.normalizePhone("51abc"));
        assertEquals("51987654321@s.whatsapp.net", ChatIds.fromPhone("51987654321"));
        assertTrue(ChatIds.isPhoneChat("51987654321@s.whatsapp.net"));
        assertEquals("51987654321", ChatIds.phoneOf("51987654321@s.whatsapp.net"));
    }
}
