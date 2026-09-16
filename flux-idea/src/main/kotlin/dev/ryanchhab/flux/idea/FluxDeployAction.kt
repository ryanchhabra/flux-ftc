package dev.ryanchhab.flux.idea

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.wm.ToolWindowManager

/**
 * "Flux Deploy" -- Tools menu + keyboard shortcut, doing exactly what the tool window's ⚡ Deploy
 * button does (both go through `FluxService.deploy()`, so there is exactly one deploy code path).
 * Also opens the tool window so the result is visible, since a shortcut-triggered deploy might
 * fire while the window is closed.
 */
class FluxDeployAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && !project.service<FluxService>().isDeploying
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ToolWindowManager.getInstance(project).getToolWindow("Flux")?.show()
        project.service<FluxService>().deploy()
    }
}
