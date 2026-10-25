package com.nihadmehar.qrscanner.ui.generator

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.TextView
import androidx.core.view.setPadding

/**
 * Tiny color picker — shows a 5x2 grid of preset colors with their name.
 * Used by the QR Generator to pick foreground/background colors.
 *
 * No external color picker dependency is pulled in to keep the APK small.
 */
class MaterialColorPickerDialog(
    context: Context,
    private val colors: IntArray,
    private val labels: Array<String>,
    private val onPick: (Int) -> Unit
) : Dialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val grid = GridLayout(context).apply {
            columnCount = 5
            rowCount = 2
            setPadding(32)
        }
        for (i in colors.indices) {
            val cell = TextView(context).apply {
                text = labels.getOrNull(i) ?: ""
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = ColorDrawable(colors[i])
                setPadding(20)
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = (120 * context.resources.displayMetrics.density).toInt()
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
            }
            cell.layoutParams = params
            cell.setOnClickListener {
                onPick(colors[i]); dismiss()
            }
            grid.addView(cell)
        }
        setContentView(grid)
        setTitle("Choose a color")
    }
}
