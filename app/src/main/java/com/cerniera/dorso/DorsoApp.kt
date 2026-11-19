package com.cerniera.dorso

import android.app.Application
import com.cerniera.kit.Crash

class DorsoApp : Application() {
    override fun onCreate() { super.onCreate(); Crash.install(this, "Dorso") }
}
