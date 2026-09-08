package dev.allofus.fusioncore.ui

import android.view.Gravity
import com.highcapable.hikage.core.base.Hikagable
import com.highcapable.hikage.core.builder.HikageBuilder
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.widget.android.widget.ImageButton
import com.highcapable.hikage.widget.android.widget.ImageView
import com.highcapable.hikage.widget.android.widget.LinearLayout
import com.highcapable.hikage.widget.android.widget.TextView
import dev.allofus.fusioncore.R

object AppItemLayout : HikageBuilder {
    const val ID_ICON = "icon"
    const val ID_LABEL = "label"
    const val ID_PACKAGE = "package_name"
    const val ID_VERSION = "version"
    const val ID_FOLDER_BTN = "folder_btn"
    const val ID_SETTINGS_BTN = "settings_btn"

    override fun build() = Hikagable {
        LinearLayout(
            lparams = LayoutParams(widthMatchParent = true) {  },
            init = {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16.dp, 8.dp, 16.dp, 8.dp)
            }
        ) {
            ImageView(id = ID_ICON, lparams = LayoutParams(width = 48.dp, height = 48.dp))

            LinearLayout(
                lparams = LayoutParams(width = 0) { weight = 1f },
                init = {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(8.dp, 0.dp, 8.dp, 0.dp)
                },
            ) {
                TextView(id = ID_LABEL)
                TextView(id = ID_PACKAGE)
                TextView(id = ID_VERSION)
            }

            ImageButton(id = ID_FOLDER_BTN) { setImageResource(R.drawable.ic_folder) }
            ImageButton(id = ID_SETTINGS_BTN) { setImageResource(R.drawable.ic_settings) }
        }
    }
}