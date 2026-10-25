package com.qrcode.scanner.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.qrcode.scanner.R
import com.qrcode.scanner.databinding.FragmentSettingsBinding
import com.qrcode.scanner.util.SettingsManager
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Load current settings
        viewLifecycleOwner.lifecycleScope.launch {
            val s = SettingsManager.get(requireContext())
            binding.switchBeep.isChecked = s.beepOnScan
            binding.switchVibrate.isChecked = s.vibrateOnScan
            binding.switchOpenAuto.isChecked = s.openResultAuto
            binding.switchSaveHistory.isChecked = s.saveHistory
            updateThemeLabel(s.themeMode)
        }

        binding.rowTheme.setOnClickListener { showThemePicker() }
        binding.switchBeep.setOnCheckedChangeListener { _, v ->
            viewLifecycleOwner.lifecycleScope.launch {
                SettingsManager.setBeep(requireContext(), v)
            }
        }
        binding.switchVibrate.setOnCheckedChangeListener { _, v ->
            viewLifecycleOwner.lifecycleScope.launch {
                SettingsManager.setVibrate(requireContext(), v)
            }
        }
        binding.switchOpenAuto.setOnCheckedChangeListener { _, v ->
            viewLifecycleOwner.lifecycleScope.launch {
                SettingsManager.setOpenResultAuto(requireContext(), v)
            }
        }
        binding.switchSaveHistory.setOnCheckedChangeListener { _, v ->
            viewLifecycleOwner.lifecycleScope.launch {
                SettingsManager.setSaveHistory(requireContext(), v)
            }
        }

        binding.tvVersion.text = "1.0.0"
    }

    private fun showThemePicker() {
        val labels = arrayOf(
            getString(R.string.settings_dark_mode_system),
            getString(R.string.settings_dark_mode_on),
            getString(R.string.settings_dark_mode_off)
        )
        viewLifecycleOwner.lifecycleScope.launch {
            val current = SettingsManager.get(requireContext()).themeMode
            val currentIdx = SettingsManager.ThemeMode.values().indexOf(current)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_dark_mode)
                .setSingleChoiceItems(labels, currentIdx) { dialog, which ->
                    val mode = SettingsManager.ThemeMode.values()[which]
                    viewLifecycleOwner.lifecycleScope.launch {
                        SettingsManager.setThemeMode(requireContext(), mode)
                        updateThemeLabel(mode)
                    }
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    private fun updateThemeLabel(mode: SettingsManager.ThemeMode) {
        binding.tvThemeValue.setText(when (mode) {
            SettingsManager.ThemeMode.SYSTEM -> R.string.settings_dark_mode_system
            SettingsManager.ThemeMode.ON -> R.string.settings_dark_mode_on
            SettingsManager.ThemeMode.OFF -> R.string.settings_dark_mode_off
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
