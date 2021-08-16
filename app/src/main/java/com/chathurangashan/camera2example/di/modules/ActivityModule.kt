package com.chathurangashan.camera2example.di.modules

import androidx.navigation.fragment.NavHostFragment
import com.chathurangashan.camera2example.ui.activities.MainActivity
import com.chathurangashan.camera2example.di.scopes.ActivityScope
import dagger.Module
import dagger.Provides

@Module
class ActivityModule {

    @ActivityScope
    @Provides
    fun navigationController(navHostFragment: NavHostFragment) =
        navHostFragment.navController

    @ActivityScope
    @Provides
    fun navigationFragment(activity: MainActivity, hosFragment: Int) =
        activity.supportFragmentManager.findFragmentById(hosFragment) as NavHostFragment
}