package eu.darken.sdmse.corpsefinder.core

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import eu.darken.sdmse.common.ca.CaString
import eu.darken.sdmse.common.ca.caString
import eu.darken.sdmse.common.coroutine.AppScope
import eu.darken.sdmse.common.datastore.value
import eu.darken.sdmse.common.debug.logging.Logging.Priority.*
import eu.darken.sdmse.common.debug.logging.log
import eu.darken.sdmse.common.debug.logging.logTag
import eu.darken.sdmse.common.easterEggProgressMsg
import eu.darken.sdmse.common.files.*
import eu.darken.sdmse.common.forensics.FileForensics
import eu.darken.sdmse.common.pkgs.pkgops.PkgOps
import eu.darken.sdmse.common.progress.*
import eu.darken.sdmse.common.sharedresource.SharedResource
import eu.darken.sdmse.common.sharedresource.keepResourceHoldersAlive
import eu.darken.sdmse.common.user.UserManager2
import eu.darken.sdmse.corpsefinder.core.filter.CorpseFilter
import eu.darken.sdmse.corpsefinder.core.tasks.*
import eu.darken.sdmse.exclusion.core.*
import eu.darken.sdmse.exclusion.core.types.Exclusion
import eu.darken.sdmse.exclusion.core.types.PathExclusion
import eu.darken.sdmse.main.core.SDMTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CorpseFinder @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    private val filterFactories: Set<@JvmSuppressWildcards CorpseFilter.Factory>,
    fileForensics: FileForensics,
    private val gatewaySwitch: GatewaySwitch,
    private val exclusionManager: ExclusionManager,
    private val userManager: UserManager2,
    private val pkgOps: PkgOps,
) : SDMTool, Progress.Client {

    override val type: SDMTool.Type = SDMTool.Type.CORPSEFINDER

    private val usedResources = setOf(fileForensics, gatewaySwitch, pkgOps)
    override val sharedResource = SharedResource.createKeepAlive(TAG, appScope)

    private val progressPub = MutableStateFlow<Progress.Data?>(null)
    override val progress: Flow<Progress.Data?> = progressPub
    override fun updateProgress(update: (Progress.Data?) -> Progress.Data?) {
        progressPub.value = update(progressPub.value)
    }

    private val internalData = MutableStateFlow(null as Data?)
    val data: Flow<Data?> = internalData

    private val toolLock = Mutex()
    override suspend fun submit(task: SDMTool.Task): SDMTool.Task.Result = toolLock.withLock {
        task as CorpseFinderTask
        log(TAG, INFO) { "submit($task) starting..." }
        updateProgressPrimary(eu.darken.sdmse.common.R.string.general_progress_loading)
        updateProgressSecondary(easterEggProgressMsg)
        updateProgressCount(Progress.Count.Indeterminate())

        try {
            val result = keepResourceHoldersAlive(usedResources) {
                when (task) {
                    is CorpseFinderScanTask -> performScan(task)
                    is CorpseFinderDeleteTask -> deleteCorpses(task)
                    is UninstallWatcherTask -> {
                        performScan(CorpseFinderScanTask(pkgIdFilter = setOf(task.target)))

                        val targets = internalData.value!!.corpses
                            .filter { it.ownerInfo.getOwner(task.target) != null }
                            .map { it.path }
                            .onEach { log(TAG) { "Uninstall watcher found target $it" } }
                            .toSet()

                        log(TAG) { "Watcher auto delete enabled=${task.autoDelete}" }
                        val deleteResult = if (task.autoDelete) {
                            deleteCorpses(CorpseFinderDeleteTask(targetCorpses = targets))
                        } else {
                            null
                        }

                        UninstallWatcherTask.Success(
                            foundItems = targets.size,
                            deletedItems = deleteResult?.deletedItems ?: 0,
                            recoveredSpace = deleteResult?.recoveredSpace ?: 0L,
                        )
                    }

                    is CorpseFinderSchedulerTask -> {
                        performScan(CorpseFinderScanTask())
                        deleteCorpses(CorpseFinderDeleteTask())
                    }
                }
            }
            internalData.value = internalData.value?.copy(
                lastResult = result,
            )
            log(TAG, INFO) { "submit($task) finished: $result" }
            result
        } catch (e: CancellationException) {
            throw e
        } finally {
            updateProgress { null }
        }
    }

    private suspend fun performScan(task: CorpseFinderScanTask): CorpseFinderTask.Result {
        log(TAG) { "performScan(): $task" }

        internalData.value = null

        val filters = filterFactories
            .filter { it.isEnabled() }
            .map { it.create() }
            .onEach { log(TAG) { "Created filter: $it" } }

        val pathExclusions = exclusionManager.pathExclusions(SDMTool.Type.CORPSEFINDER)
        val pkgExclusions = exclusionManager.pkgExclusions(SDMTool.Type.CORPSEFINDER)

        val results = filters
            .map { filter ->
                filter
                    .withProgress(this@CorpseFinder) { scan() }
                    .also { log(TAG) { "$filter found ${it.size} corpses" } }
            }
            .flatten()
            .filter { corpse ->
                pkgExclusions.none { excl ->
                    corpse.ownerInfo.owners.any { owner ->
                        excl.match(owner.pkgId).also {
                            if (it) log(TAG, INFO) { "Excluded due to $excl: $corpse" }
                        }
                    }
                }
            }
            .filter { corpse ->
                pathExclusions.none { excl ->
                    excl.match(corpse.path).also {
                        if (it) log(TAG, INFO) { "Excluded due to $excl: $corpse" }
                    }
                }
            }
            .filter { corpse ->
                // One extra check for multi-user devices without root
                if (!userManager.hasMultiUserSupport) return@filter true

                // Another user might own corpses in secondary public storage
                if (corpse.areaInfo.userHandle != userManager.systemUser().handle) return@filter true

                if (corpse.ownerInfo.owners.any { pkgOps.isInstalleMaybe(it.pkgId, it.userHandle) }) {
                    log(TAG, WARN) { "Potential multi-user false positive: $corpse" }
                    return@filter false
                }

                return@filter true
            }

        results.forEach { log(TAG, INFO) { "Result: $it" } }

        log(TAG) { "Warming up fields..." }
        results.forEach { it.size }
        log(TAG) { "Field warm up done." }

        internalData.value = Data(
            corpses = results
        )

        return CorpseFinderScanTask.Success(
            itemCount = results.size,
            recoverableSpace = results.sumOf { it.size },
        )
    }

    private suspend fun deleteCorpses(task: CorpseFinderDeleteTask): CorpseFinderDeleteTask.Success {
        log(TAG) { "deleteCorpses(): $task" }

        val deletedCorpses = mutableSetOf<Corpse>()
        val deletedContents = mutableMapOf<Corpse, Set<APathLookup<*>>>()
        val snapshot = internalData.value ?: throw IllegalStateException("Data is null")

        val targetCorpses = task.targetCorpses ?: snapshot.corpses.map { it.path }
        targetCorpses.forEach { targetCorpse ->
            val corpse = snapshot.corpses.single { it.path.matches(targetCorpse) }

            if (!task.targetContent.isNullOrEmpty()) {
                val deleted = mutableSetOf<APathLookup<*>>()

                task.targetContent.forEach { targetContent ->
                    updateProgressPrimary(caString {
                        it.getString(
                            eu.darken.sdmse.common.R.string.general_progress_deleting,
                            targetContent.userReadableName.get(it)
                        )
                    })
                    log(TAG) { "Deleting $targetContent..." }
                    try {
                        targetContent.deleteAll(gatewaySwitch) {
                            updateProgressSecondary(it.userReadablePath)
                            true
                        }
                        log(TAG) { "Deleted $targetContent!" }

                        corpse.content
                            .filter { targetContent.isAncestorOf(it) || targetContent.matches(it) }
                            .run { deleted.addAll(this) }
                    } catch (e: WriteException) {
                        log(TAG, WARN) { "Deletion failed for $targetContent: $e" }
                    }
                }

                deletedContents[corpse] = deleted
            } else {
                updateProgressPrimary(caString {
                    it.getString(
                        eu.darken.sdmse.common.R.string.general_progress_deleting,
                        corpse.path.userReadableName.get(it)
                    )
                })
                log(TAG) { "Deleting $targetCorpse..." }
                try {
                    corpse.path.deleteAll(gatewaySwitch) {
                        updateProgressSecondary(it.userReadablePath)
                        true
                    }
                    log(TAG) { "Deleted $targetCorpse!" }

                    deletedCorpses.add(corpse)
                } catch (e: WriteException) {
                    log(TAG, WARN) { "Deletion failed for $targetCorpse: $e" }
                }
            }
        }

        updateProgressPrimary(eu.darken.sdmse.common.R.string.general_progress_loading)
        updateProgressSecondary(CaString.EMPTY)

        internalData.value = snapshot.copy(
            corpses = snapshot.corpses
                .mapNotNull { corpse ->
                    when {
                        deletedCorpses.contains(corpse) -> null
                        deletedContents.containsKey(corpse) -> corpse.copy(
                            content = corpse.content.filter { c -> deletedContents[corpse]!!.none { it.matches(c) } }
                        )

                        else -> corpse
                    }
                }
        )

        return CorpseFinderDeleteTask.Success(
            deletedItems = deletedCorpses.size + deletedContents.values.sumOf { it.size },
            recoveredSpace = deletedCorpses.sumOf { it.size } + deletedContents.values.sumOf { contents -> contents.sumOf { it.size } }
        )
    }

    suspend fun exclude(corpse: Corpse, path: APath? = null) = toolLock.withLock {
        log(TAG) { "exclude(): $corpse, $path" }
        val exclusion = PathExclusion(
            path = corpse.path,
            tags = setOf(Exclusion.Tag.CORPSEFINDER),
        )
        exclusionManager.save(exclusion)

        val snapshot = internalData.value!!
        internalData.value = snapshot.copy(
            corpses = snapshot.corpses - corpse
        )
    }

    data class Data(
        val corpses: Collection<Corpse>,
        val lastResult: CorpseFinderTask.Result? = null,
    ) {
        val totalSize: Long get() = corpses.sumOf { it.size }
        val totalCount: Int get() = corpses.size
    }

    @InstallIn(SingletonComponent::class)
    @Module
    abstract class DIM {
        @Binds @IntoSet abstract fun mod(mod: CorpseFinder): SDMTool
    }

    companion object {
        private val TAG = logTag("CorpseFinder")
    }
}