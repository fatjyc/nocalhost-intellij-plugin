package dev.nocalhost.plugin.intellij.ui.vfs;

import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.impl.FileDocumentManagerImpl;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageDialogBuilder;
import com.intellij.openapi.util.NlsSafe;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileSystem;

import org.apache.commons.io.IOUtils;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import dev.nocalhost.plugin.intellij.api.data.DevSpace;
import dev.nocalhost.plugin.intellij.commands.NhctlCommand;
import dev.nocalhost.plugin.intellij.commands.data.NhctlApplyOptions;
import dev.nocalhost.plugin.intellij.exception.NocalhostNotifier;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;

@AllArgsConstructor
public class KubeConfigFile extends VirtualFile {
    private static final Logger LOG = Logger.getInstance(KubeConfigFile.class);

    private String name;
    private String path;
    private String resourceName;
    private String content;
    private Project project;
    private DevSpace devSpace;
    private String appName;

    @Override
    public @NotNull @NlsSafe String getName() {
        return name;
    }

    @Override
    public @NotNull VirtualFileSystem getFileSystem() {
        return new KubeConfigFileSystem();
    }

    @Override
    public @NonNls
    @NotNull String getPath() {
        return path;
    }

    @Override
    public boolean isWritable() {
        return true;
    }

    @Override
    public boolean isDirectory() {
        return false;
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public VirtualFile getParent() {
        return null;
    }

    @Override
    public VirtualFile[] getChildren() {
        return new VirtualFile[0];
    }

    @Override
    public @NotNull OutputStream getOutputStream(Object requestor, long newModificationStamp, long newTimeStamp) throws IOException {
        String newContent = ((FileDocumentManagerImpl) requestor).getDocument(this).getText();

        boolean exitCode = MessageDialogBuilder.okCancel("Apply this resource?", "").guessWindowAndAsk();
        if (exitCode) {
            saveContent(newContent);
        }
        OutputStream outputStream = new ByteArrayOutputStream();
        outputStream.write(newContent.getBytes());
        return outputStream;
    }

    private void saveContent(String newContent) {
        ProgressManager.getInstance().run(new Task.Backgroundable(null, "Applying " + name, false) {

            String result = "";

            @Override
            public void onSuccess() {
                NocalhostNotifier.getInstance(project).notifySuccess(name + " applied", result);
            }

            @Override
            public void onThrowable(@NotNull Throwable e) {
                LOG.error("error occurred while apply config file", e);
                NocalhostNotifier.getInstance(project).notifyError("Nocalhost apply error", "Error occurred while applying file", e.getMessage());
            }

            @SneakyThrows
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                File tempFile = File.createTempFile(resourceName, ".yaml");
                FileOutputStream outputStream = new FileOutputStream(tempFile);
                IOUtils.write(newContent, outputStream, StandardCharsets.UTF_8);
                final NhctlCommand nhctlCommand = ServiceManager.getService(NhctlCommand.class);
                NhctlApplyOptions nhctlApplyOptions = new NhctlApplyOptions(devSpace);
                nhctlApplyOptions.setFile(tempFile.getAbsolutePath());
                result = nhctlCommand.apply(appName, nhctlApplyOptions);
            }
        });
    }

    @Override
    public byte @NotNull [] contentsToByteArray() throws IOException {
        return content.getBytes();
    }

    @Override
    public long getModificationStamp() {
        return new Date().getTime();
    }

    @Override
    public long getTimeStamp() {
        return new Date().getTime();
    }

    @Override
    public long getLength() {
        return content.getBytes().length;
    }

    @Override
    public void refresh(boolean asynchronous, boolean recursive, @Nullable Runnable postRunnable) {

    }

    @Override
    public InputStream getInputStream() throws IOException {
        return new ByteArrayInputStream(content.getBytes());
    }
}
