package dev.nocalhost.plugin.intellij.ui.console;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.EnumUtils;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import javax.swing.*;

import dev.nocalhost.plugin.intellij.api.data.DevSpace;
import dev.nocalhost.plugin.intellij.commands.KubectlCommand;
import dev.nocalhost.plugin.intellij.commands.data.KubeResource;
import dev.nocalhost.plugin.intellij.commands.data.KubeResourceList;
import dev.nocalhost.plugin.intellij.commands.data.KubeResourceType;
import dev.nocalhost.plugin.intellij.exception.NocalhostExecuteCmdException;
import dev.nocalhost.plugin.intellij.exception.NocalhostNotifier;
import dev.nocalhost.plugin.intellij.ui.StartDevelopContainerChooseDialog;
import dev.nocalhost.plugin.intellij.ui.tree.node.ResourceNode;

public class NocalhostLogWindow extends NocalhostConsoleWindow {
    private static final Logger LOG = Logger.getInstance(NocalhostLogWindow.class);

    private final Project project;

    private String title;
    private ConsoleView consoleView;
    private LogPanel panel;

    private ProcessHandler logsProcessHandler;
    private boolean stop;
    private boolean pause;
    private String podName;
    private String containerName;
    private final DevSpace devSpace;
    private final KubectlCommand kubectlCommand;

    public NocalhostLogWindow(Project project, ResourceNode node) {
        this.project = project;

        kubectlCommand = ServiceManager.getService(KubectlCommand.class);
        devSpace = node.devSpace();
        stop = false;
        pause = false;

        String type = node.getKubeResource().getKind();

        switch (EnumUtils.getEnumIgnoreCase(KubeResourceType.class, type)) {
            case Deployment:
                containerName = node.getKubeResource().getSpec().getSelector().getMatchLabels().get("app");
                KubeResourceList pods = null;
                try {
                    pods = kubectlCommand.getResourceList("pods", node.getKubeResource().getSpec().getSelector().getMatchLabels(), devSpace);
                } catch (IOException | InterruptedException | NocalhostExecuteCmdException e) {
                    LOG.error("error occurred while getting workload pods", e);
                    NocalhostNotifier.getInstance(project).notifyError("Nocalhost log error", String.format("error occurred while getting workload pods containerName:[%s] devSpace:[%s]", containerName, devSpace), e.getMessage());
                    return;
                }
                if (pods != null && CollectionUtils.isNotEmpty(pods.getItems())) {
                    final List<KubeResource> running = pods
                            .getItems()
                            .stream()
                            .filter(KubeResource::canSelector)
                            .collect(Collectors.toList());
                    if (running.size() > 0) {
                        List<String> containersName = pods.getItems().stream().flatMap(r -> r.getSpec().getContainers().stream().map(KubeResource.Spec.Container::getName)).collect(Collectors.toList());
                        containerName = selectContainer(containersName);
                        List<String> podsName = pods.getItems().stream().map(r -> r.getMetadata().getName()).collect(Collectors.toList());
                        podName = selectContainer(podsName);
                    }
                    if (StringUtils.isBlank(podName)) {
                        return;
                    }
                }
                break;
            case Daemonset:
                break;
            case Statefulset:
                break;
            case Job:
                break;
            case CronJobs:
                break;
            case Pod:
                podName = node.resourceName();
                containerName = node.getKubeResource().getSpec().getContainers().get(0).getName();
                break;
            default:
                throw new IllegalStateException("Unexpected value: " + type);
        }

        title = String.format("%s/%s.log", podName, containerName);


        consoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole();

        panel = new LogPanel(false);
        panel.add(consoleView.getComponent());

        startProcess();

        AnAction[] consoleActions = consoleView.createConsoleActions();
        AnAction[] consoleViewActions = ArrayUtils.subarray(consoleActions, 2, consoleActions.length);
        StartAction startAction = new StartAction(this);
        PauseAction pauseAction = new PauseAction(this);
        StopAction stopAction = new StopAction(this);
        AnAction[] customActions = new AnAction[] {startAction, pauseAction, stopAction, new Separator()};
        DefaultActionGroup actionGroup = new DefaultActionGroup(ArrayUtils.addAll(customActions, consoleViewActions));

        ActionToolbar actionToolbar = ActionManager.getInstance().createActionToolbar("Nocalhost.Log.Window.Toolbar", actionGroup, false);
        panel.setToolbar(actionToolbar.getComponent());
    }

    private static class PauseAction extends DumbAwareAction {

        private final NocalhostLogWindow nocalhostLogWindow;

        public PauseAction(NocalhostLogWindow nocalhostLogWindow) {
            super("Pause", "Pause", AllIcons.Actions.Pause);
            this.nocalhostLogWindow = nocalhostLogWindow;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            nocalhostLogWindow.pauseProcess();
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            Presentation presentation = e.getPresentation();
            presentation.setEnabled(!nocalhostLogWindow.isPause() && !nocalhostLogWindow.isStop());
        }
    }

    private String selectContainer(List<String> containers) {
        if (containers.size() > 1) {
            StartDevelopContainerChooseDialog dialog = new StartDevelopContainerChooseDialog(containers);
            if (dialog.showAndGet()) {
                return dialog.getSelectedContainer();
            } else {
                return null;
            }
        } else {
            return containers.get(0);
        }
    }

    private static class StopAction extends DumbAwareAction {

        private final NocalhostLogWindow nocalhostLogWindow;

        public StopAction(NocalhostLogWindow nocalhostLogWindow) {
            super("Stop", "Stop", AllIcons.Actions.Suspend);
            this.nocalhostLogWindow = nocalhostLogWindow;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            nocalhostLogWindow.stopProcess();
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            Presentation presentation = e.getPresentation();
            presentation.setEnabled(!nocalhostLogWindow.isStop());
        }
    }

    private static class StartAction extends DumbAwareAction {

        private final NocalhostLogWindow nocalhostLogWindow;

        public StartAction(NocalhostLogWindow nocalhostLogWindow) {
            super("Start", "Start", AllIcons.Actions.Execute);
            this.nocalhostLogWindow = nocalhostLogWindow;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            nocalhostLogWindow.startProcess();
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            Presentation presentation = e.getPresentation();
            presentation.setEnabled(nocalhostLogWindow.isStop() || nocalhostLogWindow.isPause());
        }
    }

    public void pauseProcess() {
        consoleView.setOutputPaused(true);
        pause = true;
    }

    public void startProcess() {
        if (pause) {
            consoleView.setOutputPaused(false);
            stop = false;
            pause = false;
            return;
        }
        try {
            logsProcessHandler = kubectlCommand.getLogsProcessHandler(podName, containerName, devSpace);
            logsProcessHandler.startNotify();
            consoleView.attachToProcess(logsProcessHandler);
            consoleView.print(
                    "",
                    ConsoleViewContentType.LOG_INFO_OUTPUT);
            stop = false;
            pause = false;
        } catch (ExecutionException e) {
            NocalhostNotifier.getInstance(project).notifyError("Nocalhost log error", String.format("failed to log podName:[%s] containerName:[%s] devSpace:[%s]", podName, containerName, devSpace), e.getMessage());
        }
    }

    public void stopProcess() {
        logsProcessHandler.destroyProcess();
        stop = true;
        pause = false;
    }

    public boolean isStop() {
        return stop;
    }

    public boolean isPause() {
        return pause;
    }

    public ConsoleView getConsoleView() {
        return consoleView;
    }

    @Override
    public String getTitle() {
        return title;
    }

    @Override
    public JComponent getPanel() {
        return panel;
    }
}
