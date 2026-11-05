package com.glasssign.app.ui.update

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** 固定的 key：宿主与卡片都调 [rememberHoshioriUpdateViewModel]，拿到同一个实例。 */
const val UPDATE_VIEW_MODEL_KEY = "hoshiori-update"

/**
 * 更新功能的唯一入口。
 *
 * `UpdateViewModel` 的构造器需要参数，不能直接 `viewModel<UpdateViewModel>()`，
 * 所以这里用固定 key + 现有 factory 取实例；宿主（`HoshioriScreen`）与设置页卡片都调它，
 * 自动检查的结果因此直接显示在设置页，不会出现两份状态。
 */
@Composable
fun rememberHoshioriUpdateViewModel(): UpdateViewModel {
    val context = LocalContext.current.applicationContext
    return viewModel(key = UPDATE_VIEW_MODEL_KEY, factory = hoshioriUpdateViewModelFactory(context))
}

/** 非 Compose 场景（宿主自己持有 [ViewModelStore]）用这个工厂取同一实例。 */
fun hoshioriUpdateViewModelFactory(context: Context): ViewModelProvider.Factory {
    val app = context.applicationContext
    return object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            UpdateViewModel.production(app) as T
    }
}
