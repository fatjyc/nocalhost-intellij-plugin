package dev.nocalhost.plugin.intellij.commands;

import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import com.google.common.io.CharStreams;
import com.google.gson.reflect.TypeToken;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.util.EnvironmentUtil;

import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import dev.nocalhost.plugin.intellij.commands.data.NhctlApplyOptions;
import dev.nocalhost.plugin.intellij.commands.data.NhctlCleanPVCOptions;
import dev.nocalhost.plugin.intellij.commands.data.NhctlConfigOptions;
import dev.nocalhost.plugin.intellij.commands.data.NhctlDescribeOptions;
import dev.nocalhost.plugin.intellij.commands.data.NhctlDevEndOptions;
import dev.nocalhost.plugin.intellij.commands.data.NhctlDevStartOptions;
import dev.nocalhost.plugin.intellij.commands.data.NhctlExecOptions;
import dev.nocalhost.plugin.intellij.commands.data.NhctlGlobalOptions;
import dev.nocalhost.plugin.intellij.commands.data.NhctlInstallOptions;
import dev.nocalhost.plugin.intellij.commands.data.NhctlListApplication;
import dev.nocalhost.plugin.intellij.commands.data.NhctlListApplicationOptions;
import dev.nocalhost.plugin.intellij.commands.data.NhctlListPVCOptions;
import dev.nocalhost.plugin.intellij.commands.data.NhctlPVCItem;
import dev.nocalhost.plugin.intellij.commands.data.NhctlPortForwardEndOptions;
import dev.nocalhost.plugin.intellij.commands.data.NhctlPortForwardStartOptions;
import dev.nocalhost.plugin.intellij.commands.data.NhctlResetDevSpaceOptions;
import dev.nocalhost.plugin.intellij.commands.data.NhctlResetOptions;
import dev.nocalhost.plugin.intellij.commands.data.NhctlSyncOptions;
import dev.nocalhost.plugin.intellij.commands.data.NhctlSyncResumeOptions;
import dev.nocalhost.plugin.intellij.commands.data.NhctlSyncStatusOptions;
import dev.nocalhost.plugin.intellij.commands.data.NhctlTerminalOptions;
import dev.nocalhost.plugin.intellij.commands.data.NhctlUninstallOptions;
import dev.nocalhost.plugin.intellij.commands.data.NhctlUpgradeOptions;
import dev.nocalhost.plugin.intellij.exception.NocalhostExecuteCmdException;
import dev.nocalhost.plugin.intellij.settings.NocalhostSettings;
import dev.nocalhost.plugin.intellij.utils.DataUtils;
import dev.nocalhost.plugin.intellij.utils.SudoUtil;


public class NhctlCommand {
    private static final String NHCTL_COMMAND = "nhctl";

    public List<NhctlListApplication> listApplication(NhctlListApplicationOptions opts) throws InterruptedException, NocalhostExecuteCmdException, IOException {
        List<String> args = Lists.newArrayList(getNhctlCmd(), "list", "--yaml");
        String result = execute(args, opts);
        List<Map> mapItems = DataUtils.YAML.load(result);
        List<NhctlListApplication> nhctlListApplications = Lists.newArrayList();
        for (Map map : mapItems) {
            NhctlListApplication nhctlListApplication = new NhctlListApplication();

            nhctlListApplication.setNamespace((String) map.get("namespace"));
            List<Map> appMaps = (List<Map>) map.get("application");
            List<NhctlListApplication.Application> applications = Lists.newArrayList();
            for (Map appMap : appMaps) {
                NhctlListApplication.Application application = new NhctlListApplication.Application();
                application.setName((String) appMap.get("name"));
                application.setType((String) appMap.get("type"));
                applications.add(application);
            }
            nhctlListApplication.setApplication(applications.toArray(new NhctlListApplication.Application[0]));
            nhctlListApplications.add(nhctlListApplication);
        }
        return nhctlListApplications;
    }

