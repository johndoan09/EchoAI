package com.echoai.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import com.echoai.databinding.ActivityHistoryBinding
import com.echoai.domain.SoundHistoryManager

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var historyManager: SoundHistoryManager
    private lateinit var adapter: HistoryAdapter
    private var highlightLabel: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        installSystemBarInsets()

        historyManager = SoundHistoryManager(applicationContext)
        highlightLabel = intent.getStringExtra(EXTRA_HIGHLIGHT_LABEL)

        adapter = HistoryAdapter(
            highlightLabel = highlightLabel,
            onDismiss = { entry ->
                historyManager.dismiss(entry.timestampMs, entry.label)
                loadHistory()
            },
        )

        binding.backButton.setOnClickListener { finish() }
        binding.clearAllButton.setOnClickListener {
            historyManager.clearAll()
            loadHistory()
        }
        binding.historyList.layoutManager = LinearLayoutManager(this)
        binding.historyList.adapter = adapter

        loadHistory()
    }

    private fun installSystemBarInsets() {
        val rootStartLeft = binding.root.paddingLeft
        val rootStartTop = binding.root.paddingTop
        val rootStartRight = binding.root.paddingRight
        val rootStartBottom = binding.root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = rootStartLeft + systemBars.left,
                top = rootStartTop + systemBars.top,
                right = rootStartRight + systemBars.right,
                bottom = rootStartBottom + systemBars.bottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    override fun onResume() {
        super.onResume()
        loadHistory()
    }

    private fun loadHistory() {
        val entries = historyManager.getHistory()
        adapter.submitList(entries)
        val empty = entries.isEmpty()
        binding.emptyText.visibility = if (empty) View.VISIBLE else View.GONE
        binding.historyList.visibility = if (empty) View.GONE else View.VISIBLE
        binding.clearAllButton.visibility = if (empty) View.INVISIBLE else View.VISIBLE

        highlightLabel?.let { label ->
            val index = entries.indexOfFirst { it.label == label }
            if (index >= 0) binding.historyList.scrollToPosition(index)
            highlightLabel = null
        }
    }

    companion object {
        const val EXTRA_HIGHLIGHT_LABEL = "highlight_label"
    }
}
