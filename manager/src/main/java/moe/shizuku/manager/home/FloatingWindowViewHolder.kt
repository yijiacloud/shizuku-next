package moe.shizuku.manager.home

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.materialswitch.MaterialSwitch
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.HomeFloatingWindowBinding
import moe.shizuku.manager.databinding.HomeItemContainerBinding
import moe.shizuku.manager.overlay.FloatingWindowManager
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

/**
 * 悬浮窗模式入口卡片
 *
 * 在 Home 页面显示一个开关卡片，用户可以：
 * 1. 一键开启/关闭悬浮窗模式
 * 2. 开启后可点击"配对"按钮通过悬浮窗进行 ADB 无线配对
 *
 * 原 Dialog 弹窗配对方式保留不变，此卡片提供第二种可选方式。
 */
class FloatingWindowViewHolder(
    private val binding: HomeFloatingWindowBinding,
    private val root: View
) : BaseViewHolder<Any>(root), View.OnClickListener {

    companion object {
        val CREATOR = Creator<Any> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeFloatingWindowBinding.inflate(inflater, outer.root, true)
            FloatingWindowViewHolder(inner, outer.root)
        }
    }

    private val switch: MaterialSwitch = binding.floatingWindowSwitch

    init {
        root.setOnClickListener(this)
        switch.isClickable = false
        switch.isFocusable = false
    }

    override fun onBind() {
        switch.isChecked = FloatingWindowManager.isRunning()
        updateSummary()
    }

    private fun updateSummary() {
        val context = itemView.context
        binding.text2.text = if (FloatingWindowManager.isRunning()) {
            context.getString(R.string.floating_window_running)
        } else {
            context.getString(R.string.home_floating_window_description)
        }
    }

    override fun onClick(v: View) {
        val context = v.context
        if (FloatingWindowManager.isRunning()) {
            // 已运行 → 点击关闭
            FloatingWindowManager.stop(context)
            switch.isChecked = false
        } else {
            // 未运行 → 点击开启
            val started = FloatingWindowManager.start(context)
            if (started) {
                switch.isChecked = true
            }
            // 如果没有权限，FloatingWindowManager 会跳转设置页
        }
        updateSummary()
    }
}