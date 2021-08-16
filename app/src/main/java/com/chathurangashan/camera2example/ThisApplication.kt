package com.chathurangashan.camera2example

import android.app.Application
import com.chathurangashan.camera2example.data.enums.BuildType
import com.chathurangashan.camera2example.di.DaggerApplicationComponent
import com.chathurangashan.camera2example.di.InjectorProvider

class ThisApplication : Application(),InjectorProvider {

    companion object {
        val buildType: BuildType = BuildType.RELEASE
    }

    override val component by lazy {
        DaggerApplicationComponent.factory().create(applicationContext)
    }

    override fun onCreate() {
        super.onCreate()
    }
}