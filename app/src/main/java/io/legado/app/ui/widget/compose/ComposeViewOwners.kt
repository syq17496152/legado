package io.legado.app.ui.widget.compose

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

fun ComposeView.installViewTreeOwnersFrom(
    anchor: View,
    fallbackContext: Context = context
) {
    val ownerContext = fallbackContext.findOwnerContext()
    val lifecycleOwner = anchor.findViewTreeLifecycleOwner()
        ?: ownerContext as? LifecycleOwner
    val viewModelStoreOwner = anchor.findViewTreeViewModelStoreOwner()
        ?: ownerContext as? ViewModelStoreOwner
    val savedStateRegistryOwner = anchor.findViewTreeSavedStateRegistryOwner()
        ?: ownerContext as? SavedStateRegistryOwner
    lifecycleOwner?.let { setViewTreeLifecycleOwner(it) }
    viewModelStoreOwner?.let { setViewTreeViewModelStoreOwner(it) }
    savedStateRegistryOwner?.let { setViewTreeSavedStateRegistryOwner(it) }
}

private tailrec fun Context.findOwnerContext(): Context {
    return when (this) {
        is LifecycleOwner, is ViewModelStoreOwner, is SavedStateRegistryOwner -> this
        is ContextWrapper -> baseContext.findOwnerContext()
        else -> this
    }
}
