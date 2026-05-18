package com.github.nickkemp.fatjarbuilder

import com.intellij.icons.AllIcons
import com.intellij.packaging.artifacts.ArtifactType
import com.intellij.packaging.elements.CompositePackagingElement
import com.intellij.packaging.elements.PackagingElementFactory
import com.intellij.packaging.elements.PackagingElementOutputKind
import javax.swing.Icon

/**
 * Defines the "Fat JAR" artifact type that appears in:
 *   File → Project Structure → Artifacts → + → Fat JAR
 *
 * Registered in plugin.xml via:
 *   <artifactType implementation="...FatJarArtifactType"/>
 */
class FatJarArtifactType : ArtifactType("fat-jar", { "Fat JAR" }) {

    companion object {
        val INSTANCE: FatJarArtifactType by lazy {
            EP_NAME.findExtension(FatJarArtifactType::class.java)!!
        }
    }

    override fun getIcon(): Icon = AllIcons.Nodes.Artifact

    override fun getDefaultPathFor(kind: PackagingElementOutputKind): String? = null

    override fun isSuitableItem(item: com.intellij.packaging.ui.PackagingSourceItem): Boolean = false

    override fun createRootElement(artifactName: String): CompositePackagingElement<*> {
        return PackagingElementFactory.getInstance().createArchive("$artifactName.jar")
    }
}
