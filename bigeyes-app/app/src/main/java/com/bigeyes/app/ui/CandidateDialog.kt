package com.bigeyes.app.ui

import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import com.bigeyes.app.R
import com.bigeyes.app.browser.CandidateRanker
import com.bigeyes.app.browser.CandidateManager
import com.bigeyes.app.model.VideoCandidate
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class CandidateDialog(
    private val context: Context,
    private val candidates: List<VideoCandidate>,
    private val onClearRequested: (() -> Unit)? = null,
    private val onSelected: (VideoCandidate) -> Unit
) {
    fun show() {
        val listView = ListView(context)
        val adapter = CandidateAdapter(context, candidates)
        listView.adapter = adapter

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.select_candidate_title)
            .setView(listView)
            .setNegativeButton("取消", null)
            .setNeutralButton("清空重探") { _, _ ->
                CandidateManager.clear()
                onClearRequested?.invoke()
            }
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            dialog.dismiss()
            onSelected(candidates[position])
        }

        dialog.show()
    }

    private class CandidateAdapter(
        private val context: Context,
        private val items: List<VideoCandidate>
    ) : BaseAdapter() {

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_candidate, parent, false)
            val candidate = items[position]

            val tvTitle = view.findViewById<TextView>(R.id.tv_item_title)
            val tvTags = view.findViewById<TextView>(R.id.tv_item_tags)
            val tvUrl = view.findViewById<TextView>(R.id.tv_item_url)
            val tvTime = view.findViewById<TextView>(R.id.tv_item_time)

            // The list is already ranked: first entry is the most likely main video.
            val recommended = position == 0 && !candidate.isSuspicious && !candidate.isStale
            if (recommended) {
                tvTitle.text = "【推荐】${candidate.displayTitle}"
                tvTitle.setTextColor(context.getColor(R.color.brand_primary))
            } else {
                tvTitle.text = "【候选 ${position + 1}】${candidate.displayTitle}"
                tvTitle.setTextColor(context.getColor(R.color.text_primary))
            }

            tvTags.text = CandidateRanker.describe(candidate)
            tvTags.setTextColor(
                context.getColor(
                    if (candidate.isSuspicious || candidate.isStale) R.color.text_muted else R.color.brand_secondary
                )
            )

            tvUrl.text = candidate.url
            tvTime.text = "嗅探时间: ${candidate.formattedTime}"

            // Greyed out: kept selectable, but visually de-emphasised.
            view.alpha = if (candidate.isSuspicious || candidate.isStale) 0.6f else 1.0f

            return view
        }
    }
}
