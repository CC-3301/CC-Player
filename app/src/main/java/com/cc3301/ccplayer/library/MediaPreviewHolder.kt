package com.cc3301.ccplayer.library

/**
 * 进程内唯一的预览源持有者：抽帧许可只有一个，转屏与界面重建后必须复用同一份。
 *
 * 持有期间缓存、失败退避与无图记忆都常驻，属预期。
 */
class MediaPreviewHolder(private val create: () -> MediaPreviewSource) {

    /** 首次取用时创建，之后一直返回同一个实例。 */
    val instance: MediaPreviewSource by lazy(create)
}
