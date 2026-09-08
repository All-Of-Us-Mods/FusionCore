package dev.allofus.fusioncore.ui

import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.highcapable.hikage.core.Hikage
import dev.allofus.fusioncore.R
import dev.allofus.fusioncore.data.AppInfo

class AppViewHolder(
    private val hikage: Hikage
) : RecyclerView.ViewHolder(hikage.root) {
    private val icon = hikage.get<ImageView>(AppItemLayout.ID_ICON)
    private val label = hikage.get<TextView>(AppItemLayout.ID_LABEL)
    private val packageName = hikage.get<TextView>(AppItemLayout.ID_PACKAGE)
    private val version = hikage.get<TextView>(AppItemLayout.ID_VERSION)
    private val folderBtn = hikage.get<ImageButton>(AppItemLayout.ID_FOLDER_BTN)
    private val settingsBtn = hikage.get<ImageButton>(AppItemLayout.ID_SETTINGS_BTN)

    fun bind(
        appInfo: AppInfo,
        onClick: (AppInfo) -> Unit,
        onFolderClick: (AppInfo) -> Unit,
        onSettingsClick: (AppInfo) -> Unit
    ) {
        itemView.setOnClickListener { onClick(appInfo) }
        icon.setImageDrawable(appInfo.icon)
        label.text = appInfo.label
        packageName.text = appInfo.packageName
        version.text = if (appInfo.versionName != null) {
            itemView.context.getString(R.string.version_format, appInfo.versionName, appInfo.versionCode)
        } else {
            appInfo.versionCode.toString()
        }
        folderBtn.setOnClickListener { onFolderClick(appInfo) }
        settingsBtn.setOnClickListener { onSettingsClick(appInfo) }
    }
}