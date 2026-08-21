package com.bigeyes.app.ui

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.bigeyes.app.R
import com.bigeyes.app.browser.BookmarkManager
import com.bigeyes.app.model.Bookmark
import com.bigeyes.app.utils.AppPreferences
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object BookmarkDialog {

    fun show(
        activity: Activity,
        currentUrl: String?,
        currentTitle: String?,
        onBookmarkSelected: (String) -> Unit
    ) {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_bookmarks, null)
        val container = dialogView.findViewById<LinearLayout>(R.id.container_bookmark_items)
        val tvEmpty = dialogView.findViewById<TextView>(R.id.tv_empty_bookmarks)
        val btnAddCurrent = dialogView.findViewById<Button>(R.id.btn_add_current_page)
        val btnRestore = dialogView.findViewById<Button>(R.id.btn_restore_default_bookmarks)
        val btnClose = dialogView.findViewById<Button>(R.id.btn_close_dialog)

        var dialog: AlertDialog? = null

        fun renderBookmarks() {
            container.removeAllViews()
            val bookmarks = BookmarkManager.getBookmarks(activity)
            if (bookmarks.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
            } else {
                tvEmpty.visibility = View.GONE
                val currentHomeUrl = AppPreferences.getHomepageUrl(activity).trimEnd('/')
                val inflater = LayoutInflater.from(activity)

                for (bm in bookmarks) {
                    val itemView = inflater.inflate(R.layout.item_bookmark, container, false)
                    val tvTitle = itemView.findViewById<TextView>(R.id.tv_bookmark_title)
                    val tvUrl = itemView.findViewById<TextView>(R.id.tv_bookmark_url)
                    val ivIcon = itemView.findViewById<ImageView>(R.id.iv_bookmark_icon)
                    val btnSetHome = itemView.findViewById<ImageButton>(R.id.btn_set_as_home)
                    val btnEdit = itemView.findViewById<ImageButton>(R.id.btn_edit_bookmark)
                    val btnDelete = itemView.findViewById<ImageButton>(R.id.btn_delete_bookmark)
                    val layoutInfo = itemView.findViewById<LinearLayout>(R.id.layout_bookmark_info)

                    tvTitle.text = bm.title
                    tvUrl.text = bm.url

                    val isHome = bm.url.trimEnd('/') == currentHomeUrl
                    if (isHome) {
                        btnSetHome.setColorFilter(activity.getColor(R.color.brand_primary))
                    } else {
                        btnSetHome.setColorFilter(0xFF888888.toInt())
                    }

                    // Click to navigate
                    layoutInfo.setOnClickListener {
                        dialog?.dismiss()
                        onBookmarkSelected(bm.url)
                    }

                    // Set as home
                    btnSetHome.setOnClickListener {
                        AppPreferences.setHomepageUrl(activity, bm.url)
                        Toast.makeText(activity, "已将「${bm.title}」设为默认主页", Toast.LENGTH_SHORT).show()
                        renderBookmarks()
                    }

                    // Edit bookmark
                    btnEdit.setOnClickListener {
                        showEditBookmarkDialog(activity, bm) {
                            renderBookmarks()
                        }
                    }

                    // Delete bookmark
                    btnDelete.setOnClickListener {
                        MaterialAlertDialogBuilder(activity)
                            .setTitle("删除书签")
                            .setMessage("确定删除书签「${bm.title}」吗？")
                            .setPositiveButton("删除") { _, _ ->
                                BookmarkManager.removeBookmark(activity, bm.id)
                                Toast.makeText(activity, "已删除书签", Toast.LENGTH_SHORT).show()
                                renderBookmarks()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }

                    container.addView(itemView)
                }
            }
        }

        // Add current page action
        val cleanCurrentUrl = currentUrl?.takeIf {
            it.isNotBlank() && !it.startsWith("about:") && !it.startsWith("javascript:")
        }
        if (cleanCurrentUrl != null) {
            val isAlreadyBookmarked = BookmarkManager.isBookmarked(activity, cleanCurrentUrl)
            if (isAlreadyBookmarked) {
                btnAddCurrent.text = "✓ 当前页面已收藏 (点击管理)"
                btnAddCurrent.setOnClickListener {
                    Toast.makeText(activity, "当前网页已在书签列表中", Toast.LENGTH_SHORT).show()
                }
            } else {
                btnAddCurrent.text = "⭐ 收藏当前网页"
                btnAddCurrent.setOnClickListener {
                    showAddBookmarkDialog(activity, cleanCurrentUrl, currentTitle) {
                        renderBookmarks()
                        btnAddCurrent.text = "✓ 当前页面已收藏"
                    }
                }
            }
        } else {
            btnAddCurrent.text = "⭐ 添加自定义书签"
            btnAddCurrent.setOnClickListener {
                showAddBookmarkDialog(activity, "https://", "") {
                    renderBookmarks()
                }
            }
        }

        // Restore default bookmarks
        btnRestore.setOnClickListener {
            MaterialAlertDialogBuilder(activity)
                .setTitle("恢复预设站点")
                .setMessage("是否重置并加载预设常用影视与视频站点书签（腾讯视频、爱奇艺、优酷、芒果TV、B站等）？")
                .setPositiveButton("恢复") { _, _ ->
                    BookmarkManager.resetToDefaults(activity)
                    Toast.makeText(activity, "已恢复预设书签", Toast.LENGTH_SHORT).show()
                    renderBookmarks()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        renderBookmarks()

        dialog = MaterialAlertDialogBuilder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showAddBookmarkDialog(
        context: Context,
        initialUrl: String,
        initialTitle: String?,
        onSaved: () -> Unit
    ) {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
        }

        val etTitle = EditText(context).apply {
            hint = "书签名 (例如: 腾讯视频)"
            setText(initialTitle?.takeIf { it.isNotBlank() } ?: "")
            setSingleLine()
            setPadding(24, 24, 24, 24)
        }

        val etUrl = EditText(context).apply {
            hint = "网址 (https://...)"
            setText(initialUrl)
            setSingleLine()
            setPadding(24, 24, 24, 24)
        }

        layout.addView(etTitle)
        layout.addView(etUrl)

        MaterialAlertDialogBuilder(context)
            .setTitle("添加书签")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val title = etTitle.text.toString().trim()
                var url = etUrl.text.toString().trim()
                if (url.isNotBlank()) {
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        url = "https://$url"
                    }
                    val finalTitle = title.ifEmpty { url }
                    BookmarkManager.addBookmark(context, finalTitle, url)
                    Toast.makeText(context, "已成功添加书签「$finalTitle」", Toast.LENGTH_SHORT).show()
                    onSaved()
                } else {
                    Toast.makeText(context, "网址不能为空", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showEditBookmarkDialog(
        context: Context,
        bookmark: Bookmark,
        onSaved: () -> Unit
    ) {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
        }

        val etTitle = EditText(context).apply {
            hint = "书签名"
            setText(bookmark.title)
            setSingleLine()
            setPadding(24, 24, 24, 24)
        }

        val etUrl = EditText(context).apply {
            hint = "网址"
            setText(bookmark.url)
            setSingleLine()
            setPadding(24, 24, 24, 24)
        }

        layout.addView(etTitle)
        layout.addView(etUrl)

        MaterialAlertDialogBuilder(context)
            .setTitle("编辑书签")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val title = etTitle.text.toString().trim()
                var url = etUrl.text.toString().trim()
                if (url.isNotBlank()) {
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        url = "https://$url"
                    }
                    val finalTitle = title.ifEmpty { url }
                    BookmarkManager.updateBookmark(context, bookmark.id, finalTitle, url)
                    Toast.makeText(context, "书签已更新", Toast.LENGTH_SHORT).show()
                    onSaved()
                } else {
                    Toast.makeText(context, "网址不能为空", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
