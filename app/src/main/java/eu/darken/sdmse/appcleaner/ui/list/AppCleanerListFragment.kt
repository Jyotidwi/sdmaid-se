package eu.darken.sdmse.appcleaner.ui.list

import android.os.Bundle
import android.view.ActionMode
import android.view.MenuItem
import android.view.View
import androidx.core.view.isInvisible
import androidx.fragment.app.viewModels
import androidx.navigation.findNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import eu.darken.sdmse.R
import eu.darken.sdmse.common.lists.differ.update
import eu.darken.sdmse.common.lists.installListSelection
import eu.darken.sdmse.common.lists.setupDefaults
import eu.darken.sdmse.common.navigation.getQuantityString2
import eu.darken.sdmse.common.uix.Fragment3
import eu.darken.sdmse.common.viewbinding.viewBinding
import eu.darken.sdmse.databinding.AppcleanerListFragmentBinding

@AndroidEntryPoint
class AppCleanerListFragment : Fragment3(R.layout.appcleaner_list_fragment) {

    override val vm: AppCleanerListFragmentVM by viewModels()
    override val ui: AppcleanerListFragmentBinding by viewBinding()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ui.toolbar.apply {
            setupWithNavController(findNavController())
            setOnMenuItemClickListener {
                when (it.itemId) {
                    else -> super.onOptionsItemSelected(it)
                }
            }
        }

        val adapter = AppCleanerListAdapter()
        ui.list.setupDefaults(adapter)

        vm.state.observe2(ui) { state ->
            adapter.update(state.items)

            list.isInvisible = state.progress != null
            loadingOverlay.setProgress(state.progress)

            toolbar.subtitle = getQuantityString2(eu.darken.sdmse.common.R.plurals.result_x_items, state.items.size)
        }

        val selectionTracker = installListSelection(
            adapter = adapter,
            cabMenuRes = R.menu.menu_appcleaner_list_cab,
            onSelected = { mode: ActionMode, item: MenuItem, selected: List<AppCleanerListAdapter.Item> ->
                when (item.itemId) {
                    R.id.action_exclude_selected -> {
                        vm.exclude(selected)
                        mode.finish()
                        true
                    }

                    R.id.action_delete_selected -> {
                        vm.delete(selected)
                        true
                    }

                    else -> false
                }
            }
        )

        vm.events.observe2(ui) { event ->
            when (event) {
                is AppCleanerListEvents.ConfirmDeletion -> MaterialAlertDialogBuilder(requireContext()).apply {
                    setTitle(eu.darken.sdmse.common.R.string.general_delete_confirmation_title)
                    setMessage(
                        if (event.items.size == 1) {
                            getString(
                                R.string.appcleaner_delete_confirmation_message_x,
                                event.items.single().junk.label.get(context)
                            )
                        } else {
                            getString(
                                R.string.appcleaner_delete_confirmation_message_selected_x_items,
                                event.items.size
                            )
                        }
                    )
                    setPositiveButton(
                        if (event.items.size == 1) eu.darken.sdmse.common.R.string.general_delete_action
                        else eu.darken.sdmse.common.R.string.general_delete_selected_action
                    ) { _, _ ->
                        selectionTracker.clearSelection()
                        vm.delete(event.items, confirmed = true)
                    }
                    setNegativeButton(eu.darken.sdmse.common.R.string.general_cancel_action) { _, _ -> }
                    if (event.items.size == 1) {
                        setNeutralButton(eu.darken.sdmse.common.R.string.general_show_details_action) { _, _ ->
                            vm.showDetails(event.items.single())
                        }
                    }
                }.show()

                is AppCleanerListEvents.TaskResult -> Snackbar.make(
                    requireView(),
                    event.result.primaryInfo.get(requireContext()),
                    Snackbar.LENGTH_LONG
                ).show()

                is AppCleanerListEvents.ExclusionsCreated -> Snackbar
                    .make(
                        requireView(),
                        getQuantityString2(R.plurals.exclusion_x_new_exclusions, event.exclusions.size),
                        Snackbar.LENGTH_LONG
                    )
                    .setAction(eu.darken.sdmse.common.R.string.general_view_action) {
                        AppCleanerListFragmentDirections.goToExclusions().navigate()
                    }
                    .show()
            }
        }

        super.onViewCreated(view, savedInstanceState)
    }
}
