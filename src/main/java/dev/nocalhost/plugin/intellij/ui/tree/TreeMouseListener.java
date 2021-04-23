package dev.nocalhost.plugin.intellij.ui.tree;


import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPopupMenu;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.JBPopupMenu;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.treeStructure.Tree;

import org.apache.commons.lang3.EnumUtils;
import org.jetbrains.annotations.NotNull;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.tree.TreePath;

import dev.nocalhost.plugin.intellij.commands.KubectlCommand;
import dev.nocalhost.plugin.intellij.commands.data.KubeResourceType;
import dev.nocalhost.plugin.intellij.commands.data.NhctlDescribeService;
import dev.nocalhost.plugin.intellij.exception.NocalhostNotifier;
import dev.nocalhost.plugin.intellij.ui.action.application.ApplyAction;
import dev.nocalhost.plugin.intellij.ui.action.application.ClearAppPersisentDataAction;
import dev.nocalhost.plugin.intellij.ui.action.application.ConfigAppAction;
import dev.nocalhost.plugin.intellij.ui.action.application.LoadResourceAction;
import dev.nocalhost.plugin.intellij.ui.action.application.UninstallAppAction;
import dev.nocalhost.plugin.intellij.ui.action.application.UpgradeAppAction;
import dev.nocalhost.plugin.intellij.ui.action.devspace.InstallAppAction;
import dev.nocalhost.plugin.intellij.ui.action.devspace.ResetDevSpaceAction;
import dev.nocalhost.plugin.intellij.ui.action.devspace.ViewKubeConfigAction;
import dev.nocalhost.plugin.intellij.ui.action.workload.AssociateLocalDirectoryAction;
import dev.nocalhost.plugin.intellij.ui.action.workload.ClearPersistentDataAction;
import dev.nocalhost.plugin.intellij.ui.action.workload.ConfigAction;
import dev.nocalhost.plugin.intellij.ui.action.workload.EndDevelopAction;
import dev.nocalhost.plugin.intellij.ui.action.workload.LogsAction;
import dev.nocalhost.plugin.intellij.ui.action.workload.PortForwardAction;
import dev.nocalhost.plugin.intellij.ui.action.workload.ResetAction;
import dev.nocalhost.plugin.intellij.ui.action.workload.StartDevelopAction;
import dev.nocalhost.plugin.intellij.ui.action.workload.TerminalAction;
import dev.nocalhost.plugin.intellij.ui.tree.node.ApplicationNode;
import dev.nocalhost.plugin.intellij.ui.tree.node.DevSpaceNode;
import dev.nocalhost.plugin.intellij.ui.tree.node.ResourceNode;
import dev.nocalhost.plugin.intellij.ui.vfs.KubeConfigFile;
import lombok.SneakyThrows;

public class TreeMouseListener extends MouseAdapter {
    private static final Logger LOG = Logger.getInstance(TreeMouseListener.class);

    private final Project project;
    private final Tree tree;

    public TreeMouseListener(Tree tree, Project project) {
        this.tree = tree;
        this.project = project;
    }

