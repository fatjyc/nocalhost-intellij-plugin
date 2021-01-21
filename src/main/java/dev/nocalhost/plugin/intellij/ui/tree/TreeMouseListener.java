package dev.nocalhost.plugin.intellij.ui.tree;


import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.JBMenuItem;
import com.intellij.openapi.ui.JBPopupMenu;
import com.intellij.ui.treeStructure.Tree;

import org.apache.commons.lang3.StringUtils;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;

import javax.swing.tree.TreePath;

import dev.nocalhost.plugin.intellij.api.data.DevSpace;
import dev.nocalhost.plugin.intellij.commands.NhctlCommand;
import dev.nocalhost.plugin.intellij.commands.data.NhctlDescribeOptions;
import dev.nocalhost.plugin.intellij.commands.data.NhctlDescribeResult;
import dev.nocalhost.plugin.intellij.ui.InstallDevSpaceDialog;
import dev.nocalhost.plugin.intellij.ui.tree.listerner.devspace.Uninstall;
import dev.nocalhost.plugin.intellij.ui.tree.listerner.workload.Config;
import dev.nocalhost.plugin.intellij.ui.tree.listerner.workload.EndDevelop;
import dev.nocalhost.plugin.intellij.ui.tree.listerner.workload.Logs;
import dev.nocalhost.plugin.intellij.ui.tree.listerner.workload.Reset;
import dev.nocalhost.plugin.intellij.ui.tree.listerner.workload.StartDevelop;
import dev.nocalhost.plugin.intellij.ui.tree.listerner.workload.Terminal;
import dev.nocalhost.plugin.intellij.ui.tree.node.DevSpaceNode;
import dev.nocalhost.plugin.intellij.ui.tree.node.ResourceNode;

public class TreeMouseListener extends MouseAdapter {
    private final Project project;
    private final Tree tree;

    public TreeMouseListener(Tree tree, Project project) {
        this.tree = tree;
        this.project = project;
    }

    private TreePath getPath(MouseEvent e) {
        TreePath treePath = tree.getClosestPathForLocation(e.getX(), e.getY());
        if (treePath == null) {
            treePath = tree.getPathForLocation(e.getX(), e.getY());
        }
        return treePath;
    }

    @Override
    public void mouseClicked(MouseEvent e) {
        if (e.getClickCount() == 2 && e.getButton() == MouseEvent.BUTTON1) {
            TreePath treePath = getPath(e);
            if (treePath == null) {
                return;
            }
            Object object = treePath.getLastPathComponent();
            if (object instanceof DevSpaceNode) {
                DevSpaceNode devSpaceNode = (DevSpaceNode) object;
                if (devSpaceNode.getDevSpace().getInstallStatus() == 1) {
                    return;
                }
                new InstallDevSpaceDialog(devSpaceNode.getDevSpace()).showAndGet();
            }
        }
    }

    @Override
    public void mouseReleased(MouseEvent mouseEvent) {
        if (mouseEvent.getButton() == MouseEvent.BUTTON3) {
            TreePath treePath = getPath(mouseEvent);
            if (treePath == null) {
                return;
            }
            Object object = treePath.getLastPathComponent();

            if (object instanceof DevSpaceNode) {
                DevSpaceNode devSpaceNode = (DevSpaceNode) object;

                JBPopupMenu menu = new JBPopupMenu();
                if (devSpaceNode.getDevSpace().getInstallStatus() == 1) {
                    JBMenuItem item = new JBMenuItem("Uninstall App");
                    item.addActionListener(new Uninstall(devSpaceNode));
                    menu.add(item);

                    menu.addSeparator();
                    menu.add(new JBMenuItem("Clear persistent data"));
                    menu.add(new JBMenuItem("Reset"));

                    menu.addSeparator();
                    menu.add(new JBMenuItem("Load resource"));
                } else if (devSpaceNode.getDevSpace().getInstallStatus() == 0) {
                    JBMenuItem item = new JBMenuItem("Install App");
                    item.addActionListener(e -> {
                        new InstallDevSpaceDialog(devSpaceNode.getDevSpace()).showAndGet();
                    });
                    menu.add(item);

                    menu.addSeparator();
                    menu.add(new JBMenuItem("Clear persistent data"));
                    menu.add(new JBMenuItem("Reset"));
                }

                JBPopupMenu.showByEvent(mouseEvent, menu);
                return;
            }

            if (object instanceof ResourceNode) {
                ResourceNode resourceNode = (ResourceNode) object;
                if (!StringUtils.equalsIgnoreCase(resourceNode.getKubeResource().getKind(), "Deployment")) {
                    return;
                }

                final String workloadName = resourceNode.getKubeResource().getMetadata().getName();
                final DevSpace devSpace = ((DevSpaceNode) resourceNode.getParent().getParent().getParent()).getDevSpace();

                JBPopupMenu menu = new JBPopupMenu();

                final NhctlCommand nhctlCommand = ServiceManager.getService(NhctlCommand.class);
                final NhctlDescribeOptions opts = new NhctlDescribeOptions();
                opts.setDeployment(workloadName);
                try {
                    final NhctlDescribeResult describeResult = nhctlCommand.describe(devSpace.getContext().getApplicationName(), opts);
                    if (!describeResult.isDeveloping()) {
                        JBMenuItem item = new JBMenuItem("Start Develop");
                        item.addActionListener(new StartDevelop(resourceNode));
                        menu.add(item);
                    } else {
                        JBMenuItem item = new JBMenuItem("End Develop");
                        item.addActionListener(new EndDevelop(resourceNode, project));
                        menu.add(item);
                    }
                } catch (IOException | InterruptedException ioException) {
                    throw new RuntimeException(ioException);
                }
                JBMenuItem clearPersistentDataItem = new JBMenuItem("Clear persistent data");
                JBMenuItem configItem = new JBMenuItem("Config");
                configItem.addActionListener(new Config(resourceNode, project));
                JBMenuItem logsItem = new JBMenuItem("Logs");
                logsItem.addActionListener(new Logs(resourceNode, project));
                JBMenuItem portForwardItem = new JBMenuItem("Port Forward");
                JBMenuItem resetItem = new JBMenuItem("Reset");
                resetItem.addActionListener(new Reset(resourceNode, project));
                JBMenuItem terminalItem = new JBMenuItem("Terminal");
                terminalItem.addActionListener(new Terminal(resourceNode, project));
                menu.add(configItem);
                menu.addSeparator();
                menu.add(clearPersistentDataItem);
                menu.addSeparator();
                menu.add(logsItem);
                menu.add(portForwardItem);
                menu.add(resetItem);
                menu.add(terminalItem);
                JBPopupMenu.showByEvent(mouseEvent, menu);
                return;
            }
        }
    }
}