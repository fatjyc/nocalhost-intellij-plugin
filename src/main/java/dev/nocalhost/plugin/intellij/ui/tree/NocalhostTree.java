package dev.nocalhost.plugin.intellij.ui.tree;

import com.google.common.collect.Lists;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.ui.LoadingNode;
import com.intellij.ui.treeStructure.Tree;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.ExpandVetoException;
import javax.swing.tree.MutableTreeNode;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

import dev.nocalhost.plugin.intellij.api.data.Application;
import dev.nocalhost.plugin.intellij.api.data.DevSpace;
import dev.nocalhost.plugin.intellij.api.data.ServiceAccount;
import dev.nocalhost.plugin.intellij.commands.KubectlCommand;
import dev.nocalhost.plugin.intellij.commands.NhctlCommand;
import dev.nocalhost.plugin.intellij.commands.data.AliveDeployment;
import dev.nocalhost.plugin.intellij.commands.data.KubeResource;
import dev.nocalhost.plugin.intellij.commands.data.KubeResourceList;
import dev.nocalhost.plugin.intellij.commands.data.NhctlDescribeAllService;
import dev.nocalhost.plugin.intellij.commands.data.NhctlDescribeOptions;
import dev.nocalhost.plugin.intellij.commands.data.NhctlDescribeService;
import dev.nocalhost.plugin.intellij.commands.data.NhctlListApplication;
import dev.nocalhost.plugin.intellij.exception.NocalhostExecuteCmdException;
import dev.nocalhost.plugin.intellij.exception.NocalhostNotifier;
import dev.nocalhost.plugin.intellij.helpers.UserDataKeyHelper;
import dev.nocalhost.plugin.intellij.settings.NocalhostRepo;
import dev.nocalhost.plugin.intellij.settings.NocalhostSettings;
import dev.nocalhost.plugin.intellij.topic.NocalhostTreeDataUpdateNotifier;
import dev.nocalhost.plugin.intellij.topic.NocalhostTreeUiUpdateNotifier;
import dev.nocalhost.plugin.intellij.ui.tree.node.AccountNode;
import dev.nocalhost.plugin.intellij.ui.tree.node.ApplicationNode;
import dev.nocalhost.plugin.intellij.ui.tree.node.DefaultResourceNode;
import dev.nocalhost.plugin.intellij.ui.tree.node.DevSpaceNode;
import dev.nocalhost.plugin.intellij.ui.tree.node.ResourceGroupNode;
import dev.nocalhost.plugin.intellij.ui.tree.node.ResourceNode;
import dev.nocalhost.plugin.intellij.ui.tree.node.ResourceTypeNode;
import dev.nocalhost.plugin.intellij.ui.tree.node.ServiceAccountNode;

import static dev.nocalhost.plugin.intellij.utils.Constants.DEFAULT_APPLICATION_NAME;
import static dev.nocalhost.plugin.intellij.utils.Constants.HELM_ANNOTATION_NAME;
import static dev.nocalhost.plugin.intellij.utils.Constants.NOCALHOST_ANNOTATION_NAME;

public class NocalhostTree extends Tree implements Disposable {
    private static final Logger LOG = Logger.getInstance(NocalhostTree.class);

    private static final List<Pair<String, List<String>>> PAIRS = Lists.newArrayList(
            Pair.create("Workloads", Lists.newArrayList(
                    "Deployments",
                    "DaemonSets",
                    "StatefulSets",
                    "Jobs",
                    "CronJobs",
                    "Pods"
            )),
            Pair.create("Network", Lists.newArrayList(
                    "Services",
                    "Endpoints",
                    "Ingresses",
                    "Network Policies"
            )),
            Pair.create("Configuration", Lists.newArrayList(
                    "ConfigMaps",
                    "Secrets",
                    "Resource Quotas",
                    "HPA",
                    "Pod Disruption Budgets"
            )),
            Pair.create("Storage", Lists.newArrayList(
                    "Persistent Volumes",
                    "Persistent Volume Claims",
                    "Storage Classes"
            ))
    );

