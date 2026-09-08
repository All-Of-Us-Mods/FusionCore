package dev.allofus.fusioncore.ui

import android.graphics.Color
import android.view.Gravity
import androidx.core.view.setPadding
import com.google.android.material.button.MaterialButton
import com.highcapable.betterandroid.ui.extension.view.textColor
import com.highcapable.hikage.core.base.Hikagable
import com.highcapable.hikage.core.builder.HikageBuilder
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.widget.android.widget.FrameLayout
import com.highcapable.hikage.widget.android.widget.LinearLayout
import com.highcapable.hikage.widget.androidx.appcompat.widget.AppCompatImageView
import com.highcapable.hikage.widget.com.google.android.material.button.MaterialButton
import com.highcapable.hikage.widget.com.google.android.material.card.MaterialCardView
import com.highcapable.hikage.widget.com.google.android.material.textview.MaterialTextView
import dev.allofus.fusioncore.R

object AppItemLayout : HikageBuilder {
    const val ID_CARD = "card"
    const val ID_ICON = "icon"
    const val ID_LABEL = "label"
    const val ID_PACKAGE = "package_name"
    const val ID_VERSION = "version"
    const val ID_PLAY_BTN = "play"

    override fun build() = Hikagable {
        FrameLayout(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            MaterialCardView(
                id = ID_CARD,
                lparams = LayoutParams(widthMatchParent = true) {
                    setMargins(8.dp, 4.dp, 8.dp, 4.dp)
                }
            ) {
                LinearLayout(
                    lparams = LayoutParams(widthMatchParent = true),
                    init = {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(12.dp)
                    }
                ) {
                    AppCompatImageView(
                        id = ID_ICON,
                        lparams = LayoutParams(width = 64.dp, height = 64.dp)
                    )

                    LinearLayout(
                        lparams = LayoutParams(width = 0) { weight = 1f },
                        init = {
                            orientation = android.widget.LinearLayout.VERTICAL
                            setPadding(8.dp, 0.dp, 8.dp, 0.dp)
                        },
                    ) {
                        MaterialTextView(id = ID_LABEL)
                        MaterialTextView(id = ID_PACKAGE) { textSize = 12f; textColor = Color.GRAY }
                        MaterialTextView(id = ID_VERSION) { textSize = 12f; textColor = Color.GRAY }
                    }

                    MaterialButton(
                        id = ID_PLAY_BTN,
                        lparams = LayoutParams(width = 48.dp, height = 48.dp),
                    ) {
                        iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                        iconPadding = 0.dp
                        setPadding(0.dp)
                        setIconResource(R.drawable.ic_play)
                    }
                }
            }
        }
    }
}