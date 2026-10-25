package com.qrcode.scanner.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.qrcode.scanner.R
import com.qrcode.scanner.databinding.FragmentHomeBinding
import com.qrcode.scanner.ui.scanner.ScannerActivity

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnScanCamera.setOnClickListener {
            startActivity(Intent(requireContext(), ScannerActivity::class.java))
        }
        binding.btnScanGallery.setOnClickListener {
            // Trigger gallery scan via the scanner activity in gallery mode
            val intent = Intent(requireContext(), ScannerActivity::class.java).apply {
                putExtra(ScannerActivity.EXTRA_LAUNCH_MODE, ScannerActivity.MODE_GALLERY)
            }
            startActivity(intent)
        }
        binding.btnGenerate.setOnClickListener {
            findNavController().navigate(R.id.nav_generate)
        }
        binding.btnHistory.setOnClickListener {
            findNavController().navigate(R.id.nav_history)
        }
        binding.btnSettings.setOnClickListener {
            findNavController().navigate(R.id.nav_settings)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