    public void install(String name, NhctlInstallOptions opts) throws IOException, InterruptedException, NocalhostExecuteCmdException {
        List<String> args = Lists.newArrayList(getNhctlCmd(), "install", name);
        if (StringUtils.isNotEmpty(opts.getConfig())) {
            args.add("--config");
            args.add(opts.getConfig());
        }
        if (StringUtils.isNotEmpty(opts.getGitRef())) {
            args.add("--git-ref");
            args.add(opts.getGitRef());
        }
        if (StringUtils.isNotEmpty(opts.getGitUrl())) {
            args.add("--git-url");
            args.add(opts.getGitUrl());
        }
        if (StringUtils.isNotEmpty(opts.getHelmChartName())) {
            args.add("--helm-chart-name");
            args.add(opts.getHelmChartName());
        }
        if (StringUtils.isNotEmpty(opts.getHelmRepoName())) {
            args.add("--helm-repo-name");
            args.add(opts.getHelmRepoName());
        }
        if (StringUtils.isNotEmpty(opts.getHelmRepoUrl())) {
            args.add("--helm-repo-url");
            args.add(opts.getHelmRepoUrl());
        }
        if (StringUtils.isNotEmpty(opts.getHelmRepoVersion())) {
            args.add("--helm-repo-version");
            args.add(opts.getHelmRepoVersion());
        }
        if (StringUtils.isNotEmpty(opts.getHelmValues())) {
            args.add("--helm-values");
            args.add(opts.getHelmValues());
        }
        if (opts.isIgnorePreInstall()) {
            args.add("--ignore-pre-install");
        }
        if (StringUtils.isNotEmpty(opts.getOuterConfig())) {
            args.add("--outer-config");
            args.add(opts.getOuterConfig());
        }
        if (opts.getResourcesPath() != null) {
            for (String path : opts.getResourcesPath()) {
                args.add("--resource-path");
                args.add(path);
            }
        }
        if (opts.getValues() != null) {
            String values = opts.getValues().entrySet()
                    .stream()
                    .map((e) -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.toList())
                    .stream()
                    .reduce(",", String::join);
            if (StringUtils.isNotEmpty(values)) {
                args.add("--set");
                args.add(values);
            }
        }
        if (StringUtils.isNotEmpty(opts.getType())) {
            args.add("--type");
            args.add(opts.getType());
        }
        if (opts.isWait()) {
            args.add("--wait");
        }
        if (StringUtils.isNotEmpty(opts.getLocalPath())) {
            args.add("--local-path");
            args.add(opts.getLocalPath());
        }

        execute(args, opts);
    }

    public void uninstall(
            String name, NhctlUninstallOptions opts
    ) throws InterruptedException, IOException, NocalhostExecuteCmdException {
        List<String> args = Lists.newArrayList(getNhctlCmd(), "uninstall", name);
        if (opts.isForce()) {
            args.add("--force");
        }

        execute(args, opts);
    }

    public void resetDevSpace(NhctlResetDevSpaceOptions opts
    ) throws InterruptedException, NocalhostExecuteCmdException, IOException {
        List<String> args = Lists.newArrayList(getNhctlCmd(), "reset");
        execute(args, opts);
    }

