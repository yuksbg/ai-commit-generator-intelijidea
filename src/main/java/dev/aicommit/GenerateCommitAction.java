package dev.aicommit;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vcs.VcsDataKeys;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.CurrentContentRevision;
import com.intellij.openapi.vcs.FilePath;
import java.util.ArrayList;
import com.intellij.openapi.vcs.impl.PartialChangesUtil;
import com.intellij.openapi.diff.impl.patch.*;
import com.intellij.vcs.commit.CommitWorkflowUi;
import git4idea.repo.GitRepositoryManager;
import java.io.StringWriter;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

public final class GenerateCommitAction extends DumbAwareAction {
    private static final Key<Boolean> RUNNING = Key.create("ai.commit.generating");
    @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
    @Override public void update(AnActionEvent e) {
        Project project = e.getProject();
        CommitWorkflowUi ui = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI);
        e.getPresentation().setEnabledAndVisible(project != null && ui != null);
        if (project != null && Boolean.TRUE.equals(project.getUserData(RUNNING))) e.getPresentation().setEnabled(false);
    }
    @Override public void actionPerformed(AnActionEvent e) {
        Project project = e.getProject();
        CommitWorkflowUi ui = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI);
        if (project == null || ui == null || Boolean.TRUE.equals(project.getUserData(RUNNING))) return;
        var settings = CommitSettings.getInstance().getState();
        List<FilePath> unversioned = List.copyOf(ui.getIncludedUnversionedFiles());
        if (!unversioned.isEmpty() && !settings.includeUnversionedFiles) {
            Messages.showInfoMessage(project, "Enable Include selected unversioned files in Settings | Tools | AI Commit Message, or add these files to Git first.", "AI Commit Message");
            return;
        }
        FileDocumentManager.getInstance().saveAllDocuments();
        List<Change> changes = List.copyOf(ui.getIncludedChanges());
        if (changes.isEmpty() && unversioned.isEmpty()) {
            Messages.showInfoMessage(project, "Select the changes to include in your commit first.", "AI Commit Message"); return;
        }
        // Wrap on the UI thread so excluded hunks do not leak into the generated patch.
        List<Change> selected = new ArrayList<>(PartialChangesUtil.wrapPartialChanges(project, changes));
        for (FilePath file : unversioned) {
            // A missing before revision produces a new-file patch without touching the Git index.
            selected.add(new Change(null, CurrentContentRevision.create(file)));
        }
        var editor = ui.getCommitMessageUi();
        String original = editor.getText();
        String model = settings.model;
        String template = settings.prompt;
        int maxChars = settings.maxDiffChars;
        project.putUserData(RUNNING, true);
        new Task.Backgroundable(project, "Generate commit message with OpenAI", true) {
            private String result;
            private String failure;
            private boolean missingKey;
            @Override public void run(ProgressIndicator indicator) {
                try {
                    String key = ApiKeyStore.get();
                    if (key.isBlank()) { missingKey = true; return; }
                    indicator.setText("Reading selected changes");
                    indicator.checkCanceled();
                    var patches = IdeaTextPatchBuilder.buildPatch(project, selected, java.nio.file.Path.of(java.util.Objects.requireNonNull(project.getBasePath(), "Project has no base directory")), false);
                    if (patches.stream().anyMatch(p -> !(p instanceof TextFilePatch)))
                        throw new java.io.IOException("The selection includes binary changes. Generate for text changes only.");
                    StringWriter writer = new StringWriter();
                    UnifiedDiffWriter.write(project, patches, writer, "\n", null);
                    String diff = writer.toString();
                    if (diff.isBlank()) throw new java.io.IOException("The selected changes contain no text diff.");
                    if (diff.length() > maxChars) throw new java.io.IOException("Selected diff has " + diff.length()
                        + " characters, exceeding the limit of " + maxChars + ". Select fewer changes or raise the limit in settings.");
                    String branches = ReadAction.compute(() -> selected.stream().map(change -> {
                        var revision = change.getAfterRevision() != null ? change.getAfterRevision() : change.getBeforeRevision();
                        var repository = GitRepositoryManager.getInstance(project).getRepositoryForFileQuick(revision.getFile());
                        return repository == null ? "" : repository.getCurrentBranchName();
                    }).filter(name -> name != null && !name.isBlank()).distinct().sorted().collect(Collectors.joining(", ")));
                    indicator.setText("Waiting for " + model);
                    result = new OpenAiClient().generate(key, model,
                        PromptTemplate.render(template, branches, LocalDate.now()), diff, indicator::checkCanceled);
                    indicator.checkCanceled();
                } catch (com.intellij.openapi.progress.ProcessCanceledException cancelled) { throw cancelled; }
                catch (Exception error) { failure = error.getMessage() == null ? "Could not generate the message." : error.getMessage(); }
            }
            @Override public void onSuccess() {
                if (project.isDisposed() || com.intellij.openapi.util.Disposer.isDisposed(ui)) return;
                if (missingKey) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, CommitConfigurable.class); return;
                }
                if (failure != null) { Messages.showErrorDialog(project, failure, "AI Commit Message"); return; }
                if (result == null) return;
                if (!editor.getText().equals(original) || !ui.getIncludedChanges().equals(changes)
                    || !ui.getIncludedUnversionedFiles().equals(unversioned)) {
                    Messages.showInfoMessage(project, "The message or selection changed during generation. Your current draft was preserved.\n\nGenerated draft:\n" + result, "AI Commit Message");
                    return;
                }
                editor.setText(result); editor.focus();
            }
            @Override public void onFinished() { project.putUserData(RUNNING, null); }
        }.queue();
    }
}
