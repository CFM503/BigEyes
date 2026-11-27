package com.bigeyes.app.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bigeyes.app.R
import com.bigeyes.app.model.playback.Episode
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/** 选集: bottom sheet with a grid of episode tiles; the playing one is highlighted. */
class EpisodeSelectDialog(
    private val context: Context,
    private val episodes: List<Episode>,
    private val currentIndex: Int,
    private val onEpisodeSelected: (Int) -> Unit
) {
    companion object {
        private const val COLUMNS = 4
    }

    private val dialog = BottomSheetDialog(context)

    init {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_episode_select, null)
        dialog.setContentView(view)

        val tvSubtitle: TextView = view.findViewById(R.id.tv_episode_dialog_subtitle)
        val btnClose: ImageButton = view.findViewById(R.id.btn_close_episode_dialog)
        val rv: RecyclerView = view.findViewById(R.id.rv_episodes)

        val total = episodes.size
        tvSubtitle.text = if (currentIndex in episodes.indices) {
            "共 ${total} 集 · 正在播放第 ${episodes[currentIndex].episodeNumber} 集"
        } else {
            "共 ${total} 集"
        }
        btnClose.setOnClickListener { dialog.dismiss() }

        rv.layoutManager = GridLayoutManager(context, COLUMNS)
        rv.adapter = EpisodeAdapter(episodes, currentIndex) { selectedIdx ->
            dialog.dismiss()
            onEpisodeSelected(selectedIdx)
        }

        if (currentIndex in episodes.indices) {
            rv.scrollToPosition(currentIndex)
        }

        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
    }

    fun show() {
        dialog.show()
    }

    val isShowing: Boolean
        get() = dialog.isShowing

    fun dismiss() {
        dialog.dismiss()
    }

    private class EpisodeAdapter(
        private val list: List<Episode>,
        private val currentIndex: Int,
        private val onClick: (Int) -> Unit
    ) : RecyclerView.Adapter<EpisodeAdapter.ViewHolder>() {

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvNum: TextView = itemView.findViewById(R.id.tv_item_episode_number)
            val tvTitle: TextView = itemView.findViewById(R.id.tv_item_episode_title)
            val tvBadge: TextView = itemView.findViewById(R.id.tv_item_playing_badge)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_episode_select, parent, false)
            return ViewHolder(v)
        }

        override fun getItemCount(): Int = list.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val ep = list[position]
            val ctx = holder.itemView.context
            val isCurrent = position == currentIndex

            holder.tvNum.text = ep.episodeNumber.toString()

            // Only show a subtitle when the title says more than "第 N 集".
            val title = ep.episodeTitle.trim()
            val isPlainNumber = title.isBlank() ||
                title.replace(" ", "") == "第${ep.episodeNumber}集" ||
                title == ep.episodeNumber.toString()
            if (isPlainNumber) {
                holder.tvTitle.visibility = View.GONE
            } else {
                holder.tvTitle.visibility = View.VISIBLE
                holder.tvTitle.text = title
            }

            holder.itemView.setBackgroundResource(
                if (isCurrent) R.drawable.bg_episode_tile_current else R.drawable.bg_episode_tile
            )
            holder.tvBadge.visibility = if (isCurrent) View.VISIBLE else View.GONE
            holder.tvNum.setTextColor(
                ContextCompat.getColor(ctx, if (isCurrent) R.color.brand_primary else R.color.text_primary)
            )
            holder.itemView.contentDescription = if (isCurrent) "${ep.displayTitle}，正在播放" else ep.displayTitle

            // The episode list never changes while the sheet is open, so the bound position is stable.
            holder.itemView.setOnClickListener {
                onClick(position)
            }
        }
    }
}