    public String devStart(
            String name, NhctlDevStartOptions opts
    ) throws IOException, InterruptedException, NocalhostExecuteCmdException {
        List<String> args = Lists.newArrayList(getNhctlCmd(), "dev", "start", name);
        if (StringUtils.isNotEmpty(opts.getDeployment())) {
            args.add("--deployment");
            args.add(opts.getDeployment());
        }
        if (StringUtils.isNotEmpty(opts.getImage())) {
            args.add("--image");
            args.add(opts.getImage());
        }
        if (opts.getLocalSync() != null) {
            for (String s : opts.getLocalSync()) {
                args.add("--local-sync");
                args.add(s);
            }
        }
        if (StringUtils.isNotEmpty(opts.getSidecarImage())) {
            args.add("--sidecar-image");
            args.add(opts.getSidecarImage());
        }
        if (StringUtils.isNotEmpty(opts.getStorageClass())) {
            args.add("--storage-class");
            args.add(opts.getStorageClass());
        }
        if (StringUtils.isNotEmpty(opts.getSyncthingVersion())) {
            args.add("--syncthing-version");
            args.add(opts.getSyncthingVersion());
        }
        if (StringUtils.isNotEmpty(opts.getWorkDir())) {
            args.add("--work-dir");
            args.add(opts.getWorkDir());
        }
        if (StringUtils.isNotEmpty(opts.getContainer())) {
            args.add("--container");
            args.add(opts.getContainer());
        }

        return execute(args, opts);
    }

    public void devEnd(String name, NhctlDevEndOptions opts) throws IOException, InterruptedException, NocalhostExecuteCmdException {
        List<String> args = Lists.newArrayList(getNhctlCmd(), "dev", "end", name);
        if (StringUtils.isNotEmpty(opts.getDeployment())) {
            args.add("--deployment");
            args.add(opts.getDeployment());
        }

        execute(args, opts);
    }

    public void sync(String name, NhctlSyncOptions opts) throws IOException, InterruptedException, NocalhostExecuteCmdException {
        List<String> args = Lists.newArrayList(getNhctlCmd(), "sync", name);
        if (opts.isDaemon()) {
            args.add("--daemon");
        }
        if (StringUtils.isNotEmpty(opts.getDeployment())) {
            args.add("--deployment");
            args.add(opts.getDeployment());
        }
        if (opts.isDoubleSideSync()) {
            args.add("--double");
        }

        if (StringUtils.isNotEmpty(opts.getContainer())) {
            args.add("--container");
            args.add(opts.getContainer());
        }
        if (opts.getIgnoredPatterns() != null) {
            for (String pattern : opts.getIgnoredPatterns()) {
                args.add("--ignored-pattern");
                args.add(pattern);
            }
        }
        if (opts.getSyncedPatterns() != null) {
            for (String pattern : opts.getSyncedPatterns()) {
                args.add("--synced-pattern");
                args.add(pattern);
            }
        }

        execute(args, opts);
    }

    public void syncResume(String name, NhctlSyncResumeOptions opts) throws InterruptedException, NocalhostExecuteCmdException, IOException {
        List<String> args = Lists.newArrayList(getNhctlCmd(), "sync", name);
        if (StringUtils.isNotEmpty(opts.getDeployment())) {
            args.add("--deployment");
            args.add(opts.getDeployment());
        }
        args.add("--resume");
        execute(args, opts);
    }

    public void startPortForward(String name, NhctlPortForwardStartOptions opts) throws IOException, InterruptedException, NocalhostExecuteCmdException {
        startPortForward(name, opts, null);
    }

    public void startPortForward(String name, NhctlPortForwardStartOptions opts, String sudoPassword) throws IOException, InterruptedException, NocalhostExecuteCmdException {
        List<String> args = Lists.newArrayList(getNhctlCmd(), "port-forward", "start", name);
        if (opts.isDaemon()) {
            args.add("--daemon");
        }
        if (StringUtils.isNotEmpty(opts.getDeployment())) {
            args.add("--deployment");
            args.add(opts.getDeployment());
        }
        if (opts.getDevPorts() != null) {
            for (String port : opts.getDevPorts()) {
                args.add("-p");
                args.add(port);
            }
        }
        if (StringUtils.isNotEmpty(opts.getPod())) {
            args.add("--pod");
            args.add(opts.getPod());
        }
        if (StringUtils.isNotEmpty(opts.getType())) {
            args.add("--type");
            args.add(opts.getType());
        }
        args.add("--way");
        args.add(opts.getWay().getVal());

        execute(args, opts, sudoPassword);
    }

