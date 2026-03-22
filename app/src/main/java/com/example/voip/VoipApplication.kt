package com.example.voip

import android.app.Application
import com.example.voip.utils.NsdHelper

class VoipApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NsdHelper.initialize(this)
    }
}