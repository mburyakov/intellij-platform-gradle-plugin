// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.intellij.platform.gradle.providers

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlSerialName
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.*
import org.gradle.api.resources.ResourceHandler
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.jetbrains.intellij.platform.gradle.Constants.Locations
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformExtension
import org.jetbrains.intellij.platform.gradle.models.AndroidStudioReleases
import org.jetbrains.intellij.platform.gradle.models.JetBrainsIdesReleases
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
import org.jetbrains.intellij.platform.gradle.models.ProductRelease.Channel
import org.jetbrains.intellij.platform.gradle.models.decode
import org.jetbrains.intellij.platform.gradle.providers.ProductReleasesValueSource.FilterParameters
import org.jetbrains.intellij.platform.gradle.tasks.PrintProductsReleasesTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.intellij.platform.gradle.toIntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.utils.*

/**
 * Provides a complete list of binary IntelliJ Platform product releases matching the given [FilterParameters] criteria.
 *
 * Its main purpose is to feed the IntelliJ Plugin Verifier with a list of all compatible IDEs for the binary plugin verification.
 *
 * @see PrintProductsReleasesTask
 * @see VerifyPluginTask
 * @see Locations.PRODUCTS_RELEASES_JETBRAINS_IDES
 * @see Locations.PRODUCTS_RELEASES_ANDROID_STUDIO
 */
abstract class ProductReleasesValueSource : ValueSource<List<String>, ProductReleasesValueSource.Parameters> {

    interface Parameters : FilterParameters {
        /**
         * A file containing the XML with all available JetBrains IDEs releases.
         *
         * @see Locations.PRODUCTS_RELEASES_JETBRAINS_IDES
         */
        val jetbrainsIdes: RegularFileProperty

        /**
         * A file containing the XML with all available Android Studio releases.
         *
         * @see Locations.PRODUCTS_RELEASES_ANDROID_STUDIO
         */
        val androidStudio: RegularFileProperty
    }

    interface FilterParameters : ValueSourceParameters {
        /**
         * Build number from which the binary IDE releases will be matched.
         */
        @get:Input
        @get:Optional
        val sinceBuild: Property<String>

        /**
         * Build number until which the binary IDE releases will be matched.
         */
        @get:Input
        @get:Optional
        val untilBuild: Property<String>

        /**
         * A list of [IntelliJPlatformType] types to match.
         */
        @get:Input
        @get:Optional
        val types: ListProperty<IntelliJPlatformType>

        /**
         * A list of [Channel] types of binary releases to search in.
         */
        @get:Input
        @get:Optional
        val channels: ListProperty<Channel>
    }

    private val log = Logger(javaClass)

    override fun obtain(): List<String>? = with(parameters) {
        val jetbrainsIdesReleases = jetbrainsIdes.orNull?.asPath
            ?.also { log.info("Reading JetBrains IDEs releases from: $it") }
            ?.let { decode<JetBrainsIdesReleases>(it) }
            ?.let {
                sequence {
                    it.products.forEach { product ->
                        product.channels.forEach channel@{ channelEntry ->
                            channelEntry.builds.forEach { build ->
                                product.codes.forEach codes@{ code ->
                                    val type = runCatching { code.toIntelliJPlatformType() }.getOrElse { return@codes }
                                    val channel = runCatching { Channel.valueOf(channelEntry.status.uppercase()) }.getOrElse { return@channel }

                                    yield(
                                        ProductRelease(
                                            name = product.name,
                                            type = type,
                                            channel = channel,
                                            build = build.fullNumber.toVersion(),
                                            version = build.version.toVersion(),
                                            id = when (channel) {
                                                Channel.RELEASE -> with(build.version.toVersion()) {
                                                    "$major.$minor" + (".$patch".takeIf { patch > 0 }.orEmpty())
                                                }

                                                else -> build.fullNumber
                                            }
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
            .orEmpty()
            .toList()

        val androidStudioReleases = androidStudio.orNull?.asPath
            ?.also { log.info("Reading Android Studio releases from: $it") }
            ?.let { decode<AndroidStudioReleases>(it) }
            ?.items
            ?.mapNotNull { item ->
                val channel = runCatching { Channel.valueOf(item.channel.uppercase()) }.getOrNull() ?: return@mapNotNull null

                ProductRelease(
                    name = item.name,
                    type = IntelliJPlatformType.AndroidStudio,
                    channel = channel,
                    build = item.platformBuild.toVersion(),
                    version = item.platformVersion.toVersion(),
                    id = item.version,
                )
            }
            .orEmpty()

        val since = sinceBuild.map { it.toVersion() }.get()
        val until = untilBuild.map { it.replace("*", "99999").toVersion() }.orNull
        fun ProductRelease.testVersion(): Boolean {
            fun getComparativeVersion(version: Version) = when (version.major) {
                in 100..999 -> build
                else -> this.version
            }
            return getComparativeVersion(since) >= since && (until?.let { getComparativeVersion(it) <= it } ?: true)
        }

        val types = types.get()
        val channels = channels.get()

        log.info("Filtering releases with since='$since', until='$until', types='${types.joinToString(",")}', channels='${channels.joinToString(",")}'")

        (jetbrainsIdesReleases + androidStudioReleases)
            .filter { it.type in types }
            .filter { it.channel in channels }
            .filter { it.testVersion() }
            .groupBy { "${it.type.code}-${it.version.major}.${it.version.minor}" }
            .values
            .map { it.maxBy { release -> release.version } }
            .map { "${it.type.code}-${it.id}" }
    }
}

/**
 * Factory for creating the [ProductReleasesValueSource] instance to simplify providing product releases XML files and default [FilterParameters] filter values.
 */
@Suppress("FunctionName")
fun ProductReleasesValueSource(
    providers: ProviderFactory,
    resources: ResourceHandler,
    extensionProvider: Provider<IntelliJPlatformExtension>,
    configure: FilterParameters.() -> Unit = {},
) = providers.of(ProductReleasesValueSource::class.java) {
    val ideaVersionProvider = extensionProvider.map { it.pluginConfiguration.ideaVersion }

    with(parameters) {
        jetbrainsIdes.set(resources.resolve(Locations.PRODUCTS_RELEASES_JETBRAINS_IDES))
        androidStudio.set(resources.resolve(Locations.PRODUCTS_RELEASES_ANDROID_STUDIO))
        channels.convention(providers.provider { ProductRelease.Channel.values().toList() })
        types.convention(extensionProvider.map {
            listOf(it.productInfo.productCode.toIntelliJPlatformType())
        })
        sinceBuild.convention(ideaVersionProvider.flatMap { it.sinceBuild })
        untilBuild.convention(ideaVersionProvider.flatMap { it.untilBuild })
        configure()
    }
}

/**
 * Extension function for the [IntelliJPlatformExtension.VerifyPlugin.Ides] extension to let filter IDE binary releases just using [FilterParameters].
 */
@Suppress("FunctionName")
fun IntelliJPlatformExtension.VerifyPlugin.Ides.ProductReleasesValueSource(configure: FilterParameters.() -> Unit = {}) =
    ProductReleasesValueSource(
        providers,
        resources,
        extensionProvider,
        configure,
    )

@Serializable
data class Data(
    @XmlSerialName("item")
    val items: List<Item>,
) {
    @Serializable
    data class Item(
        val name: String,
    )
}