    @Override
    public void mouseClicked(MouseEvent event) {
        if (event.getClickCount() == 2 && event.getButton() == MouseEvent.BUTTON1) {
            TreePath treePath = tree.getClosestPathForLocation(event.getX(), event.getY());
            if (treePath == null) {
                return;
            }
            Object object = treePath.getLastPathComponent();

            if (object instanceof ResourceNode) {
                ResourceNode resourceNode = (ResourceNode) object;

                ProgressManager.getInstance().run(new Task.Backgroundable(project, "Loading kubernetes resource") {
                    private VirtualFile virtualFile;

                    @Override
                    public void onSuccess() {
                        FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, virtualFile, 0), true);
                    }

                    @Override
                    public void onThrowable(@NotNull Throwable e) {
                        LOG.error("error occurred while loading kubernetes resource yaml", e);
                        NocalhostNotifier.getInstance(project).notifyError("Nocalhost load kubernetes resource error", "Error occurred while loading kubernetes resource yaml", e.getMessage());
                    }

                    @SneakyThrows
                    @Override
                    public void run(@NotNull ProgressIndicator indicator) {
                        final KubectlCommand kubectlCommand = ServiceManager.getService(KubectlCommand.class);
                        String content = kubectlCommand.getResourceYaml(
                                resourceNode.getKubeResource().getKind(),
                                resourceNode.getKubeResource().getMetadata().getName(),
                                resourceNode.devSpace());
                        virtualFile = new KubeConfigFile(resourceNode.resourceName() + ".yaml", resourceNode.resourceName() + ".yaml", resourceNode.resourceName(), content, project, resourceNode.devSpace(), resourceNode.applicationName());
                    }
                });
                return;
            }
        }
    }

    @Override
    public void mouseReleased(MouseEvent event) {
        if (event.getButton() == MouseEvent.BUTTON3) {
            TreePath treePath = tree.getClosestPathForLocation(event.getX(), event.getY());
            if (treePath == null) {
                return;
            }
            Object object = treePath.getLastPathComponent();

            if (object instanceof DevSpaceNode) {
                DevSpaceNode devSpaceNode = (DevSpaceNode) object;
                renderDevSpaceAction(event, devSpaceNode);
                return;
            }

            if (object instanceof ApplicationNode) {
                ApplicationNode applicationNode = (ApplicationNode) object;
                renderApplicationAction(event, applicationNode);
                return;
            }

            if (object instanceof ResourceNode) {
                ResourceNode resourceNode = (ResourceNode) object;
                renderWorkloadAction(event, resourceNode);
                return;
            }
        }
    }

    private void renderApplicationAction(MouseEvent event, ApplicationNode applicationNode) {
        DefaultActionGroup actionGroup = new DefaultActionGroup();

        actionGroup.add(new UninstallAppAction(project, applicationNode));

        actionGroup.add(new Separator());
        actionGroup.add(new ApplyAction(project, applicationNode));
        actionGroup.add(new ConfigAppAction(project, applicationNode));
        actionGroup.add(new ClearAppPersisentDataAction(project, applicationNode));
        actionGroup.add(new Separator());
        actionGroup.add(new UpgradeAppAction(project, applicationNode));

        actionGroup.add(new Separator());
        actionGroup.add(new LoadResourceAction(project, applicationNode));

        ActionPopupMenu menu = ActionManager.getInstance().createActionPopupMenu("Nocalhost.Application.Actions", actionGroup);
        JBPopupMenu.showByEvent(event, menu.getComponent());
    }

    private void renderDevSpaceAction(MouseEvent event, DevSpaceNode devSpaceNode) {
        DefaultActionGroup actionGroup = new DefaultActionGroup();
        actionGroup.add(new InstallAppAction(project, devSpaceNode));
        actionGroup.add(new Separator());
        actionGroup.add(new ViewKubeConfigAction(project, devSpaceNode));
        actionGroup.add(new ResetDevSpaceAction(project, devSpaceNode));

        ActionPopupMenu menu = ActionManager.getInstance().createActionPopupMenu("Nocalhost.Devspace.Actions", actionGroup);
        JBPopupMenu.showByEvent(event, menu.getComponent());
    }

    private void renderWorkloadAction(MouseEvent event, ResourceNode resourceNode) {
        DefaultActionGroup actionGroup = new DefaultActionGroup();

        String kind = resourceNode.getKubeResource().getKind().toLowerCase();
        KubeResourceType type = EnumUtils.getEnumIgnoreCase(KubeResourceType.class, kind);
        switch (type) {
            case Deployment:
                final NhctlDescribeService nhctlDescribeService = resourceNode.getNhctlDescribeService();

                if (nhctlDescribeService != null) {
                    if (!nhctlDescribeService.isDeveloping()) {
                        actionGroup.add(new StartDevelopAction(project, resourceNode));
                    } else {
                        actionGroup.add(new EndDevelopAction(project, resourceNode));
                    }
                }
                actionGroup.add(new ConfigAction(project, resourceNode));
                actionGroup.add(new AssociateLocalDirectoryAction(project, resourceNode));
                actionGroup.add(new Separator());
                actionGroup.add(new ClearPersistentDataAction(project, resourceNode));
                actionGroup.add(new Separator());
                actionGroup.add(new LogsAction(project, resourceNode));
                actionGroup.add(new PortForwardAction(project, resourceNode));
                actionGroup.add(new ResetAction(project, resourceNode));
                actionGroup.add(new TerminalAction(project, resourceNode));
                break;
            case Daemonset:
            case Job:
            case CronJobs:
                break;
            case Statefulset:
                actionGroup.add(new PortForwardAction(project, resourceNode));
                break;
            case Pod:
                actionGroup.add(new LogsAction(project, resourceNode));
                actionGroup.add(new PortForwardAction(project, resourceNode));
                actionGroup.add(new TerminalAction(project, resourceNode));
                break;
            default:
                return;
        }

        ActionPopupMenu menu = ActionManager.getInstance().createActionPopupMenu("Nocalhost.Workload.Actions", actionGroup);
        JBPopupMenu.showByEvent(event, menu.getComponent());
    }
}