    private final Project project;
    private final DefaultTreeModel model;
    private final DefaultMutableTreeNode root;
    private final AtomicBoolean updatingDecSpaces = new AtomicBoolean(false);

    public NocalhostTree(Project project) {
        super(new DefaultTreeModel(new DefaultMutableTreeNode(new Object())));

        this.project = project;
        model = (DefaultTreeModel) this.getModel();
        root = (DefaultMutableTreeNode) model.getRoot();

        init();

        model.insertNodeInto(new LoadingNode(), root, 0);
        model.reload();
    }

    private void init() {
        this.expandPath(new TreePath(root.getPath()));
        this.setRootVisible(false);
        this.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        this.setCellRenderer(new TreeNodeRenderer());
        this.addMouseListener(new TreeMouseListener(this, project));
        this.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent event) throws ExpandVetoException {
                Object node = event.getPath().getLastPathComponent();
                if (node instanceof ResourceTypeNode) {
                    ResourceTypeNode resourceTypeNode = (ResourceTypeNode) node;
                    if (!resourceTypeNode.isLoaded() && model.getChildCount(resourceTypeNode) == 0) {
                        model.insertNodeInto(new LoadingNode(), resourceTypeNode, model.getChildCount(resourceTypeNode));
                    }
                    return;
                }
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent event) throws ExpandVetoException {

            }
        });
        this.addTreeExpansionListener(new TreeExpansionListener() {
            @Override
            public void treeExpanded(TreeExpansionEvent event) {
                Object node = event.getPath().getLastPathComponent();
                if (node instanceof ServiceAccountNode) {
                    ServiceAccountNode serviceAccountNode = (ServiceAccountNode) node;
                    serviceAccountNode.setExpanded(false);
                    return;
                }
                if (node instanceof ApplicationNode) {
                    ApplicationNode applicationNode = (ApplicationNode) node;
                    applicationNode.setExpanded(true);
                    return;
                }
                if (node instanceof ResourceGroupNode) {
                    ResourceGroupNode resourceGroupNode = (ResourceGroupNode) node;
                    resourceGroupNode.setExpanded(true);
                    return;
                }
                if (node instanceof ResourceTypeNode) {
                    ResourceTypeNode resourceTypeNode = (ResourceTypeNode) node;
                    resourceTypeNode.setExpanded(true);

                    ProgressManager.getInstance().run(new Task.Backgroundable(project, "Fetching nocalhost data", false) {
                        @Override
                        public void run(@NotNull ProgressIndicator indicator) {
                            if (!resourceTypeNode.isLoaded()) {
                                try {
                                    loadKubeResources(resourceTypeNode);
                                    resourceTypeNode.setLoaded(true);
                                } catch (IOException | InterruptedException | NocalhostExecuteCmdException e) {
                                    LOG.error("error occurred while loading kube resources", e);
                                    if (StringUtils.contains(e.getMessage(), "No such file or directory")) {
                                        NocalhostNotifier.getInstance(project).notifyKubectlNotFound();
                                    } else {
                                        NocalhostNotifier.getInstance(project).notifyError("Nocalhost fetch data error", "Error occurred while fetching data", e.getMessage());
                                    }
                                }
                            }
                        }
                    });
                }
            }

            @Override
            public void treeCollapsed(TreeExpansionEvent event) {
                Object node = event.getPath().getLastPathComponent();
                if (node instanceof ServiceAccountNode) {
                    ServiceAccountNode serviceAccountNode = (ServiceAccountNode) node;
                    serviceAccountNode.setExpanded(false);
                    return;
                }
                if (node instanceof ApplicationNode) {
                    ApplicationNode applicationNode = (ApplicationNode) node;
                    applicationNode.setExpanded(false);
                    return;
                }
                if (node instanceof ResourceGroupNode) {
                    ResourceGroupNode resourceGroupNode = (ResourceGroupNode) node;
                    resourceGroupNode.setExpanded(false);
                    return;
                }
                if (node instanceof ResourceTypeNode) {
                    ResourceTypeNode resourceTypeNode = (ResourceTypeNode) node;
                    resourceTypeNode.setExpanded(false);
                    return;
                }
            }
        });

        ApplicationManager.getApplication().getMessageBus().connect(this).subscribe(
                NocalhostTreeUiUpdateNotifier.NOCALHOST_TREE_UI_UPDATE_NOTIFIER_TOPIC,
                this::updateTree
        );
    }

    public void updateDevSpaces() {
        ApplicationManager.getApplication().getMessageBus().syncPublisher(
                NocalhostTreeDataUpdateNotifier.NOCALHOST_TREE_DATA_UPDATE_NOTIFIER_TOPIC
        ).action();
    }

    private void updateTree(List<ServiceAccount> serviceAccounts,
                            List<Application> applications,
                            List<NhctlListApplication> nhctlListApplications) {
        try {
            updateDevSpaces(serviceAccounts, applications, nhctlListApplications);
        } catch (InterruptedException | NocalhostExecuteCmdException | IOException e) {
            LOG.error(e);
            if (StringUtils.contains(e.getMessage(), "No such file or directory")) {
                NocalhostNotifier.getInstance(project).notifyNhctlNotFound();
            } else {
                NocalhostNotifier.getInstance(project).notifyError(
                        "Nocalhost update tree error",
                        "Error occurred while updating tree",
                        e.getMessage());
            }
        }
    }

    private void updateDevSpaces(List<ServiceAccount> serviceAccounts, List<Application> applications, List<NhctlListApplication> nhctlListApplications) throws InterruptedException, NocalhostExecuteCmdException, IOException {
        boolean needReload = false;

        if (model.getChild(root, 0) instanceof LoadingNode) {
            model.removeNodeFromParent((MutableTreeNode) model.getChild(root, 0));
            final NocalhostSettings nocalhostSettings = ServiceManager.getService(NocalhostSettings.class);
            model.insertNodeInto(new AccountNode(nocalhostSettings.getUserInfo()), root, 0);
            needReload = true;
        }

        for (int i = model.getChildCount(root) - 1; i >= 1; i--) {
            ServiceAccountNode serviceAccountNode = (ServiceAccountNode) model.getChild(root, i);
            final Optional<ServiceAccount> serviceAccountOptional = serviceAccounts.stream().filter(sa -> sa.getClusterId() == serviceAccountNode.getServiceAccount().getClusterId()).findFirst();
            boolean toBeRemoved = serviceAccountOptional.isEmpty();

            if (toBeRemoved) {
                model.removeNodeFromParent(serviceAccountNode);
                needReload = true;
            }
        }

        for (int i = 0; i < serviceAccounts.size(); i++) {
            ServiceAccount serviceAccount = serviceAccounts.get(i);
            final Optional<NhctlListApplication> nhctlListApplicationOptional = nhctlListApplications
                    .stream()
                    .filter(a -> StringUtils.equals(a.getNamespace(), devSpace.getNamespace())).findFirst();

            if (model.getChildCount(root) <= i + 1) {
                model.insertNodeInto(createServiceAccountNode(serviceAccount, applications, nhctlListApplicationOptional), root, model.getChildCount(root));
                needReload = true;
                continue;
            }

            ServiceAccountNode serviceAccountNode = (ServiceAccountNode) model.getChild(root, i + 1);

            if (serviceAccountNode.getServiceAccount().getClusterId() != serviceAccount.getClusterId()) {
                model.insertNodeInto(createServiceAccountNode(serviceAccount, applications, nhctlListApplicationOptional), root, i + 1);
                continue;
            }

            if (serviceAccountNode.getServiceAccount().getClusterId() == serviceAccount.getClusterId()) {
                if (nhctlListApplicationOptional.isPresent()) {
                    final List<NhctlListApplication.Application> collect = Arrays.stream(nhctlListApplicationOptional.get().getApplication()).filter(a -> !DEFAULT_APPLICATION_NAME.equals(a.getName())).collect(Collectors.toList());
                    if (collect.size() + 1 != serviceAccountNode.getChildCount()) {
                        model.removeNodeFromParent(serviceAccountNode);
                        model.insertNodeInto(createServiceAccountNode(serviceAccount, applications, nhctlListApplicationOptional), root, i + 1);
                    }
                } else {
                    model.removeNodeFromParent(serviceAccountNode);
                    model.insertNodeInto(createServiceAccountNode(serviceAccount, applications, Optional.empty()), root, i + 1);
                }
            }

        }

        if (needReload) {
            model.reload();
        }

        for (int i = 1; i < model.getChildCount(root); i++) {
            makeExpandedVisible((DevSpaceNode) model.getChild(root, i));
        }

        for (int i = 1; i < model.getChildCount(root); i++) {
            loadResourceNodes((DevSpaceNode) model.getChild(root, i));
        }
    }

    private void loadResourceNodes(DevSpaceNode devSpaceNode) throws InterruptedException, NocalhostExecuteCmdException, IOException {
        for (int i = 0; i < model.getChildCount(devSpaceNode); i++) {
            final Object child = model.getChild(devSpaceNode, i);
            for (int j = 0; j < model.getChildCount(child); j++) {
                ResourceGroupNode resourceGroupNode = (ResourceGroupNode) model.getChild(child, j);
                for (int k = 0; k < model.getChildCount(resourceGroupNode); k++) {
                    ResourceTypeNode resourceTypeNode = (ResourceTypeNode) model.getChild(resourceGroupNode, k);
                    if (!resourceTypeNode.isLoaded()) {
                        continue;
                    }
                    loadKubeResources(resourceTypeNode);
                }
            }
        }
    }

    private void loadKubeResources(ResourceTypeNode resourceTypeNode) throws InterruptedException, NocalhostExecuteCmdException, IOException {
        final KubectlCommand kubectlCommand = ServiceManager.getService(KubectlCommand.class);
        final DevSpace devSpace = ((DevSpaceNode) resourceTypeNode.getParent().getParent().getParent()).getDevSpace();
        Application application = null;
        if (resourceTypeNode.getParent().getParent() instanceof ApplicationNode) {
            application = ((ApplicationNode) resourceTypeNode.getParent().getParent()).getApplication();
        }

        String resourceName = resourceTypeNode.getName().toLowerCase().replaceAll(" ", "");
        KubeResourceList kubeResourceList = kubectlCommand.getResourceList(resourceName, null, devSpace);

        final NhctlCommand nhctlCommand = ServiceManager.getService(NhctlCommand.class);
        List<ResourceNode> resourceNodes = Lists.newArrayList();
        final NhctlDescribeOptions nhctlDescribeOptions = new NhctlDescribeOptions(devSpace);
        final NocalhostSettings nocalhostSettings = ServiceManager.getService(NocalhostSettings.class);
        List<KubeResource> resources;
        String applicationName;
        if (application == null) {
            applicationName = DEFAULT_APPLICATION_NAME;
        } else {
            applicationName = application.getContext().getApplicationName();
        }
        NhctlDescribeAllService nhctlDescribeAllService = nhctlCommand.describe(applicationName, nhctlDescribeOptions, NhctlDescribeAllService.class);
        NhctlDescribeService[] nhctlDescribeServices = nhctlDescribeAllService.getSvcProfile();
        resources = kubeResourceList.getItems()
                                    .stream()
                                    .filter(i -> StringUtils.equals(i.getMetadata().getAnnotations().get(NOCALHOST_ANNOTATION_NAME), applicationName)
                                            || StringUtils.equals(i.getMetadata().getAnnotations().get(HELM_ANNOTATION_NAME), applicationName))
                                    .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(resources)){
            resources = kubeResourceList.getItems()
                                        .stream()
                                        .filter(i -> StringUtils.isBlank(i.getMetadata().getAnnotations().get(NOCALHOST_ANNOTATION_NAME))
                                                && StringUtils.isBlank(i.getMetadata().getAnnotations().get(HELM_ANNOTATION_NAME)))
                                        .collect(Collectors.toList());
        }
        for (KubeResource kubeResource : resources) {
            final Optional<NhctlDescribeService> nhctlDescribeService =
                    ArrayUtils.isEmpty(nhctlDescribeServices) ? Optional.empty() : Arrays.stream(nhctlDescribeServices).filter(svc -> svc.getRawConfig().getName().equals(kubeResource.getMetadata().getName())).findFirst();
            if (StringUtils.equalsIgnoreCase(kubeResource.getKind(), "Deployment") && nhctlDescribeService.isPresent()) {
                NhctlDescribeService nhctlDescribe = nhctlDescribeService.get();
                final Optional<NocalhostRepo> nocalhostRepo =
                        nocalhostSettings.getRepos().stream()
                                         .filter(repo -> Objects.equals(nocalhostSettings.getBaseUrl(), repo.getHost())
                                                 && Objects.equals(nocalhostSettings.getUserInfo().getEmail(), repo.getEmail())
                                                 && Objects.equals(applicationName, repo.getAppName())
                                                 && Objects.equals(devSpace.getId(), repo.getDevSpaceId())
                                                 && Objects.equals(nhctlDescribe.getRawConfig().getName(), repo.getDeploymentName()))
                                         .findFirst();
                if (nhctlDescribe.isDeveloping()) {
                    nocalhostRepo.ifPresent(repos -> UserDataKeyHelper.addAliveDeployments(project, new AliveDeployment(devSpace, applicationName, nhctlDescribe.getRawConfig().getName(), repos.getRepoPath())));
                } else {
                    nocalhostRepo.ifPresent(repos -> UserDataKeyHelper.removeAliveDeployments(project, new AliveDeployment(devSpace, applicationName, nhctlDescribe.getRawConfig().getName(), repos.getRepoPath())));
                }
                resourceNodes.add(new ResourceNode(kubeResource, nhctlDescribe));
            } else if (StringUtils.equalsIgnoreCase(kubeResource.getKind(), "StatefulSet")) {
                String metadataName = kubeResource.getMetadata().getName();
                KubeResource statefulsetKubeResource = kubectlCommand.getResource("StatefulSet/" + metadataName, "", devSpace);
                if (nhctlDescribeService.isPresent()) {
                    resourceNodes.add(new ResourceNode(statefulsetKubeResource, nhctlDescribeService.get()));
                } else {
                    resourceNodes.add(new ResourceNode(statefulsetKubeResource));
                }
            } else {
                resourceNodes.add(new ResourceNode(kubeResource));
            }
        }

        boolean needReload = false;

        if (resourceTypeNode.isLoaded()) {
            for (int k = model.getChildCount(resourceTypeNode) - 1; k >= 0; k--) {
                ResourceNode resourceNode = (ResourceNode) model.getChild(resourceTypeNode, k);
                boolean toBeRemoved = true;

                for (ResourceNode rn : resourceNodes) {
                    if (StringUtils.equals(rn.resourceName(), resourceNode.resourceName())) {
                        toBeRemoved = false;
                        break;
                    }
                }

                if (toBeRemoved) {
                    model.removeNodeFromParent(resourceNode);
                    needReload = true;
                }
            }
        } else {
            for (int i = model.getChildCount(resourceTypeNode) - 1; i >= 0; i--) {
                model.removeNodeFromParent((MutableTreeNode) model.getChild(resourceTypeNode, i));
                needReload = true;
            }
        }

        for (int i = 0; i < resourceNodes.size(); i++) {
            ResourceNode resourceNode = resourceNodes.get(i);

            if (model.getChildCount(resourceTypeNode) <= i) {
                model.insertNodeInto(resourceNode, resourceTypeNode, model.getChildCount(resourceTypeNode));
                needReload = true;
                continue;
            }

            ResourceNode rn = (ResourceNode) model.getChild(resourceTypeNode, i);

            if (StringUtils.equals(resourceNode.resourceName(), rn.resourceName())) {
                rn.setKubeResource(resourceNode.getKubeResource());
                rn.setNhctlDescribeService(resourceNode.getNhctlDescribeService());
                model.reload(rn);
            }
        }

        if (needReload) {
            model.reload(resourceTypeNode);
        }
    }

    private void makeExpandedVisible(DevSpaceNode devSpaceNode) {
        boolean devSpaceNodeExpanded = false;
        for (int i = 0; i < devSpaceNode.getChildCount(); i++) {
            final TreeNode child = devSpaceNode.getChildAt(i);
            if (child instanceof ApplicationNode) {
                ApplicationNode applicationNode = (ApplicationNode) child;
                boolean applicationNodeExpanded = false;
                for (int j = 0; j < applicationNode.getChildCount(); j++) {
                    ResourceGroupNode resourceGroupNode = (ResourceGroupNode) applicationNode.getChildAt(j);
                    boolean resourceGroupNodeExpanded = false;
                    for (int k = 0; k < resourceGroupNode.getChildCount(); k++) {
                        ResourceTypeNode resourceTypeNode = (ResourceTypeNode) resourceGroupNode.getChildAt(k);
                        if (resourceTypeNode.isExpanded()) {
                            this.expandPath(new TreePath(model.getPathToRoot(resourceTypeNode)));
                            resourceGroupNodeExpanded = true;
                        }
                    }
                    if (resourceGroupNode.isExpanded() && !resourceGroupNodeExpanded) {
                        this.expandPath(new TreePath(model.getPathToRoot(resourceGroupNode)));
                        applicationNodeExpanded = true;
                    }
                }
                if (applicationNode.isExpanded() && !applicationNodeExpanded) {
                    this.expandPath(new TreePath(model.getPathToRoot(applicationNode)));
                    devSpaceNodeExpanded = true;
                }
            }
        }
        if (devSpaceNode.isExpanded() && !devSpaceNodeExpanded) {
            this.expandPath(new TreePath(model.getPathToRoot(devSpaceNode)));
        }
    }

    private DevSpaceNode createDevSpaceNode(DevSpace devSpace, List<Application> applications, Optional<NhctlListApplication> nhctlListApplicationOptional) {
        DevSpaceNode devSpaceNode = new DevSpaceNode(devSpace);
        if (nhctlListApplicationOptional.isPresent()) {
            final NhctlListApplication.Application[] apps = nhctlListApplicationOptional.get().getApplication();
            for (NhctlListApplication.Application app : apps) {
                final Optional<Application> installedApp = applications.stream().filter(a -> StringUtils.equals(a.getContext().getApplicationName(), app.getName())).findFirst();
                if (installedApp.isPresent()) {
                    ApplicationNode applicationNode = new ApplicationNode(installedApp.get(), devSpace);
                    applicationNode.setInstalled(true);
                    for (Pair<String, List<String>> pair : PAIRS) {
                        applicationNode.add(generateResourceGroup(pair));
                    }
                    ResourceGroupNode resourceGroupNode = (ResourceGroupNode) applicationNode.getChildAt(0);
                    resourceGroupNode.setExpanded(true);
                    devSpaceNode.add(applicationNode);
                }
            }
        }
        DefaultResourceNode defaultResourceNode = new DefaultResourceNode();
        for (Pair<String, List<String>> pair : PAIRS) {
            defaultResourceNode.add(generateResourceGroup(pair));
        }
        ResourceGroupNode resourceGroupNode = (ResourceGroupNode) defaultResourceNode.getChildAt(0);
        resourceGroupNode.setExpanded(true);
        devSpaceNode.add(defaultResourceNode);
        return devSpaceNode;
    }

    private ResourceGroupNode generateResourceGroup(Pair<String, List<String>> pair) {
        ResourceGroupNode resourceGroupNode = new ResourceGroupNode(pair.first);
        for (String name : pair.second) {
            resourceGroupNode.add(new ResourceTypeNode(name));
        }
        return resourceGroupNode;
    }

    @Override
    public void dispose() {

    }
}