package com.bigeyes.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.bigeyes.app.R
import com.bigeyes.app.browser.CandidateManager
import com.bigeyes.app.browser.CandidateRanker
import com.bigeyes.app.model.VideoCandidate
import com.bigeyes.app.utils.AppPreferences
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Bottom sheet listing the sniffed videos of the current page (already ranked by
 * [CandidateRanker]): the most likely main video is enlarged as 「推荐」, ads/trailers and
 * leftovers from the previous page are dimmed. Stream URLs only appear with 显示技术信息.
 */
class CandidateDialog(
    private val context: Context,
    private val candidates: List<VideoCandidate>,
    private val onClearRequested: (() -> Unit)? = null,
    private val onSelected: (VideoCandidate) -> Unit
) {
    fun show() {
        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.sheet_candidates, null)
        dialog.setContentView(view)

        val showTech = AppPreferences.isShowTechInfo(context)
        view.findViewById<TextView>(R.id.tv_sheet_subtitle).text =
            "在这个页面找到 ${candidates.size} 个视频"

        val container = view.findViewById<LinearLayout>(R.id.container_sheet_items)
        val inflater = LayoutInflater.from(context)
        candidates.forEachIndexed { position, candidate ->
            val item = inflater.inflate(R.layout.item_candidate, container, false)
            bind(item, candidate, position, showTech)
            item.setOnClickListener {
                dialog.dismiss()
                onSelected(candidate)
            }
            container.addView(item)
        }

        view.findViewById<View>(R.id.btn_sheet_secondary).setOnClickListener {
            dialog.dismiss()
            CandidateManager.clear()
            onClearRequested?.invoke()
        }
        view.findViewById<View>(R.id.btn_sheet_cancel).setOnClickListener { dialog.dismiss() }

        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
    }

    private fun bind(item: View, candidate: VideoCandidate, position: Int, showTech: Boolean) {
        val recommended = position == 0 && !candidate.isSuspicious && !candidate.isStale
        val dimmed = candidate.isSuspicious || candidate.isStale

        item.setBackgroundResource(if (recommended) R.drawable.bg_option_recommended else R.drawable.bg_option)
        item.alpha = if (dimmed) 0.6f else 1.0f

        val frameIcon = item.findViewById<View>(R.id.frame_item_icon)
        val ivIcon = item.findViewById<ImageView>(R.id.iv_item_icon)
        frameIcon.setBackgroundResource(if (recommended) R.drawable.bg_icon_tile_surface else R.drawable.bg_icon_tile)
        ivIcon.setImageResource(
            when {
                candidate.isSuspicious -> R.drawable.ic_megaphone
                candidate.isStale -> R.drawable.ic_history
                else -> R.drawable.ic_movie
            }
        )
        ivIcon.imageTintList = ColorStateList.valueOf(
            context.getColor(if (recommended) R.color.brand_primary else R.color.icon_secondary)
        )

        item.findViewById<TextView>(R.id.tv_item_title).text = candidate.displayTitle
        item.findViewById<View>(R.id.tv_item_badge).visibility = if (recommended) View.VISIBLE else View.GONE

        item.findViewById<TextView>(R.id.tv_item_tags).text =
            if (showTech) CandidateRanker.describe(candidate) else CandidateRanker.describeSimple(candidate)

        val tvTech = item.findViewById<TextView>(R.id.tv_item_tech)
        if (showTech) {
            tvTech.visibility = View.VISIBLE
            tvTech.text = "${candidate.url}\n嗅探时间 ${candidate.formattedTime}"
        } else {
            tvTech.visibility = View.GONE
        }
    }
}
