package com.kutv.music.direct.content

import org.json.JSONObject

/**
 * Loads the desensitized detail/comment fixtures shared by the mapper and
 * domain tests. Fixtures are fake (example.com hosts, placeholder hashes) and
 * never touch the network.
 */
object KugouDetailFixtures {
    private val root: JSONObject by lazy {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("kugou/detail-fixtures.json")) {
            "missing kugou/detail-fixtures.json on the unit-test classpath"
        }
        JSONObject(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
    }

    fun get(name: String): JSONObject = root.optJSONObject(name) ?: JSONObject()
}
