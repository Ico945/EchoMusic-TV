package com.kutv.music.application.plugins

data class TvPluginInfo(
    val id: String,
    val version: String,
    val entry: String,
    val capabilities: List<String>,
    val bridgeScopes: List<String>,
)

/**
 * Scans build-time local plugin manifests under [baseDir] (each directory owns
 * a `manifest.json`). Rejects path traversal, duplicate ids (the later wins the
 * reject), non-local entries, unknown scopes, and manifests lacking the
 * `android-tv-v1` capability. When the compatible-plugins switch is off the
 * registry returns an empty list and retains [disabledReason].
 */
class TvPluginRegistry(
    private val assetReader: (relativePath: String) -> String?,
    private val baseDir: String = "tv-plugins",
    private val directoryLister: (relativePath: String) -> List<String> = { emptyList() },
    private val compatiblePluginsEnabled: () -> Boolean = { true },
) {
    var disabledReason: String? = null
        private set

    fun list(): List<TvPluginInfo> {
        disabledReason = null
        if (!compatiblePluginsEnabled()) {
            disabledReason = DISABLED_REASON
            return emptyList()
        }
        val directories = runCatching { directoryLister(baseDir).orEmpty() }.getOrDefault(emptyList())
        val result = mutableListOf<TvPluginInfo>()
        val seenIds = mutableSetOf<String>()
        for (directory in directories) {
            if (!isSafeDirectoryName(directory)) continue
            val raw = assetReader("$baseDir/$directory/manifest.json") ?: continue
            val manifest = TvPluginManifest.parse(raw).getOrNull() ?: continue
            if (!seenIds.add(manifest.id)) continue
            result.add(
                TvPluginInfo(
                    id = manifest.id,
                    version = manifest.version,
                    entry = manifest.entry,
                    capabilities = manifest.capabilities,
                    bridgeScopes = manifest.bridgeScopes,
                ),
            )
        }
        return result
    }

    /**
     * Validates [action] against the manifest-declared [TvPluginManifest.bridgeScopes]
     * whitelist. The first version ships no executable plugins, so an allowed
     * action still resolves to [UnsupportedOperationException]; the whitelist
     * check itself is exercised by tests through injected fake manifests.
     */
    fun invoke(id: String, action: String): Result<Unit> {
        val info = list().firstOrNull { it.id == id }
            ?: return Result.failure(UnsupportedOperationException(PLUGIN_UNAVAILABLE))
        if (action !in info.bridgeScopes) {
            return Result.failure(IllegalArgumentException("动作不在插件白名单"))
        }
        return Result.failure(UnsupportedOperationException(PLUGIN_UNAVAILABLE))
    }

    private fun isSafeDirectoryName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." &&
            !name.contains('/') && !name.contains('\\')

    companion object {
        const val DISABLED_REASON = "compatible-plugins-disabled"
        const val PLUGIN_UNAVAILABLE = "插件不可用"
    }
}
