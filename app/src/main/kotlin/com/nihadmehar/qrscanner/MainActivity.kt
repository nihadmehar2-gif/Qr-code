package com.nihadmehar.qrscanner

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.nihadmehar.qrscanner.databinding.ActivityMainBinding
import com.nihadmehar.qrscanner.ui.scanner.ScannerActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        // Splash screen must be installed before super.onCreate().
        installSplashScreen()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // IMPORTANT: when using <androidx.fragment.app.FragmentContainerView> as the
        // NavHostFragment container (instead of a <fragment> tag), Activity.findNavController(viewId)
        // throws "IllegalStateException: View NavHostFragment does not have a NavController set"
        // because the NavController is attached only after the NavHostFragment goes through
        // its lifecycle. The supported fix (per Android docs) is to fetch the NavHostFragment
        // from the FragmentManager and ask it for its NavController.
        // See: https://developer.android.com/guide/navigation/navigation-getting-started
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        val navView: BottomNavigationView = binding.bottomNav
        navView.setupWithNavController(navController)

        binding.fabScan.setOnClickListener {
            startActivity(Intent(this, ScannerActivity::class.java))
        }
    }
}
