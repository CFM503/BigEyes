package com.bigeyes.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.bigeyes.app.R
import com.bigeyes.app.model.DlnaDevice
import com.bigeyes.app.utils.AppPreferences
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Bottom sheet for choosing the TV. BigEyesTV receivers come first and the first one is
 * 「推荐」, with a plain-language note on what it adds over ordinary DLNA casting.
 * IP / port only appear with 显示技术信息.
 */
class DeviceSelectDialog(
    private val context: Context,
    private val devices: List<DlnaDevice>,
    private val onManualAdd: (() -> Unit)? = null,
    private val onSelected: (DlnaDevice) -> Unit
) {
    fun show() {
        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.sheet_devices, null)
        dialog.setContentView(view)

        val showTech = AppPreferences.isShowTechInfo(context)
        // Stable sort: BigEyesTV receivers first, original order otherwise.
        val ordered = devices.sortedByDescending { it.isBigEyesTv }
        val recommended = ordered.firstOrNull { it.isBigEyesTv }

        view.findViewById<TextView>(R.id.tv_sheet_subtitle).text =
            "在同一 Wi-Fi 下找到 ${ordered.size} 台设备"

        val container = view.findViewById<LinearLayout>(R.id.container_sheet_items)
        val inflater = LayoutInflater.from(context)
        for (device in ordered) {
            val item = inflater.inflate(R.layout.item_device, container, false)
            bind(item, device, device === recommended, showTech)
            item.setOnClickListener {
                dialog.dismiss()
                onSelected(device)
            }
            container.addView(item)
        }

        val btnManual = view.findViewById<View>(R.id.btn_sheet_secondary)
        if (onManualAdd != null) {
            btnManual.setOnClickListener {
                dialog.dismiss()
                onManualAdd.invoke()
            }
        } else {
            btnManual.visibility = View.GONE
        }
        view.findViewById<View>(R.id.btn_sheet_cancel).setOnClickListener { dialog.dismiss() }

        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
    }

    private fun bind(item: View, device: DlnaDevice, recommended: Boolean, showTech: Boolean) {
        item.setBackgroundResource(if (recommended) R.drawable.bg_option_recommended else R.drawable.bg_option)

        val frameIcon = item.findViewById<View>(R.id.frame_item_icon)
        val ivIcon = item.findViewById<ImageView>(R.id.iv_item_icon)
        frameIcon.setBackgroundResource(if (recommended) R.drawable.bg_icon_tile_surface else R.drawable.bg_icon_tile)
        ivIcon.setImageResource(R.drawable.ic_tv)
        ivIcon.imageTintList = ColorStateList.valueOf(
            context.getColor(if (device.isBigEyesTv) R.color.brand_primary else R.color.icon_secondary)
        )

        item.findViewById<TextView>(R.id.tv_item_title).text = device.name
        item.findViewById<View>(R.id.tv_item_badge).visibility = if (recommended) View.VISIBLE else View.GONE

        item.findViewById<TextView>(R.id.tv_item_tags).text = if (device.isBigEyesTv) {
            "BigEyesTV · 支持选集、自动下一集、断点续播"
        } else {
            "普通投屏 · 仅播放当前视频"
        }

        val tvTech = item.findViewById<TextView>(R.id.tv_item_tech)
        if (showTech) {
            tvTech.visibility = View.VISIBLE
            tvTech.text = if (device.isBigEyesTv) {
                "IP ${device.ip} · 端口 ${device.apiPort}"
            } else {
                "IP ${device.ip}\n${device.locationUrl}"
            }
        } else {
            tvTech.visibility = View.GONE
        }

        item.findViewById<View>(R.id.iv_item_check).visibility = if (device.selected) View.VISIBLE else View.GONE
    }
}
