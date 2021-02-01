package dev.nocalhost.plugin.intellij.commands.data;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NhctlDescribeService {
    private NhctlRawConfig rawConfig;
    private String actualName;
    private boolean developing;
    private boolean portForwarded;
    private boolean syncing;
    private List<String> portForwardStatusList;
}
