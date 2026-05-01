package com.echoai.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
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
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
