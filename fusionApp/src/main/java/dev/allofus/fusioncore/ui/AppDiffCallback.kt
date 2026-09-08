package dev.allofus.fusioncore.ui

import androidx.recyclerview.widget.DiffUtil
import dev.allofus.fusioncore.data.AppInfo

class AppDiffCallback(
    private val oldList: List<AppInfo>,
    private val newList: List<AppInfo>
) : DiffUtil.Callback() {

    override fun getOldListSize() = oldList.size
    override fun getNewListSize() = newList.size

    override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
        oldList[oldPos].packageName == newList[newPos].packageName

    override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
        oldList[oldPos] == newList[newPos]
}