    public void endPortForward(String name, NhctlPortForwardEndOptions opts) throws InterruptedException, NocalhostExecuteCmdException, IOException {
        endPortForward(name, opts, null);
    }

    public void endPortForward(String name, NhctlPortForwardEndOptions opts, String sudoPassword) throws IOException, InterruptedException, NocalhostExecuteCmdException {
        List<String> args = Lists.newArrayList(getNhctlCmd(), "port-forward", "end", name);
        if (StringUtils.isNotEmpty(opts.getDeployment())) {
            args.add("--deployment");
            args.add(opts.getDeployment());
        }
        if (StringUtils.isNotEmpty(opts.getPort())) {
            args.add("--port");
            args.add(opts.getPort());
        }
        if (StringUtils.isNotEmpty(opts.getType())) {
            args.add("--type");
            args.add(opts.getType());
        }

        execute(args, opts, sudoPassword);
    }

    public String describe(String name, NhctlDescribeOptions opts) throws IOException, InterruptedException, NocalhostExecuteCmdException {
        List<String> args = Lists.newArrayList(getNhctlCmd(), "describe", name);
        if (StringUtils.isNotEmpty(opts.getDeployment())) {
            args.add("--deployment");
            args.add(opts.getDeployment());
        }
        if (StringUtils.isNotEmpty(opts.getType())) {
            args.add("--type");
            args.add(opts.getType());
        }
        return execute(args, opts);
    }

    public <T> T describe(String name, NhctlDescribeOptions opts, Class<T> type) throws IOException, InterruptedException, NocalhostExecuteCmdException {
        String result = describe(name, opts);
        return DataUtils.YAML.loadAs(result, type);
    }

    public void reset(String name, NhctlResetOptions opts) throws IOException, InterruptedException, NocalhostExecuteCmdException {
        List<String> args = Lists.newArrayList(getNhctlCmd(), "dev", "reset", name);
        if (StringUtils.isNotEmpty(opts.getDeployment())) {
            args.add("--deployment");
            args.add(opts.getDeployment());
        }
        execute(args, opts);
    }

    public List<String> terminal(String name, NhctlTerminalOptions opts) {
        List<String> args = Lists.newArrayList(getNhctlCmd(), "dev", "terminal", name);
        if (StringUtils.isNotEmpty(opts.getDeployment())) {
            args.add("--deployment");
            args.add(opts.getDeployment());
        }
        if (StringUtils.isNotEmpty(opts.getContainer())) {
            args.add("--container");
            args.add(opts.getContainer());
        }
        if (StringUtils.isNotEmpty(opts.getPod())) {
            args.add("--pod");
            args.add(opts.getPod());
        }
        addGlobalOptions(args, opts);
        return args;
    }

    public String getConfig(String name, NhctlConfigOptions opts) throws IOException, InterruptedException, NocalhostExecuteCmdException {
        List<String> args = Lists.newArrayList(getNhctlCmd(), "config", "get", name);
        if (StringUtils.isNotEmpty(opts.getDeployment())) {
            args.add("--deployment");
            args.add(opts.getDeployment());
        }
        if (opts.isAppConfig()) {
            args.add("--app-config");
        }
        return execute(args, opts);
    }

    public void getTemplateConfig(String name, NhctlConfigOptions opts) throws IOException, InterruptedException, NocalhostExecuteCmdException {
        List<String> args = Lists.newArrayList(getNhctlCmd(), "config", "template", name);
        if (StringUtils.isNotEmpty(opts.getDeployment())) {
            args.add("--deployment");
            args.add(opts.getDeployment());
        }
        execute(args, opts);
    }

    public void editConfig(String name, NhctlConfigOptions opts) throws IOException, InterruptedException, NocalhostExecuteCmdException {
        List<String> args = Lists.newArrayList(getNhctlCmd(), "config", "edit", name);
        if (StringUtils.isNotEmpty(opts.getDeployment())) {
            args.add("--deployment");
            args.add(opts.getDeployment());
        }
        if (StringUtils.isNotEmpty(opts.getContent())) {
            args.add("--content");
            args.add(opts.getContent());
        }
        if (opts.isAppConfig()) {
            args.add("--app-config");
        }
        execute(args, opts);
    }

