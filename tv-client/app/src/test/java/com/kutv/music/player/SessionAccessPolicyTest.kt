package com.kutv.music.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A2：SessionAccessPolicy 单元测试（JVM，无需模拟器/Media3 运行时）。
 * 覆盖五类控制器识别：自身 / 可信 / 媒体键 fallback / Legacy 白名单 / 未验证第三方拒绝。
 */
class SessionAccessPolicyTest {

    private val own = "com.kutv.music"

    @Test
    fun `own package is allowed`() {
        assertTrue(SessionAccessPolicy.isAllowed(own, own, isTrusted = false, controllerVersion = 1))
    }

    @Test
    fun `trusted controller is allowed`() {
        // TV 遥控媒体键 / 系统媒体框架（isTrusted 由系统 binder 保证，不可伪造）
        assertTrue(SessionAccessPolicy.isAllowed(own, "com.android.systemui", isTrusted = true, controllerVersion = 1))
        assertTrue(SessionAccessPolicy.isAllowed(own, null, isTrusted = true, controllerVersion = 1))
    }

    @Test
    fun `media button fallback without version is rejected`() {
        // P1-3（第十二轮）：controllerVersion==null 不可达（官方 Int，Legacy 返回 0）；
        // 无版本号的非白名单包名不得放行（媒体键由 own/isTrusted 覆盖）
        assertFalse(SessionAccessPolicy.isAllowed(own, "com.some.platform.placeholder", isTrusted = false, controllerVersion = null))
        assertFalse(SessionAccessPolicy.isAllowed(own, null, isTrusted = false, controllerVersion = null))
    }

    @Test
    fun `legacy browser whitelist packages are allowed`() {
        // Legacy MediaBrowserCompat / 平台接口（Play、Assistant、GMS、TV 系统）
        assertTrue(SessionAccessPolicy.isAllowed(own, "com.android.vending", isTrusted = false, controllerVersion = 1))
        assertTrue(SessionAccessPolicy.isAllowed(own, "com.google.android.googlequicksearchbox", isTrusted = false, controllerVersion = 1))
        assertTrue(SessionAccessPolicy.isAllowed(own, "com.google.android.gms", isTrusted = false, controllerVersion = 1))
        assertTrue(SessionAccessPolicy.isAllowed(own, "com.google.android.tvlauncher", isTrusted = false, controllerVersion = 1))
        assertTrue(SessionAccessPolicy.isAllowed(own, "android.media", isTrusted = false, controllerVersion = 1))
    }

    @Test
    fun `unverified third party with version is rejected`() {
        // 未验证第三方：有版本号 + 非白名单 + 非自身 → 拒绝
        assertFalse(SessionAccessPolicy.isAllowed(own, "com.evil.music.app", isTrusted = false, controllerVersion = 1))
        assertFalse(SessionAccessPolicy.isAllowed(own, "com.example.browser", isTrusted = false, controllerVersion = 2))
        assertFalse("无包名但有版本号也拒绝", SessionAccessPolicy.isAllowed(own, null, isTrusted = false, controllerVersion = 1))
    }
}
