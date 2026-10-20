package com.airkeyboard.ui.bookmarks

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.airkeyboard.R
import com.airkeyboard.databinding.ActivityBookmarksBinding
import com.airkeyboard.databinding.DialogBookmarkBinding
import com.airkeyboard.network.AirKeyboardClient
import com.airkeyboard.ui.common.RemoteKeys
import com.airkeyboard.ui.common.glassToast
import com.airkeyboard.ui.remotes.RemoteAdapter
import com.airkeyboard.ui.remotes.sendPcSystem

/** Websites to open in the PC's browser. Tap opens, long-press edits or deletes. */
class BookmarksActivity : AppCompatActivity() {
    private lateinit var binding: ActivityBookmarksBinding
    private var host: String = ""
    private var port: Int = AirKeyboardClient.DEFAULT_PORT
    private var bookmarks = listOf<Bookmark>()

    private val bookmarkAdapter by lazy {
        RemoteAdapter<Bookmark>(
            title = { it.name },
            subtitle = { it.url },
            onLongClick = ::showBookmarkActions,
            look = RemoteAdapter.Look.BOOKMARK,
            onExecute = ::open
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBookmarksBinding.inflate(layoutInflater)
        setContentView(binding.root)

        host = intent.getStringExtra("host") ?: ""
        port = intent.getIntExtra("port", AirKeyboardClient.DEFAULT_PORT)

        binding.apply {
            tvConnectionStatus.text = "$host:$port"
            // Pressed rows stay inside the card's rounded corners
            cardBookmarks.clipToOutline = true
            rvBookmarks.layoutManager = LinearLayoutManager(this@BookmarksActivity)
            rvBookmarks.adapter = bookmarkAdapter
            btnAdd.setOnClickListener { showEditDialog(null) }
            btnBack.setOnClickListener { finish() }
        }
        show(BookmarkStore.load(this))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (RemoteKeys.onKeyDown(this, keyCode)) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun show(list: List<Bookmark>) {
        bookmarks = list
        bookmarkAdapter.submitList(list)
        binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun update(list: List<Bookmark>) {
        BookmarkStore.save(this, list)
        show(list)
    }

    private fun open(bookmark: Bookmark) {
        // A bookmark saved before the length check existed
        if (BookmarkStore.isUrlTooLong(bookmark.url)) {
            glassToast(R.string.bookmark_url_too_long)
            return
        }
        if (sendPcSystem("url ${bookmark.url}")) {
            glassToast(getString(R.string.opening_on_pc, bookmark.name))
        }
    }

    private fun showBookmarkActions(bookmark: Bookmark) {
        AlertDialog.Builder(this)
            .setTitle(bookmark.name)
            .setItems(arrayOf(getString(R.string.bookmark_edit), getString(R.string.bookmark_delete))) { _, which ->
                if (which == 0) showEditDialog(bookmark) else update(bookmarks - bookmark)
            }
            .show()
    }

    /** Add when [existing] is null, else edit it in place */
    private fun showEditDialog(existing: Bookmark?) {
        val dialogBinding = DialogBookmarkBinding.inflate(layoutInflater)
        existing?.let {
            dialogBinding.etName.setText(it.name)
            dialogBinding.etUrl.setText(it.url)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.add_bookmark else R.string.edit_bookmark)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.bookmark_save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            // Set here instead of in the builder so invalid input keeps the dialog open
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = dialogBinding.etName.text.toString().trim()
                val url = BookmarkStore.normalizeUrl(dialogBinding.etUrl.text.toString())
                dialogBinding.tilName.error = if (name.isEmpty()) getString(R.string.bookmark_name_required) else null
                val urlTooLong = url != null && BookmarkStore.isUrlTooLong(url)
                dialogBinding.tilUrl.error = when {
                    url == null -> getString(R.string.bookmark_invalid_url)
                    urlTooLong -> getString(R.string.bookmark_url_too_long)
                    else -> null
                }
                if (name.isEmpty() || url == null || urlTooLong) return@setOnClickListener

                val bookmark = Bookmark(name, url)
                val index = existing?.let { bookmarks.indexOf(it) } ?: -1
                update(if (index < 0) bookmarks + bookmark else bookmarks.toMutableList().also { it[index] = bookmark })
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