    public String apply(String name, NhctlApplyOptions opts) throws InterruptedException, NocalhostExecuteCmdException, IOException {
        List<String> args = Lists.newArrayList(getNhctlCmd(), "apply", name);
        if (StringUtils.isNotEmpty(opts.getFile())) {
            args.add(opts.getFile());
        }
        return execute(args, opts);
    }

    public List<NhctlPVCItem> listPVC(NhctlListPVCOptions opts) throws IOException, InterruptedException, NocalhostExecuteCmdException {
        List<String> args = Lists.newArrayList(getNhctlCmd(), "pvc", "list");
        if (StringUtils.isNotEmpty(opts.getApp())) {
            args.add("--app");
            args.add(opts.getApp());
        }
        if (StringUtils.isNotEmpty(opts.getSvc())) {
            args.add("--svc");
            args.add(opts.getSvc());
        }
        args.add("--json");
        String output = execute(args, opts);
        return DataUtils.GSON.fromJson(output, TypeToken.getParameterized(List.class, NhctlPVCItem.class).getType());
    }

    public void cleanPVC(NhctlCleanPVCOptions opts) throws IOException, InterruptedException, NocalhostExecuteCmdException {
        List<String> args = Lists.newArrayList(getNhctlCmd(), "pvc", "clean");
        if (StringUtils.isNotEmpty(opts.getApp())) {
            args.add("--app");
            args.add(opts.getApp());
        }
        if (StringUtils.isNotEmpty(opts.getSvc())) {
            args.add("--svc");
            args.add(opts.getSvc());
        }
        if (StringUtils.isNotEmpty(opts.getName())) {
            args.add("--name");
            args.add(opts.getName());
        }
        execute(args, opts);
    }

    public String syncStatus(String name, NhctlSyncStatusOptions opts) throws InterruptedException, NocalhostExecuteCmdException, IOException {
        List<String> args = Lists.newArrayList(getNhctlCmd(), "sync-status", name, "--deployment", opts.getDeployment());
        return execute(args, opts);
    }

    public void syncStatusOverride(String name, NhctlSyncStatusOptions opts) throws InterruptedException, NocalhostExecuteCmdException, IOException {
        List<String> args = Lists.newArrayList(getNhctlCmd(), "sync-status", name, "--deployment", opts.getDeployment(), "--override");
        execute(args, opts);
    }

    public void upgrade(String name, NhctlUpgradeOptions opts) throws InterruptedException, NocalhostExecuteCmdException, IOException {
        List<String> args = Lists.newArrayList(getNhctlCmd(), "upgrade", name);
        if (StringUtils.isNotEmpty(opts.getConfig())) {
            args.add("--config");
            args.add(opts.getConfig());
        }
        if (StringUtils.isNotEmpty(opts.getGitRef())) {
            args.add("--git-ref");
            args.add(opts.getGitRef());
        }
        if (StringUtils.isNotEmpty(opts.getGitUrl())) {
            args.add("--git-url");
            args.add(opts.getGitUrl());
        }
        if (StringUtils.isNotEmpty(opts.getHelmChartName())) {
            args.add("--helm-chart-name");
            args.add(opts.getHelmChartName());
        }
        if (StringUtils.isNotEmpty(opts.getHelmRepoName())) {
            args.add("--helm-repo-name");
            args.add(opts.getHelmRepoName());
        }
        if (StringUtils.isNotEmpty(opts.getHelmRepoUrl())) {
            args.add("--helm-repo-url");
            args.add(opts.getHelmRepoUrl());
        }
        if (StringUtils.isNotEmpty(opts.getHelmRepoVersion())) {
            args.add("--helm-repo-version");
            args.add(opts.getHelmRepoVersion());
        }
        if (StringUtils.isNotEmpty(opts.getLocalPath())) {
            args.add("--local-path");
            args.add(opts.getLocalPath());
        }
        if (opts.getResourcesPath() != null) {
            opts.getResourcesPath().forEach(e -> {
                args.add("--resource-path");
                args.add(e);
            });
        }
        execute(args, opts);
    }

