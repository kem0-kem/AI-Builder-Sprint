package com.apptive.slowtalk

import android.app.Application
import com.apptive.slowtalk.data.auth.AuthSession

class SlowTalkApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AuthSession.initialize(this)
    }
}
