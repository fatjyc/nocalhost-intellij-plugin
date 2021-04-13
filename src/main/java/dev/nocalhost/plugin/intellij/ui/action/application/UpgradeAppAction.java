package dev.nocalhost.plugin.intellij.ui.action.application;

import com.google.common.collect.Lists;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;

import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import dev.nocalhost.plugin.intellij.api.data.Application;
import dev.nocalhost.plugin.intellij.api.data.DevSpace;
import dev.nocalhost.plugin.intellij.commands.OutputCapturedNhctlCommand;
import dev.nocalhost.plugin.intellij.commands.data.NhctlUpgradeOptions;
import dev.nocalhost.plugin.intellij.exception.NocalhostNotifier;
import dev.nocalhost.plugin.intellij.helpers.NhctlHelper;
import dev.nocalhost.plugin.intellij.topic.NocalhostTreeDataUpdateNotifier;
import dev.nocalhost.plugin.intellij.ui.AppInstallOrUpgradeOption;
import dev.nocalhost.plugin.intellij.ui.AppInstallOrUpgradeOptionDialog;
import dev.nocalhost.plugin.intellij.ui.KustomizePathDialog;
import dev.nocalhost.plugin.intellij.ui.tree.node.ApplicationNode;
import dev.nocalhost.plugin.intellij.utils.FileChooseUtil;
import lombok.SneakyThrows;

public class UpgradeAppAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(UpgradeAppAction.class);
    private static final Set<String> CONFIG_FILE_EXTENSIONS = Set.of("yaml", "yml");

    private final Project project;
    private final ApplicationNode node;

    public UpgradeAppAction(Project project, ApplicationNode node) {
        super("Upgrade App");
        this.project = project;
        this.node = node;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        final DevSpace devSpace = node.getDevSpace();
        final Application application = node.getApplication();

        try {
            if (!NhctlHelper.isApplicationInstalled(devSpace, application)) {
                Messages.showMessageDialog("Application has not been installed.", "Upgrade Application", null);
                return;
            }
            upgradeApp();
        } catch (IOException | InterruptedException e) {
            LOG.error("error occurred while upgrading application", e);
            return;
        }
    }

    private void upgradeApp() throws IOException {
        final DevSpace devSpace = node.getDevSpace();
        final Application application = node.getApplication();
        final Application.Context context = application.getContext();
        final String installType = NhctlHelper.generateInstallType(application.getContext());

        final NhctlUpgradeOptions opts = new NhctlUpgradeOptions(devSpace);
        List<String> resourceDirs = Lists.newArrayList(context.getResourceDir());

        if (Set.of("helmLocal", "rawManifestLocal").contains(installType)) {
            String message = StringUtils.equals(installType, "rawManifestLocal")
                    ? "Please choose application manifest root directory"
                    : "Please choose unpacked application helm chart root directory";

            Path localPath = FileChooseUtil.chooseSingleDirectory(project, message);
            if (localPath == null) {
                return;
            }

            Path configPath = null;
            Path nocalhostConfigPath = localPath.resolve(".nocalhost");
            List<Path> configs = getAllConfig(nocalhostConfigPath);
            if (configs.size() == 0) {
                Messages.showErrorDialog("Not found config.yaml", "");
            } else if (configs.size() == 1) {
                configPath = configs.get(0);
            } else if (configs.size() > 1) {
                configPath = FileChooseUtil.chooseSingleFile(project, "Please select your configuration file", nocalhostConfigPath, CONFIG_FILE_EXTENSIONS);
            }
            if (configPath == null) {
                return;
            }

            opts.setLocalPath(localPath.toString());
            opts.setConfig(configPath.toString());

        } else {
            AppInstallOrUpgradeOption upgradeOption = askAndGetUpgradeOption(installType, application);
            if (upgradeOption == null) {
                return;
            }

            if (StringUtils.equals(installType, "helmRepo")) {
                opts.setHelmRepoUrl(context.getApplicationUrl());
                opts.setHelmChartName(context.getApplicationName());
                if (upgradeOption.isSpecifyOneSelected()) {
                    opts.setHelmRepoVersion(upgradeOption.getSpecifyText());
                }
            } else {
                opts.setGitUrl(context.getApplicationUrl());
                opts.setConfig(context.getApplicationConfigPath());
                if (upgradeOption.isSpecifyOneSelected()) {
                    opts.setGitRef(upgradeOption.getSpecifyText());
                }
            }

            if (StringUtils.equalsIgnoreCase(installType, "kustomizeGit")) {
                KustomizePathDialog kustomizePathDialog = new KustomizePathDialog(project);
                if (kustomizePathDialog.showAndGet()) {
                    String specifyPath = kustomizePathDialog.getSpecifyPath();
                    if (StringUtils.isNotBlank(specifyPath)) {
                        resourceDirs.add(specifyPath);
                    }
                } else {
                    return;
                }
            }
        }
        opts.setResourcesPath(resourceDirs);

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Upgrading application: " + context.getApplicationName(), false) {
            @Override
            public void onSuccess() {
                ApplicationManager.getApplication().getMessageBus().syncPublisher(
                        NocalhostTreeDataUpdateNotifier.NOCALHOST_TREE_DATA_UPDATE_NOTIFIER_TOPIC
                ).action();

                NocalhostNotifier.getInstance(project).notifySuccess("Application " + context.getApplicationName() + " upgraded", "");
            }

            @Override
            public void onThrowable(@NotNull Throwable e) {
                LOG.error("error occurred while upgrading application", e);
                NocalhostNotifier.getInstance(project).notifyError("Nocalhost upgrade application error", "Error occurred while upgrading application", e.getMessage());
            }

            @SneakyThrows
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                final OutputCapturedNhctlCommand outputCapturedNhctlCommand = project.getService(OutputCapturedNhctlCommand.class);
                outputCapturedNhctlCommand.upgrade(context.getApplicationName(), opts);
            }
        });
    }

    private AppInstallOrUpgradeOption askAndGetUpgradeOption(String installType, Application application) {
        final String title = "Upgrade DevSpace: " + application.getContext().getApplicationName();
        AppInstallOrUpgradeOptionDialog dialog;
        if (StringUtils.equals(installType, "helmRepo")) {
            dialog = new AppInstallOrUpgradeOptionDialog(
                    project,
                    title,
                    "Which version to upgrade?",
                    "Latest Version",
                    "Input the version of chart",
                    "Chart version cannot be empty");
        } else if (StringUtils.equals(installType, "kustomizeGit")) {
            dialog = new AppInstallOrUpgradeOptionDialog(
                    project,
                    title,
                    "Which branch to upgrade(Kustomize in Git Repo)?",
                    "Default Branch",
                    "Input the branch of repository",
                    "Git ref cannot be empty");
        } else {
            dialog = new AppInstallOrUpgradeOptionDialog(
                    project,
                    title,
                    "Which branch to upgrade(Manifests in Git Repo)?",
                    "Default Branch",
                    "Input the branch of repository",
                    "Git ref cannot be empty");
        }

        if (!dialog.showAndGet()) {
            return null;
        }

        return dialog.getAppInstallOrUpgradeOption();
    }

    private List<Path> getAllConfig(Path localPath) throws IOException {
        if (Files.notExists(localPath)) {
            return Lists.newArrayList();
        }

        return Files.list(localPath)
                .filter(Files::isRegularFile)
                .filter(e -> CONFIG_FILE_EXTENSIONS.contains(com.google.common.io.Files.getFileExtension(e.getFileName().toString())))
                .map(Path::toAbsolutePath)
                .collect(Collectors.toList());
    }
}
