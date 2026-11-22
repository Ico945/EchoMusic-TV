package com.kutv.music.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KugouCredentialStoreJvmTest {
    @Test
    fun `memory credential store saves loads and clears complete envelope`() {
        val store = MemoryKugouCredentialStore()
        val credentials = KugouCredentials("42", "token", "vip", 1)
        val account = KugouAccount("42", "测试用户")

        store.save(credentials, account)
        assertEquals(credentials, store.loadCredentials())
        assertEquals(account, store.loadAccount())

        store.clear()
        assertNull(store.loadCredentials())
        assertNull(store.loadAccount())
    }

    @Test
    fun `legacy numeric fallback is never exposed by credential store`() {
        val store = MemoryKugouCredentialStore()
        val credentials = KugouCredentials("408615481", "token")

        store.save(credentials, KugouAccount(credentials.userId, "用户${credentials.userId}"))

        assertEquals(KUGOU_PRIVATE_DISPLAY_NAME, store.loadAccount()?.nickname)
    }
}
