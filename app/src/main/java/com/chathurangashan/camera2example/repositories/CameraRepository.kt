package com.chathurangashan.camera2example.repositories

import com.chathurangashan.camera2example.network.ApiService
import javax.inject.Inject

class CameraRepository @Inject constructor(private val apiService: ApiService) : BaseRepository() {
}