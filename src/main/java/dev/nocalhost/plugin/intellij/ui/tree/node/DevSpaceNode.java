package dev.nocalhost.plugin.intellij.ui.tree.node;

import javax.swing.tree.DefaultMutableTreeNode;

import dev.nocalhost.plugin.intellij.api.data.DevSpace;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@Deprecated
public class DevSpaceNode extends DefaultMutableTreeNode {
    private DevSpace devSpace;
    private boolean expanded;

    public DevSpaceNode(DevSpace devSpace) {
        this(devSpace, false);
    }

    public DevSpaceNode clone() {
        return new DevSpaceNode(devSpace, expanded);
    }
}
