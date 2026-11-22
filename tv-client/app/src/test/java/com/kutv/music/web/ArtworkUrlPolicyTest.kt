package com.kutv.music.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkUrlPolicyTest {
    @Test fun `allows the observed artist CDN without admitting subdomains or secrets`() {
        assertEquals("https://singerimg.kugou.com/uploadpic/softhead/400/a.jpg",
            ArtworkUrlPolicy.normalize("https://singerimg.kugou.com/uploadpic/softhead/400/a.jpg"))
        listOf(
            "https://evil.singerimg.kugou.com/a.jpg", "http://singerimg.kugou.com/a.jpg",
            "https://singerimg.kugou.com/a.jpg?token=secret", "https://singerimg.kugou.com:443/a.jpg",
            "https://user@singerimg.kugou.com/a.jpg", "https://singerimg.kugou.com/a.jpg#secret",
        ).forEach { assertNull(ArtworkUrlPolicy.normalize(it)) }
    }
    @Test
    fun `allows only exact https Kugou image CDN host`() {
        assertEquals(
            "https://imge.kugou.com/400/cover.jpg",
            ArtworkUrlPolicy.normalize("https://imge.kugou.com/400/cover.jpg"),
        )
        assertNull(ArtworkUrlPolicy.normalize("https://login-user.kugou.com/avatar.jpg"))
        assertNull(ArtworkUrlPolicy.normalize("https://evil.imge.kugou.com/avatar.jpg"))
        assertNull(ArtworkUrlPolicy.normalize("http://imge.kugou.com/avatar.jpg"))
        assertNull(ArtworkUrlPolicy.normalize("https://imge.kugou.com/avatar.jpg?token=secret"))
    }
}
