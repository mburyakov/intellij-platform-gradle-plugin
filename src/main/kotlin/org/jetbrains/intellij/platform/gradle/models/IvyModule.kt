// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.intellij.platform.gradle.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlChildrenName
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformRepositoriesExtension
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory

@Serializable
@XmlSerialName("ivy-module")
data class IvyModule(
    val version: String = "2.0",
    @XmlElement @XmlSerialName("info") val info: Info?,
    @XmlElement @XmlChildrenName("conf") val configurations: List<Configuration>,
    @XmlElement @XmlChildrenName("artifact") val publications: List<Publication>,
) {

    @Serializable
    data class Configuration(
        val name: String?,
        val visibility: String?,
    )

    @Serializable
    data class Info(
        val organisation: String?,
        val module: String?,
        val revision: String?,
        val publication: String?,
    )

    @Serializable
    data class Publication(
        val name: String?,
        val type: String?,
        val ext: String?,
        val conf: String?,
        val url: String?,
    )
}

/**
 * Create a publication artifact element for Ivy XML file, like:
 *
 * ```XML
 * <ivy-module version="2.0">
 *     ...
 *     <publications>
 *         <artifact conf="default" ext="jar" name="/path/to/artifact.jar" type="jar" />
 *     </publications>
 * </ivy-module>
 * ```
 *
 * The artifact name is an actual path of the file or directory, later used to locate all entries belonging to the current local dependency.
 * Note that the path is built using [invariantSeparatorsPathString] due to path separator differences on Windows.
 *
 * In addition, the leading drive letter is always removed to start paths with `/` and to solve an issue with the Gradle file resolver.
 * If the artifact path doesn't start with `/`, the [BaseDirFileResolver] creates a malformed location with a Gradle base dir prepended,
 * like: `C:/Users/hsz/dir/C:/Users/hsz/path/to/artifact.jar`, so we make it `/Users/hsz/path/to/artifact.jar` explicitly.
 *
 * As we remove the drive letter, we later have to guess which drive the artifact belongs to by iterating over A:/, B:/, C:/, ...
 * but that's not a huge problem.
 *
 * @see IntelliJPlatformRepositoriesExtension.localPlatformArtifacts
 */
internal fun Path.toPublication() = IvyModule.Publication(
    name = invariantSeparatorsPathString.replaceFirst(Regex("^[a-zA-Z]:/"), "/"),
    type = when {
        isDirectory() -> "directory"
        else -> extension
    },
    ext = when {
        isDirectory() -> null
        else -> extension
    },
    conf = "default",
    url = null,
)
