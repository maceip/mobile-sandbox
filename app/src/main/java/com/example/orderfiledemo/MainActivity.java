package com.example.orderfiledemo;

import android.app.AlertDialog;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.system.ErrnoException;
import android.system.Os;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;

import androidx.annotation.Keep;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.example.orderfiledemo.databinding.ActivityMainBinding;
import com.google.androidgamesdk.GameActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Keep
public final class MainActivity extends GameActivity {

    private static final String[] SANDBOX_DIRS = {
            "workspace",
            "tmp",
            "state",
            "site-packages",
            "bin",
            "dev",
            "home"
    };
    private static final String[] SANDBOX_DEV_FILES = {
            "null",
            "zero",
            "random",
            "urandom",
            "tty",
            "stdin",
            "stdout",
            "stderr"
    };

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService runtimeExecutor = Executors.newSingleThreadExecutor();
    private final List<File> workspaceEntries = new ArrayList<>();

    private ActivityMainBinding binding;
    private SandboxPaths sandboxPaths;
    private File currentFile;
    private ArrayAdapter<String> workspaceAdapter;

    private static final class SandboxPaths {
        final File pythonHome;
        final File sandboxRoot;
        final File workspaceDir;
        final File tempDir;
        final File stateDir;

        SandboxPaths(File pythonHome, File sandboxRoot, File workspaceDir, File tempDir, File stateDir) {
            this.pythonHome = pythonHome;
            this.sandboxRoot = sandboxRoot;
            this.workspaceDir = workspaceDir;
            this.tempDir = tempDir;
            this.stateDir = stateDir;
        }
    }

    private interface RelativePathAction {
        void run(String relativePath);
    }

    static {
        System.loadLibrary("c++_shared");
        System.loadLibrary("orderfiledemo");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        hideSystemUi();
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        String nativeStatus;
        try {
            sandboxPaths = prepareSandbox();
            currentFile = ensureWorkspaceScript(sandboxPaths.workspaceDir);
            Os.setenv("TMPDIR", sandboxPaths.tempDir.getAbsolutePath(), false);
            runWorkload(
                    sandboxPaths.pythonHome.getAbsolutePath(),
                    sandboxPaths.sandboxRoot.getAbsolutePath(),
                    sandboxPaths.tempDir.getAbsolutePath());
            nativeStatus = nativeSummary();
            binding.storageSummary.setText(
                    getString(
                            R.string.storage_summary,
                            getInternalStoragePath(),
                            getCacheStoragePath(),
                            sandboxPaths.sandboxRoot.getAbsolutePath(),
                            sandboxPaths.workspaceDir.getAbsolutePath()));
        } catch (ErrnoException | RuntimeException | IOException e) {
            nativeStatus = "python embedded failed: " + e.getMessage();
            binding.storageSummary.setText(
                    getString(
                            R.string.storage_summary,
                            getInternalStoragePath(),
                            getCacheStoragePath(),
                            new File(getFilesDir(), "inceptionsandbox").getAbsolutePath(),
                            new File(new File(getFilesDir(), "inceptionsandbox"), "workspace")
                                    .getAbsolutePath()));
        }

        binding.nativeSummary.setText(nativeStatus);
        binding.consoleOutput.setText(readTerminalOutput("last_run"));
        binding.statusLine.setText(getString(R.string.status_ready));
        setupWorkspaceUi();
        refreshWorkspaceList(currentFile);
    }

    @Override
    protected void onDestroy() {
        runtimeExecutor.shutdownNow();
        super.onDestroy();
    }

    private void setupWorkspaceUi() {
        workspaceAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_list_item_activated_1,
                new ArrayList<>());
        binding.workspaceList.setAdapter(workspaceAdapter);
        binding.workspaceList.setOnItemClickListener((parent, view, position, id) -> {
            File entry = workspaceEntries.get(position);
            if (entry.isDirectory()) {
                binding.commandInput.setText("ls " + relativeWorkspacePath(entry));
                binding.statusLine.setText("Selected directory " + relativeWorkspacePath(entry));
                return;
            }
            try {
                saveCurrentFile(false);
            } catch (IOException e) {
                binding.consoleOutput.setText("Save failed: " + e.getMessage());
                return;
            }
            openWorkspaceFile(entry);
        });

