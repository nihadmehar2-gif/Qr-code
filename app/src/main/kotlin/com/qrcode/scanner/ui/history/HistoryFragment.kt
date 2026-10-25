package com.qrcode.scanner.ui.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.qrcode.scanner.R
import com.qrcode.scanner.data.QrTypeDetector
import com.qrcode.scanner.data.ScanHistoryEntity
import com.qrcode.scanner.data.ScanHistoryRepository
import com.qrcode.scanner.databinding.FragmentHistoryBinding
import com.qrcode.scanner.ui.scanner.ResultBottomSheet
import com.qrcode.scanner.util.IntentHelper
import kotlinx.coroutines.launch

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!
    private lateinit var repository: ScanHistoryRepository
    private lateinit var adapter: HistoryAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = ScanHistoryRepository(requireContext())
        adapter = HistoryAdapter(
            onClick = { showItem(it) },
            onFavorite = { toggleFavorite(it) },
            onDelete = { confirmDelete(it) }
        )
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter

        // Tabs: All / Favorites
        binding.tabs.addTab(binding.tabs.newTab().setText(R.string.history_all_tab))
        binding.tabs.addTab(binding.tabs.newTab().setText(R.string.history_favorites_tab))
        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { observeList(tab.position) }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        binding.btnClearAll.setOnClickListener { confirmClearAll() }

        observeList(0)
    }

    private fun observeList(tabPosition: Int) {
        val source = if (tabPosition == 1) repository.observeFavorites() else repository.observeAll()
        source.observe(viewLifecycleOwner) { items ->
            adapter.submitList(items)
            binding.emptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun showItem(item: ScanHistoryEntity) {
        val parsed = QrTypeDetector.detect(item.rawContent)
        ResultBottomSheet.newInstance(parsed).show(parentFragmentManager, ResultBottomSheet.TAG)
    }

    private fun toggleFavorite(item: ScanHistoryEntity) {
        viewLifecycleOwner.lifecycleScope.launch {
            repository.setFavorite(item.id, !item.isFavorite)
        }
    }

    private fun confirmDelete(item: ScanHistoryEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.history_delete_confirm_message)
            .setPositiveButton(R.string.action_delete_permanently) { d, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    repository.deleteById(item.id)
                }
                d.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmClearAll() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.history_clear_confirm_title)
            .setMessage(R.string.history_clear_confirm_message)
            .setPositiveButton(R.string.action_clear) { d, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    repository.clearAll()
                }
                d.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
