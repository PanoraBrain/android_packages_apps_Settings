/*
 * SPDX-FileCopyrightText: 2026 Paranoid Android
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.settings.applications

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.SearchView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.android.settings.R
import com.google.android.material.appbar.AppBarLayout

class TrickyStoreAppListSettings : Fragment(R.layout.hide_applist_layout) {

    private lateinit var packageManager: PackageManager
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AppListAdapter
    private var packageList: List<PackageInfo> = emptyList()

    private var appBarLayout: AppBarLayout? = null
    private var searchText = ""
    private var showSystem = false
    private var optionsMenu: Menu? = null
    private var targetMap: MutableMap<String, TargetMode> = mutableMapOf()

    override fun onStart() {
        super.onStart()
        updateOptionsMenu()
        activity?.invalidateOptionsMenu()
    }

    @SuppressLint("QueryPermissionsNeeded")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        activity?.setTitle(R.string.ts_manage_target_apps)
        appBarLayout = activity?.findViewById(R.id.app_bar)
        packageManager = requireContext().packageManager
        packageList = try {
            packageManager.getInstalledPackages(PackageManager.MATCH_ANY_USER)
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = AppListAdapter()
        recyclerView = view.findViewById<RecyclerView>(R.id.user_list_view).also {
            it.layoutManager = LinearLayoutManager(context)
            it.adapter = adapter
        }
        refreshList()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        val host = activity ?: return
        optionsMenu = menu
        inflater.inflate(R.menu.trickystore_app_list_menu, menu)

        val searchMenuItem = menu.findItem(R.id.search)
        searchMenuItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                appBarLayout?.setExpanded(false, false)
                ViewCompat.setNestedScrollingEnabled(recyclerView, false)
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                appBarLayout?.setExpanded(false, false)
                ViewCompat.setNestedScrollingEnabled(recyclerView, true)
                return true
            }
        })

        val searchView = searchMenuItem.actionView as SearchView
        searchView.queryHint = getString(R.string.search_apps)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String) = false

            override fun onQueryTextChange(newText: String): Boolean {
                searchText = newText
                refreshList()
                return true
            }
        })

        updateOptionsMenu()
        host.invalidateOptionsMenu()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.show_system,
            R.id.hide_system -> {
                showSystem = !showSystem
                refreshList()
            }
            R.id.select_all -> {
                adapter.currentList.forEach { app ->
                    targetMap[app.packageName] = app.targetMode
                }
                saveTargets()
                refreshList()
            }
            R.id.reset_apps -> {
                targetMap.clear()
                DEFAULT_TARGETS.forEach { pkg ->
                    targetMap[pkg] = TargetMode.AUTO
                }
                saveTargets()
                refreshList()
            }
        }
        updateOptionsMenu()
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        updateOptionsMenu()
    }

    override fun onDestroyOptionsMenu() {
        optionsMenu = null
    }

    private fun updateOptionsMenu() {
        val menu = optionsMenu ?: return
        menu.findItem(R.id.show_system)?.isVisible = !showSystem
        menu.findItem(R.id.hide_system)?.isVisible = showSystem
    }

    private fun loadTargetMap(): MutableMap<String, TargetMode> {
        val result = mutableMapOf<String, TargetMode>()
        val content = Settings.Secure.getString(
            requireContext().contentResolver, SETTINGS_TARGETS
        ) ?: return result
        content.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            when {
                trimmed.endsWith("?") ->
                    result[trimmed.dropLast(1)] = TargetMode.LEAF_HACK
                trimmed.endsWith("!") ->
                    result[trimmed.dropLast(1)] = TargetMode.CERT_GEN
                else ->
                    result[trimmed] = TargetMode.AUTO
            }
        }
        return result
    }

    private fun saveTargets() {
        val lines = targetMap
            .map { it.key + it.value.symbol }
            .sorted()
        Settings.Secure.putString(
            requireContext().contentResolver,
            SETTINGS_TARGETS,
            lines.joinToString("\n")
        )
    }

    private fun showModeDialog(app: AppInfo) {
        val options = mutableListOf<TargetOption>()
        options.add(TargetOption(TargetMode.AUTO, getString(R.string.ts_mode_auto)))
        options.add(TargetOption(TargetMode.LEAF_HACK, getString(R.string.ts_mode_leaf_hack)))
        options.add(TargetOption(TargetMode.CERT_GEN, getString(R.string.ts_mode_cert_gen)))
        options.add(TargetOption(null, getString(R.string.ts_mode_remove)))

        val labels = options.map { it.label }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.ts_select_mode, app.label))
            .setItems(labels) { _, which ->
                val selected = options[which]
                if (selected.mode == null) {
                    targetMap.remove(app.packageName)
                } else {
                    targetMap[app.packageName] = selected.mode
                }
                saveTargets()
                refreshList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshList() {
        targetMap = loadTargetMap()
        val hiddenApps = resources.getStringArray(R.array.trickystore_hidden_apps).toSet()
        val list = packageList
            .filter { it.applicationInfo != null }
            .filter { !hiddenApps.contains(it.packageName) }
            .filter { info ->
                val isSystem = info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
                val isExcluded = EXCLUDED_SUFFIXES.any { info.packageName.contains(it) }
                if (isSystem && isExcluded) return@filter false
                if (isSystem && !showSystem && !targetMap.containsKey(info.packageName)) {
                    return@filter false
                }
                true
            }
            .filter { getLabel(it).contains(searchText, true) }
            .sortedWith(compareBy<PackageInfo> {
                !targetMap.containsKey(it.packageName)
            }.thenBy {
                getLabel(it).lowercase()
            })

        if (::adapter.isInitialized) {
            adapter.submitList(list.map { appInfoFromPackageInfo(it) })
        }
    }

    private fun appInfoFromPackageInfo(packageInfo: PackageInfo) =
        AppInfo(
            packageInfo.packageName,
            getLabel(packageInfo),
            packageInfo.applicationInfo.loadIcon(packageManager),
            targetMap[packageInfo.packageName] ?: TargetMode.AUTO,
            targetMap.containsKey(packageInfo.packageName),
        )

    private fun getLabel(packageInfo: PackageInfo) =
        packageInfo.applicationInfo.loadLabel(packageManager).toString()

    private fun getModeLabel(mode: TargetMode): String {
        return getString(mode.labelRes)
    }

    private inner class AppListAdapter : ListAdapter<AppInfo, AppListViewHolder>(itemCallback) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            AppListViewHolder(
                layoutInflater.inflate(R.layout.hide_applist_list_item, parent, false)
            )

        override fun onBindViewHolder(holder: AppListViewHolder, position: Int) {
            getItem(position).let { app ->
                holder.label?.text = app.label
                holder.icon?.setImageDrawable(app.icon)
                
                holder.checkBox?.setOnCheckedChangeListener(null)
                holder.checkBox?.isChecked = app.isInTarget
                
                holder.checkBox?.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        targetMap[app.packageName] = app.targetMode
                    } else {
                        targetMap.remove(app.packageName)
                    }
                    saveTargets()
                    // Use post to avoid IllegalStateException during layout phase
                    holder.itemView.post { refreshList() }
                }

                holder.itemView?.setOnClickListener { 
                    if (app.isInTarget) {
                        showModeDialog(app)
                    } else {
                        holder.checkBox?.isChecked = true
                    }
                }
                
                holder.packageName?.text = if (app.isInTarget) {
                    app.packageName + " - " + getModeLabel(app.targetMode)
                } else {
                    app.packageName
                }
            }
        }
    }

    private class AppListViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView? = itemView.findViewById(R.id.app_icon)
        val label: TextView? = itemView.findViewById(R.id.app_name)
        val packageName: TextView? = itemView.findViewById(R.id.package_name)
        val checkBox: CheckBox? = itemView.findViewById(R.id.check_box)
    }

    private data class AppInfo(
        val packageName: String,
        val label: String,
        val icon: Drawable,
        val targetMode: TargetMode,
        val isInTarget: Boolean,
    )

    private data class TargetOption(val mode: TargetMode?, val label: String)

    enum class TargetMode(val symbol: String, val labelRes: Int) {
        AUTO("", R.string.ts_mode_auto),
        LEAF_HACK("?", R.string.ts_mode_leaf_hack),
        CERT_GEN("!", R.string.ts_mode_cert_gen),
    }

    companion object {
        const val SETTINGS_TARGETS = "spoof_trickystore_target"

        val DEFAULT_TARGETS = setOf(
            "com.google.android.gms",
            "com.android.vending",
        )

        val EXCLUDED_SUFFIXES = listOf(
            ".auto_generated", ".appsearch", ".backup", ".carrier",
            ".cellbroadcast", ".cts", ".federated", ".ims", ".overlay",
            ".qti", ".qualcomm", ".resources", ".systemui.clocks",
            ".systemui.plugin", ".theme", ".iconpack",
        )

        private val itemCallback = object : DiffUtil.ItemCallback<AppInfo>() {
            override fun areItemsTheSame(oldInfo: AppInfo, newInfo: AppInfo) =
                oldInfo.packageName == newInfo.packageName

            override fun areContentsTheSame(oldInfo: AppInfo, newInfo: AppInfo) =
                oldInfo == newInfo
        }
    }
}