        binding.newFileButton.setOnClickListener(view ->
                promptForRelativePath("Create file", "notes/agent.py", relativePath -> {
                    try {
                        File target = resolveWorkspaceTarget(relativePath);
                        ensureFile(target);
                        if (target.length() == 0 && target.getName().endsWith(".py")) {
                            writeText(target, "# " + relativeWorkspacePath(target) + "\n");
                        }
                        openWorkspaceFile(target);
                        refreshWorkspaceList(target);
                        binding.statusLine.setText("Created " + relativeWorkspacePath(target));
                    } catch (IOException e) {
                        binding.consoleOutput.setText("Create file failed: " + e.getMessage());
                    }
                }));

        binding.newDirButton.setOnClickListener(view ->
                promptForRelativePath("Create directory", "notes/archive", relativePath -> {
                    try {
                        File target = resolveWorkspaceTarget(relativePath);
                        ensureDir(target);
                        refreshWorkspaceList(currentFile);
                        binding.statusLine.setText("Created directory " + relativeWorkspacePath(target));
                    } catch (IOException e) {
                        binding.consoleOutput.setText("Create directory failed: " + e.getMessage());
                    }
                }));

        binding.saveButton.setOnClickListener(view -> {
            try {
                saveCurrentFile(true);
            } catch (IOException e) {
                binding.consoleOutput.setText("Save failed: " + e.getMessage());
            }
        });

