package eu.darken.sdmse.common.areas.modules.misc

import dagger.Binds
import dagger.Module
import dagger.Reusable
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import eu.darken.sdmse.common.areas.DataArea
import eu.darken.sdmse.common.areas.modules.DataAreaModule
import eu.darken.sdmse.common.debug.logging.Logging.Priority.INFO
import eu.darken.sdmse.common.debug.logging.log
import eu.darken.sdmse.common.debug.logging.logTag
import eu.darken.sdmse.common.files.core.APath
import eu.darken.sdmse.common.files.core.GatewaySwitch
import eu.darken.sdmse.common.files.core.local.LocalGateway
import eu.darken.sdmse.common.storage.StorageEnvironment
import eu.darken.sdmse.common.user.UserManager2
import javax.inject.Inject


@Reusable
class CacheModule @Inject constructor(
    private val environment: StorageEnvironment,
    private val userManager2: UserManager2,
    private val gatewaySwitch: GatewaySwitch,
) : DataAreaModule {

    override suspend fun firstPass(): Collection<DataArea> {
        val gateway = gatewaySwitch.getGateway(APath.PathType.LOCAL) as LocalGateway

        if (!gateway.hasRoot()) {
            log(TAG, INFO) { "LocalGateway has no root, skipping." }
            return emptySet()
        }

        val basePath = environment.cacheDir

        if (!gateway.exists(basePath, mode = LocalGateway.Mode.ROOT)) {
            log(TAG, INFO) { "Doesn't exist: $basePath" }
            return emptySet()
        }

        return setOf(
            DataArea(
                type = DataArea.Type.DOWNLOAD_CACHE,
                path = basePath,
                userHandle = userManager2.systemUser,
            )
        )
    }

    @Module @InstallIn(SingletonComponent::class)
    abstract class DIM {
        @Binds @IntoSet abstract fun mod(mod: CacheModule): DataAreaModule
    }

    companion object {
        val TAG: String = logTag("DataArea", "Module", "Cache")
    }
}