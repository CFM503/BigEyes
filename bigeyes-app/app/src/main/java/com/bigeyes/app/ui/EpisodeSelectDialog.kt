package com.bigeyes.app.ui

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bigeyes.app.R
import com.bigeyes.app.model.playback.Episode
import com.google.android.material.bottomsheet.BottomSheetDialog

class EpisodeSelectDialog(
    private val context: Context,
    private val episodes: List<Episode>,
    private val currentIndex: Int,
    private val onEpisodeSelected: (Int) -> Unit
) {
    private val dialog = BottomSheetDialog(context)

    init {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_episode_select, null)
        dialog.setContentView(view)

        val tvTitle: TextView = view.findViewById(R.id.tv_episode_dialog_title)
        val btnClose: ImageButton = view.findViewById(R.id.btn_close_episode_dialog)
        val rv: RecyclerView = view.findViewById(R.id.rv_episodes)

        val total = episodes.size
        tvTitle.text = "选集 (共 ${total} 集)"
        btnClose.setOnClickListener { dialog.dismiss() }

        rv.layoutManager = LinearLayoutManager(context)
        rv.adapter = EpisodeAdapter(episodes, currentIndex) { selectedIdx ->
            dialog.dismiss()
            onEpisodeSelected(selectedIdx)
        }

        if (currentIndex in episodes.indices) {
            rv.scrollToPosition(currentIndex)
        }
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
            val isCurrent = position == currentIndex

            holder.tvNum.text = ep.episodeNumber.toString()
            holder.tvTitle.text = ep.displayTitle

            if (isCurrent) {
                holder.tvBadge.visibility = View.VISIBLE
                holder.tvTitle.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.brand_primary))
                holder.tvNum.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.brand_primary))
            } else {
                holder.tvBadge.visibility = View.GONE
                holder.tvTitle.setTextColor(Color.WHITE)
                holder.tvNum.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.text_muted))
            }

            holder.itemView.setOnClickListener {
                onClick(position)
            }
        }
    }
}
