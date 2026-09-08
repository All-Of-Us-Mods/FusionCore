package dev.allofus.fusioncore.ui

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import dev.allofus.fusioncore.data.AppInfo

class AppAdapter(
    private val launch: (AppInfo) -> Unit,
    private val openSettings: (AppInfo) -> Unit
) : RecyclerView.Adapter<AppViewHolder>() {

    private var items = emptyList<AppInfo>()

    fun submitList(newItems: List<AppInfo>) {
        val diff = DiffUtil.calculateDiff(AppDiffCallback(items, newItems))
        items = newItems
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val hikage = AppItemLayout.build().create(parent.context)
        return AppViewHolder(hikage)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(items[position], launch, openSettings)
    }

    override fun getItemCount() = items.size
}