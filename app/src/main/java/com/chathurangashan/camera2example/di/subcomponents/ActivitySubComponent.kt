package com.chathurangashan.camera2example.di.subcomponents

import com.chathurangashan.camera2example.ui.activities.MainActivity
import com.chathurangashan.camera2example.di.scopes.ActivityScope
import com.chathurangashan.camera2example.di.modules.ActivityModule
import dagger.BindsInstance
import dagger.Subcomponent

@ActivityScope
@Subcomponent(modules = [ActivityModule::class])
interface ActivitySubComponent {

    @Subcomponent.Factory
    interface Factory{
        fun create(@BindsInstance activity: MainActivity,
                   @BindsInstance hostFragment: Int): ActivitySubComponent
    }

    fun inject(MainActivity: MainActivity)
}