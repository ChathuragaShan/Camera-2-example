package com.chathurangashan.camera2example.viewmodel

import com.chathurangashan.camera2example.repositories.CameraRepository
import javax.inject.Inject

class CameraViewModel @Inject constructor(private val repository: CameraRepository)
    :BaseViewModel(repository) {

    var documentPreviewSizeToCaptureSizeScaleFactor  = 0.0f
}