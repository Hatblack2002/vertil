package com.vertil

import android.app.Application
import com.vertil.core.log.VertilLog
import com.vertil.di.ServiceLocator

class VertilApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        VertilLog.i("VertilApp", "VERTIL iniciado — core listo")
    }
}