        binding.runButton.setOnClickListener(view -> runCurrentScript());
        binding.commandButton.setOnClickListener(view -> runCommandLine());
        binding.clearButton.setOnClickListener(view -> binding.consoleOutput.setText(""));
        binding.commandInput.setOnEditorActionListener((view, actionId, event) -> {
            if (event == null || event.getAction() == KeyEvent.ACTION_DOWN) {
                runCommandLine();
                return true;
            }
            return false;
        });
    }

    private void openWorkspaceFile(File file) {
        currentFile = file;
        binding.currentFileLabel.setText(relativeWorkspacePath(file));
        binding.scriptEditor.setText(loadText(file));
        int selectedIndex = workspaceEntries.indexOf(file);
        if (selectedIndex >= 0) {
            binding.workspaceList.setItemChecked(selectedIndex, true);
        }
    }

    private void refreshWorkspaceList(File preferredSelection) {
        if (sandboxPaths == null) {
            return;
        }
        workspaceEntries.clear();
        collectWorkspaceEntries(sandboxPaths.workspaceDir, workspaceEntries);
        Collections.sort(workspaceEntries, Comparator
                .comparing(File::isFile)
                .thenComparing(this::relativeWorkspacePath));

        List<String> labels = new ArrayList<>();
        for (File entry : workspaceEntries) {
            String label = relativeWorkspacePath(entry);
            labels.add(entry.isDirectory() ? label + "/" : label);
        }
        workspaceAdapter.clear();
        workspaceAdapter.addAll(labels);
        workspaceAdapter.notifyDataSetChanged();

        if (preferredSelection != null && preferredSelection.exists()) {
            openWorkspaceFile(preferredSelection);
        } else if (!workspaceEntries.isEmpty()) {
            for (File entry : workspaceEntries) {
                if (entry.isFile()) {
                    openWorkspaceFile(entry);
                    break;
                }
            }
        } else {
            binding.currentFileLabel.setText(getString(R.string.workspace_empty));
            binding.scriptEditor.setText("");
        }
    }

    private void collectWorkspaceEntries(File dir, List<File> entries) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        List<File> childList = new ArrayList<>();
        Collections.addAll(childList, children);
        Collections.sort(childList, Comparator
                .comparing(File::isFile)
                .thenComparing(File::getName));
        for (File child : childList) {
            entries.add(child);
            if (child.isDirectory()) {
                collectWorkspaceEntries(child, entries);
            }
        }
    }

    private void saveCurrentFile(boolean announce) throws IOException {
        if (currentFile == null) {
            binding.consoleOutput.setText("No workspace file is available.");
            return;
        }
        writeText(currentFile, binding.scriptEditor.getText().toString());
        refreshWorkspaceList(currentFile);
        if (announce) {
            binding.consoleOutput.setText("Saved " + relativeWorkspacePath(currentFile));
            binding.statusLine.setText("Saved " + relativeWorkspacePath(currentFile));
        }
    }

    private void runCurrentScript() {
        if (sandboxPaths == null || currentFile == null) {
            binding.consoleOutput.setText("Sandbox is not ready.");
            return;
        }
        try {
            saveCurrentFile(false);
        } catch (IOException e) {
            binding.consoleOutput.setText("Run failed: " + e.getMessage());
            return;
        }

        final File script = currentFile;
        setBusy(true, "Running " + relativeWorkspacePath(script));
        runtimeExecutor.execute(() -> {
            final String status = runScript(
                    sandboxPaths.pythonHome.getAbsolutePath(),
                    sandboxPaths.sandboxRoot.getAbsolutePath(),
                    sandboxPaths.tempDir.getAbsolutePath(),
                    script.getAbsolutePath());
            final String output = readTerminalOutput("last_run");
            mainHandler.post(() -> {
                binding.nativeSummary.setText(nativeSummary());
                binding.consoleOutput.setText(status + "\n\n" + output);
                binding.statusLine.setText(status);
                setBusy(false, null);
                refreshWorkspaceList(script);
            });
        });
    }

    private void runCommandLine() {
        if (sandboxPaths == null) {
            binding.consoleOutput.setText("Sandbox is not ready.");
            return;
        }
        final String command = binding.commandInput.getText().toString().trim();
        if (command.isEmpty()) {
            binding.consoleOutput.setText("Enter a command to execute.");
            return;
        }

        try {
            saveCurrentFile(false);
        } catch (IOException e) {
            binding.consoleOutput.setText("Command failed: " + e.getMessage());
            return;
        }

        setBusy(true, "$ " + command);
        runtimeExecutor.execute(() -> {
            final String status = runCommand(
                    sandboxPaths.pythonHome.getAbsolutePath(),
                    sandboxPaths.sandboxRoot.getAbsolutePath(),
                    sandboxPaths.tempDir.getAbsolutePath(),
                    command);
            final String output = readTerminalOutput("last_command");
            mainHandler.post(() -> {
                binding.nativeSummary.setText(nativeSummary());
                binding.consoleOutput.setText("$ " + command + "\n" + status + "\n\n" + output);
                binding.statusLine.setText(status);
                setBusy(false, null);
                refreshWorkspaceList(currentFile);
            });
        });
    }

    private void setBusy(boolean busy, String statusText) {
        binding.newFileButton.setEnabled(!busy);
        binding.newDirButton.setEnabled(!busy);
        binding.saveButton.setEnabled(!busy);
        binding.runButton.setEnabled(!busy);
        binding.commandButton.setEnabled(!busy);
        binding.clearButton.setEnabled(!busy);
        binding.workspaceList.setEnabled(!busy);
        binding.scriptEditor.setEnabled(!busy);
        binding.commandInput.setEnabled(!busy);
        binding.statusLine.setText(statusText != null ? statusText : getString(R.string.status_ready));
    }

    private void promptForRelativePath(String title, String hint, RelativePathAction action) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setPositiveButton("OK", (dialog, which) -> action.run(input.getText().toString()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String getInternalStoragePath() {
        return getFilesDir().getAbsolutePath();
    }

    private String getCacheStoragePath() {
        return getCacheDir().getAbsolutePath();
    }

    private void hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }

        View decorView = getWindow().getDecorView();
        WindowInsetsControllerCompat controller =
                new WindowInsetsControllerCompat(getWindow(), decorView);
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.hide(WindowInsetsCompat.Type.displayCutout());
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    private SandboxPaths prepareSandbox() throws IOException {
        File pythonHome = extractPythonAssets();
        File sandboxRoot = new File(getFilesDir(), "inceptionsandbox");
        ensureDir(sandboxRoot);
        for (String dir : SANDBOX_DIRS) {
            ensureDir(new File(sandboxRoot, dir));
        }

        File devDir = new File(sandboxRoot, "dev");
        for (String name : SANDBOX_DEV_FILES) {
            ensureFile(new File(devDir, name));
        }
        installBundledTools(pythonHome, new File(sandboxRoot, "bin"));

        return new SandboxPaths(
                pythonHome,
                sandboxRoot,
                new File(sandboxRoot, "workspace"),
                new File(sandboxRoot, "tmp"),
                new File(sandboxRoot, "state"));
    }

    private void installBundledTools(File pythonHome, File sandboxBin) throws IOException {
        File bundledBin = new File(pythonHome, "bin");
        if (!bundledBin.exists()) {
            return;
        }
        ensureDir(sandboxBin);
        File[] tools = bundledBin.listFiles();
        if (tools == null) {
            return;
        }
        for (File tool : tools) {
            if (!tool.isFile()) {
                continue;
            }
            File target = new File(sandboxBin, tool.getName());
            copyFile(tool, target);
            if (!target.setExecutable(true, true)) {
                throw new IOException("Failed to mark executable " + target);
            }
        }
    }

    private File ensureWorkspaceScript(File workspaceDir) throws IOException {
        File script = new File(workspaceDir, "main.py");
        if (!script.exists()) {
            writeText(
                    script,
                    "# Cory inceptionsandbox entrypoint\n"
                            + "from pathlib import Path\n"
                            + "\n"
                            + "workspace = Path.cwd()\n"
                            + "print(f\"workspace={workspace}\")\n");
        }
        return script;
    }

    private File extractPythonAssets() throws IOException {
        File pythonHome = new File(getFilesDir(), "python");
        if (pythonHome.exists() && !deleteRecursively(pythonHome)) {
            throw new IOException("Failed to delete " + pythonHome);
        }
        extractAssetDir("python", getFilesDir());

        File cwd = new File(pythonHome, "cwd");
        if (!cwd.exists() && !cwd.mkdirs()) {
            throw new IOException("Failed to create " + cwd);
        }
        return pythonHome;
    }

    private void ensureDir(File dir) throws IOException {
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Failed to create " + dir);
        }
    }

    private void ensureFile(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) {
            ensureDir(parent);
        }
        if (!file.exists() && !file.createNewFile()) {
            throw new IOException("Failed to create " + file);
        }
    }

    private void extractAssetDir(String path, File targetDir) throws IOException {
        String[] names = getAssets().list(path);
        if (names == null) {
            throw new IOException("Failed to list " + path);
        }

        File targetSubdir = new File(targetDir, path);
        if (!targetSubdir.exists() && !targetSubdir.mkdirs()) {
            throw new IOException("Failed to create " + targetSubdir);
        }

        for (String name : names) {
            String subPath = path + "/" + name;
            try (InputStream input = getAssets().open(subPath)) {
                String outputName = name.endsWith("-") ? name.substring(0, name.length() - 1) : name;
                File outputFile = new File(targetSubdir, outputName);
                try (FileOutputStream output = new FileOutputStream(outputFile)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                    }
                }
            } catch (FileNotFoundException assetDirectory) {
                extractAssetDir(subPath, targetDir);
            }
        }
    }

    private File resolveWorkspaceTarget(String relativePath) throws IOException {
        if (sandboxPaths == null) {
            throw new IOException("Sandbox is not ready.");
        }
        String normalized = relativePath.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isEmpty()) {
            throw new IOException("Path must not be empty.");
        }
        File target = new File(sandboxPaths.workspaceDir, normalized);
        String workspaceCanonical = sandboxPaths.workspaceDir.getCanonicalPath();
        String targetCanonical = target.getCanonicalPath();
        if (!targetCanonical.equals(workspaceCanonical)
                && !targetCanonical.startsWith(workspaceCanonical + File.separator)) {
            throw new IOException("Path escapes workspace.");
        }
        return target;
    }

    private String relativeWorkspacePath(File file) {
        if (sandboxPaths == null) {
            return file.getName();
        }
        String root = sandboxPaths.workspaceDir.getAbsolutePath();
        String path = file.getAbsolutePath();
        if (path.equals(root)) {
            return ".";
        }
        if (path.startsWith(root + File.separator)) {
            return path.substring(root.length() + 1);
        }
        return path;
    }

    private String loadText(File file) {
        try {
            return readText(file);
        } catch (IOException e) {
            return "# Failed to load " + file.getName() + ": " + e.getMessage();
        }
    }

    private String readTerminalOutput(String stem) {
        if (sandboxPaths == null) {
            return getString(R.string.terminal_empty);
        }
        File stdout = new File(sandboxPaths.stateDir, stem + ".stdout");
        File stderr = new File(sandboxPaths.stateDir, stem + ".stderr");
        StringBuilder builder = new StringBuilder();
        try {
            if (stdout.exists()) {
                builder.append(readText(stdout));
            }
            if (stderr.exists()) {
                if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '\n') {
                    builder.append('\n');
                }
                builder.append(readText(stderr));
            }
        } catch (IOException e) {
            return "Console read failed: " + e.getMessage();
        }
        return builder.length() == 0 ? getString(R.string.terminal_empty) : builder.toString();
    }

    private String readText(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            StringBuilder builder = new StringBuilder();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                builder.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
            return builder.toString();
        }
    }

    private void copyFile(File source, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null) {
            ensureDir(parent);
        }
        try (InputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    private void writeText(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) {
            ensureDir(parent);
        }
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private boolean deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                if (!deleteRecursively(child)) {
                    return false;
                }
            }
        }
        return file.delete();
    }

    private native void runWorkload(String pythonHome, String sandboxRoot, String tempDir);

    private native String runScript(
            String pythonHome, String sandboxRoot, String tempDir, String scriptPath);

    private native String runCommand(
            String pythonHome, String sandboxRoot, String tempDir, String command);

    private native String nativeSummary();
}
