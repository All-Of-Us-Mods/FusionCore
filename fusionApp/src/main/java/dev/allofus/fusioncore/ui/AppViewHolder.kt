package dev.allofus.fusioncore.ui

import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.highcapable.hikage.core.Hikage
import dev.allofus.fusioncore.R
import dev.allofus.fusioncore.data.AppInfo

class AppViewHolder(
    hikage: Hikage
) : RecyclerView.ViewHolder(hikage.root) {
    private val card = hikage.get<MaterialCardView>(AppItemLayout.ID_CARD)
    private val icon = hikage.get<ImageView>(AppItemLayout.ID_ICON)
    private val label = hikage.get<TextView>(AppItemLayout.ID_LABEL)
    private val packageName = hikage.get<TextView>(AppItemLayout.ID_PACKAGE)
    private val version = hikage.get<TextView>(AppItemLayout.ID_VERSION)
    private val playBtn = hikage.get<MaterialButton>(AppItemLayout.ID_PLAY_BTN)

    fun bind(
        appInfo: AppInfo,
        launch: (AppInfo) -> Unit,
        openSettings: (AppInfo) -> Unit
    ) {
        card.setOnClickListener { openSettings(appInfo) }
        icon.setImageDrawable(appInfo.icon)
        label.text = appInfo.label
        packageName.text = appInfo.packageName
        version.text = if (appInfo.versionName != null) {
            itemView.context.getString(R.string.version_format, appInfo.versionName, appInfo.versionCode)
        } else {
            appInfo.versionCode.toString()
        }
        playBtn.setOnClickListener { launch(appInfo) }
    }
}