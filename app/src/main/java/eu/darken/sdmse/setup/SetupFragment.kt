package eu.darken.sdmse.setup

import android.os.Bundle
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.fragment.app.viewModels
import androidx.navigation.findNavController
import androidx.navigation.ui.setupWithNavController
import dagger.hilt.android.AndroidEntryPoint
import eu.darken.sdmse.R
import eu.darken.sdmse.common.lists.differ.update
import eu.darken.sdmse.common.lists.setupDefaults
import eu.darken.sdmse.common.uix.Fragment3
import eu.darken.sdmse.common.viewbinding.viewBinding
import eu.darken.sdmse.databinding.SetupFragmentBinding
import eu.darken.sdmse.setup.saf.SAFSetupModule
import eu.darken.sdmse.setup.saf.SafGrantPrimaryContract
import javax.inject.Inject

@AndroidEntryPoint
class SetupFragment : Fragment3(R.layout.setup_fragment) {

    override val vm: SetupFragmentVM by viewModels()
    override val ui: SetupFragmentBinding by viewBinding()

    @Inject lateinit var setupAdapter: SetupAdapter

    private lateinit var safRequestLauncher: ActivityResultLauncher<SAFSetupModule.State.PathAccess>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        safRequestLauncher = registerForActivityResult(SafGrantPrimaryContract()) {
            vm.onSafAccessGranted(it)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ui.toolbar.apply {
            setupWithNavController(findNavController())
            setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.action_help -> {
                        // TODO
                        true
                    }
                    else -> super.onOptionsItemSelected(it)
                }
            }
        }

        ui.list.setupDefaults(setupAdapter, dividers = false)

        vm.listItems.observe2(ui) {
            setupAdapter.update(it)
        }

        vm.events.observe2(ui) {
            when (it) {
                is SetupEvents.RequestSafAccess -> safRequestLauncher.launch(it.item)
            }
        }

        super.onViewCreated(view, savedInstanceState)
    }
//
//    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
//        requireContext().contentResolver.apply {
//            takePersistableUriPermission(
//                data?.data!!,
//                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
//            )
//            persistedUriPermissions.forEach {
//                log { "URI PERM : $it" }
//                DocumentFile.fromTreeUri(requireContext(), it.uri)!!.listFiles().forEach {
//                    log { "LIST: ${it.uri}" }
//                }
//            }
//        }
//    }
}
