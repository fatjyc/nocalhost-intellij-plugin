package dev.nocalhost.plugin.intellij.commands.data;

import java.util.List;

import dev.nocalhost.plugin.intellij.api.data.DevSpace;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NhctlSyncOptions extends NhctlGlobalOptions {
    private boolean daemon;
    private String deployment;
    private boolean doubleSideSync;
    private String container;
    private List<String> ignoredPatterns;
    private List<String> syncedPatterns;

    public NhctlSyncOptions(DevSpace devSpace) {
        super(devSpace);
    }
}
