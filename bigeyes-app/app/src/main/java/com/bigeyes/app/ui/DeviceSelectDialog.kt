package com.bigeyes.app.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.RadioButton
import android.widget.TextView
import com.bigeyes.app.R
import com.bigeyes.app.model.DlnaDevice
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class DeviceSelectDialog(
    private val context: Context,
    private val devices: List<DlnaDevice>,
    private val onSelected: (DlnaDevice) -> Unit
) {
    fun show() {
        val listView = ListView(context)
        val adapter = DeviceAdapter(context, devices)
        listView.adapter = adapter

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.select_device_title)
            .setView(listView)
            .setNegativeButton("取消", null)
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            dialog.dismiss()
            onSelected(devices[position])
        }

        dialog.show()
    }

    private class DeviceAdapter(
        private val context: Context,
        private val items: List<DlnaDevice>
    ) : BaseAdapter() {

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_device, parent, false)
            val device = items[position]

            val tvName = view.findViewById<TextView>(R.id.tv_device_name)
            val tvIp = view.findViewById<TextView>(R.id.tv_device_ip)
            val rbSelected = view.findViewById<RadioButton>(R.id.rb_device_selected)

            tvName.text = device.name
            tvIp.text = "IP: ${device.ip}"
            rbSelected.isChecked = device.selected

            return view
        }
    }
}