    public String version() throws InterruptedException, NocalhostExecuteCmdException, IOException {
        List<String> args = Lists.newArrayList(getNhctlCmd(), "version");
        return execute(args, null);
    }

    public void exec(String name, NhctlExecOptions opts) throws InterruptedException, NocalhostExecuteCmdException, IOException {
        List<String> args = Lists.newArrayList(getNhctlCmd(), "exec", name);
        if (opts.getCommand() != null) {
            opts.getCommand().forEach(e -> {
                args.add("--command");
                args.add(e);
            });
        }
        if (StringUtils.isNotEmpty(opts.getDeployment())) {
            args.add("--deployment");
            args.add(opts.getDeployment());
        }
        execute(args, opts);
    }

    protected String execute(List<String> args, NhctlGlobalOptions opts) throws IOException, InterruptedException, NocalhostExecuteCmdException {
        return execute(args, opts, null);
    }

    protected String execute(List<String> args, NhctlGlobalOptions opts, String sudoPassword) throws IOException, InterruptedException, NocalhostExecuteCmdException {
        addGlobalOptions(args, opts);

        String cmd = String.join(" ", args.toArray(new String[]{}));

        if (sudoPassword != null) {
            args = SudoUtil.toSudoCommand(args);
        }

        GeneralCommandLine commandLine = getCommandline(args);
        System.out.println("Execute command: " + cmd);
        Process process;
        try {
            process = commandLine.createProcess();
            if (sudoPassword != null) {
                SudoUtil.inputPassword(process, sudoPassword);
            }
        } catch (ExecutionException e) {
            throw new NocalhostExecuteCmdException(cmd, -1, e.getMessage());
        }

        String output = CharStreams.toString(new InputStreamReader(
                process.getInputStream(), Charsets.UTF_8));
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new NocalhostExecuteCmdException(cmd, exitCode, output);
        }

        return output;
    }

    protected void addGlobalOptions(List<String> args, NhctlGlobalOptions opts) {
        if (opts == null) {
            return;
        }
        if (opts.isDebug()) {
            args.add("--debug");
        }
        if (StringUtils.isNotEmpty(opts.getKubeconfig())) {
            args.add("--kubeconfig");
            args.add(opts.getKubeconfig());
        }
        if (StringUtils.isNotEmpty(opts.getNamespace())) {
            args.add("--namespace");
            args.add(opts.getNamespace());
        }
    }

    protected String getNhctlCmd() {
        final NocalhostSettings nocalhostSettings = ServiceManager.getService(NocalhostSettings.class);
        String nhctlCmd = NHCTL_COMMAND;
        if (StringUtils.isNoneBlank(nocalhostSettings.getNhctlBinary())) {
            nhctlCmd = nocalhostSettings.getNhctlBinary();
        }
        return nhctlCmd;
    }

    protected GeneralCommandLine getCommandline(List<String> args) {
        final Map<String, String> environment = new HashMap<>(EnvironmentUtil.getEnvironmentMap());
        environment.put("DISABLE_SPINNER", "true");
        if (SystemInfo.isMac || SystemInfo.isLinux) {
            String path = environment.get("PATH");
            String nhctlCmd = getNhctlCmd();
            if (StringUtils.contains(nhctlCmd, "/")) {
                path = nhctlCmd.substring(0, nhctlCmd.lastIndexOf("/")) + ":" + path;
                environment.put("PATH", path);
            }
        }
        return new GeneralCommandLine(args).withEnvironment(environment).withRedirectErrorStream(true);
    }
}
