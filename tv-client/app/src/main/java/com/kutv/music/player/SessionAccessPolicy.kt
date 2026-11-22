package com.kutv.music.player

/**
 * A2：Media3 系统控制器访问策略（纯函数，JVM 可测）。
 *
 * 安全说明：MusicService 不声明绑定权限（官方注册方式，系统媒体框架绑定由平台处理）；
 * 第三方控制器由 onGetSession 策略拒绝（own / isTrusted / 平台包名白名单）。
 *
 * 放行：
 *  1) 自身包名（应用内 MediaController / MediaButtonReceiver——媒体键路径由此覆盖）
 *  2) 可信控制器（isTrusted：TV 遥控媒体键 / 系统媒体框架，系统 binder 不可伪造）
 *  3) Legacy MediaBrowserCompat / 平台接口白名单包名（Play、Assistant、TV 系统等）
 * 拒绝：其余未验证第三方（有版本号 + 非白名单 + 非自身）。
 */
object SessionAccessPolicy {

    val SYSTEM_MEDIA_PACKAGES = setOf(
        "com.google.android.tvlauncher",
        "com.google.android.leanbacklauncher",
        "com.google.android.apps.tv.launcherx",
        "com.android.systemui",
        "com.google.android.media",
        "com.google.android.media.tv",
        "com.google.android.tv.remote",
        "com.google.android.as",
        // Media3 / 平台 MediaBrowser service 接口（媒体键占位路径）
        "android.media",
        "android.media.tv",
        "com.google.android.apps.mediashell",
        "com.google.android.gms",
        "com.google.android.tv",
        "com.android.tv",
        // Play 商店 / Google Assistant（系统媒体键与语音媒体路由）
        "com.android.vending",
        "com.google.android.googlequicksearchbox",
        "android",
    )

    fun isAllowed(
        ownPackage: String,
        pkg: String?,
        isTrusted: Boolean,
        controllerVersion: Int?,
        isPackageNameVerified: Boolean = false,
    ): Boolean {
        if (pkg == ownPackage) return true
        // P1-2（第十四轮）：isPackageNameVerified 仅表示包名归属该 UID，不代表控制权限——不单独放行
        // （真正的系统信任由 isTrusted 表示；verified 可作为未来白名单的加强校验）
        if (isTrusted) return true
        // P1-3（第十二轮）：controllerVersion==null 不可达（官方为 Int，Legacy 返回 0）——已移除该放行分支
        // 媒体键路径由 own（MediaButtonReceiver）/ isTrusted（TV 遥控系统 binder）覆盖——
        // connectionHints 由控制器端 setConnectionHints 透传可伪造，绝不作为放行依据（security HIGH 修复）
        // Legacy MediaBrowserCompat / 平台接口白名单
        if (pkg != null && pkg in SYSTEM_MEDIA_PACKAGES) return true
        return false
    }
}
