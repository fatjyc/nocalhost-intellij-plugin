package dev.nocalhost.plugin.intellij.topic;

import com.intellij.util.messages.Topic;

import java.util.List;

import dev.nocalhost.plugin.intellij.ui.tree.node.ResourceNode;
import dev.nocalhost.plugin.intellij.ui.tree.node.ResourceTypeNode;

public interface NocalhostTreeResourcesUpdateNotifier {
    @Topic.ProjectLevel
    Topic<NocalhostTreeResourcesUpdateNotifier> NOCALHOST_TREE_RESOURCES_UPDATE_NOTIFIER_TOPIC =
            new Topic<>(NocalhostTreeResourcesUpdateNotifier.class);

    void action(ResourceTypeNode resourceTypeNode, List<ResourceNode> resources);
}
