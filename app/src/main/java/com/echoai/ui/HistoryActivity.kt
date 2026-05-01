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
    private val adapter = HistoryAdapter()
    private var highlightLabel: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        historyManager = SoundHistoryManager(applicationContext)
        highlightLabel = intent.getStringExtra(EXTRA_HIGHLIGHT_LABEL)

        binding.toolbar.setNavigationOnClickListener { finish() }
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
        binding.emptyText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        binding.historyList.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE

        highlightLabel?.let { label ->
            val index = entries.indexOfFirst { it.label == label }
            if (index >= 0) {
                binding.historyList.scrollToPosition(index)
            }
            highlightLabel = null
        }
    }

    companion object {
        const val EXTRA_HIGHLIGHT_LABEL = "highlight_label"
    }
}
