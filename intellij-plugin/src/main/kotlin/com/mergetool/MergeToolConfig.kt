package com.mergetool

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.annotations.MapAnnotation
import com.intellij.util.xmlb.annotations.Tag

/**
 * Perfil de merge: branch de origem + branches de destino.
 */
@Tag("profile")
data class MergeProfile(
    var sourceBranch: String = "",
    var targetBranches: List<String> = emptyList()
)

/**
 * Estado persistente do plugin (salvo por projeto).
 */
class MergeToolState {
    @MapAnnotation(surroundWithTag = false, entryTagName = "profile",
        keyAttributeName = "name", surroundKeyWithTag = false, surroundValueWithTag = false)
    var profiles: MutableMap<String, MergeProfile> = mutableMapOf(
        "Homologação" to MergeProfile(
            sourceBranch = "homologacao_merge",
            targetBranches = listOf(
                "1_Homologação-Miranga",
                "2_Homologação-PotiguarE&P",
                "3_Homologação-Recôncavo",
                "4_Homologação-Tieta"
            )
        ),
        "Produção" to MergeProfile(
            sourceBranch = "Produção-Miranga",
            targetBranches = emptyList()
        )
    )

    var lastProfile: String = "Homologação"
}

/**
 * Service persistente por projeto — salva perfis no .idea/
 */
@Service(Service.Level.PROJECT)
@State(
    name = "GitMergeToolSettings",
    storages = [Storage("gitMergeTool.xml")]
)
class MergeToolConfig : PersistentStateComponent<MergeToolState> {

    private var myState = MergeToolState()

    override fun getState(): MergeToolState = myState

    override fun loadState(state: MergeToolState) {
        myState = state
    }

    companion object {
        fun getInstance(project: Project): MergeToolConfig {
            return project.getService(MergeToolConfig::class.java)
        }
    }
}
