package com.kutv.music

import android.view.View
import com.kutv.music.bridge.BridgeRouter

class ControllerLifecycleActivity : MainActivity() {
    override fun createTvWebHost(router: BridgeRouter, routeObserved: (String?) -> Unit): View = View(this)
}
