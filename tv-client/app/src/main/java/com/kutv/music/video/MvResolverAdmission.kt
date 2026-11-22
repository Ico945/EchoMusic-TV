package com.kutv.music.video

/** Process-wide single resolver slot. A cancelled non-cooperative resolver retains its lease until completion. */
class MvResolverAdmission {
    private var active: Lease? = null

    val isBusy: Boolean
        @Synchronized get() = active != null

    @Synchronized
    fun tryAcquire(): Lease? {
        if (active != null) return null
        return Lease(this).also { active = it }
    }

    @Synchronized
    private fun release(lease: Lease) {
        if (active === lease) active = null
    }

    class Lease internal constructor(private val admission: MvResolverAdmission) {
        private var released = false

        @Synchronized
        fun release() {
            if (released) return
            released = true
            admission.release(this)
        }
    }
}
