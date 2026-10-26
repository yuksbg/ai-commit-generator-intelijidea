package dev.aicommit;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.FormBuilder;
import javax.swing.*;
import java.awt.*;
import java.util.List;

public final class CommitConfigurable implements Configurable {
    private JPanel panel;
    private JBPasswordField apiKey;
    private JCheckBox removeKey;
    private JCheckBox includeUnversioned;
    private JComboBox<String> model;
    private JBTextArea prompt;
    private JSpinner limit;
    private JLabel status;
    private JButton refresh;
    private long generation;

    @Override public String getDisplayName() { return "AI Commit Message"; }
    @Override public JComponent createComponent() {
        apiKey = new JBPasswordField();
        removeKey = new JCheckBox("Remove saved API key");
        includeUnversioned = new JCheckBox("Include selected unversioned files");
        includeUnversioned.setToolTipText("Include checked new files in generation without adding them to Git.");
        model = new JComboBox<>(new String[]{"gpt-5.4-mini", "gpt-6-luna", "gpt-6.1-sol"});
        model.setEditable(true);
        refresh = new JButton("Load models from OpenAI");
        refresh.addActionListener(e -> refreshModels());
        JPanel modelRow = new JPanel(new BorderLayout(8, 0));
        modelRow.add(model, BorderLayout.CENTER); modelRow.add(refresh, BorderLayout.EAST);
        prompt = new JBTextArea(12, 65); prompt.setLineWrap(true); prompt.setWrapStyleWord(true);
        limit = new JSpinner(new SpinnerNumberModel(60000, 1000, 500000, 1000));
        status = new JLabel(" ");
        JButton restore = new JButton("Restore default prompt");
        restore.addActionListener(e -> prompt.setText(CommitSettings.DEFAULT_PROMPT));
        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Provider:", new JLabel("OpenAI"))
            .addLabeledComponent("API key:", apiKey)
            .addComponent(new JLabel("Leave blank to keep the saved key. Stored in the IDE Password Safe."))
            .addComponent(removeKey)
            .addLabeledComponent("Model:", modelRow)
            .addComponent(new JLabel("Choose a Responses-compatible text model, or enter its ID. Model access varies by account."))
            .addComponent(status)
            .addComponent(includeUnversioned)
            .addLabeledComponent("Maximum diff characters:", limit)
            .addLabeledComponent("Custom prompt:", new JBScrollPane(prompt), true)
            .addComponent(new JLabel("Placeholders: {BRANCH_NAME}, {DATE_ISO_8601}. The selected diff is appended automatically."))
            .addComponent(restore)
            .addComponent(new JLabel("Generate sends selected changes to OpenAI and fills the commit editor. API usage is billed by OpenAI."))
            .addComponentFillVertically(new JPanel(), 0).getPanel();
        reset();
        return panel;
    }

    private String selectedModel() { return String.valueOf(model.getEditor().getItem()).strip(); }
    @Override public boolean isModified() {
        if (panel == null) return false;
        var state = CommitSettings.getInstance().getState();
        return apiKey.getPassword().length > 0 || removeKey.isSelected() || !selectedModel().equals(state.model)
            || includeUnversioned.isSelected() != state.includeUnversionedFiles
            || !prompt.getText().equals(state.prompt) || !limit.getValue().equals(state.maxDiffChars);
    }
    @Override public void apply() throws ConfigurationException {
        if (selectedModel().isBlank()) throw new ConfigurationException("Enter a model ID.");
        if (prompt.getText().isBlank()) throw new ConfigurationException("Enter a prompt.");
        String key = new String(apiKey.getPassword()).strip();
        if (removeKey.isSelected() && !key.isEmpty()) throw new ConfigurationException("Clear the API key field before removing the saved key.");
        if (removeKey.isSelected()) ApiKeyStore.set(""); else if (!key.isEmpty()) ApiKeyStore.set(key);
        var state = new CommitSettings.Values();
        state.model = selectedModel(); state.prompt = prompt.getText(); state.maxDiffChars = (Integer) limit.getValue();
        state.includeUnversionedFiles = includeUnversioned.isSelected();
        CommitSettings.getInstance().loadState(state);
        apiKey.setText(""); removeKey.setSelected(false);
    }
    @Override public void reset() {
        var state = CommitSettings.getInstance().getState();
        apiKey.setText(""); removeKey.setSelected(false); model.setSelectedItem(state.model);
        prompt.setText(state.prompt); limit.setValue(state.maxDiffChars);
        includeUnversioned.setSelected(state.includeUnversionedFiles);
    }
    private void refreshModels() {
        String entered = new String(apiKey.getPassword()).strip();
        if (removeKey.isSelected()) { status.setText("Uncheck Remove saved API key to load models."); return; }
        String selection = selectedModel();
        long current = ++generation;
        refresh.setEnabled(false); status.setText("Loading models…");
        new Task.Backgroundable(null, "Load OpenAI models", true) {
            private List<String> models;
            @Override public void run(ProgressIndicator indicator) {
                try {
                    String key = entered.isEmpty() ? ApiKeyStore.get() : entered;
                    if (key.isBlank()) throw new java.io.IOException("Enter an OpenAI API key first.");
                    models = new OpenAiClient().models(key, indicator::checkCanceled);
                } catch (java.io.IOException e) { throw new RuntimeException(e.getMessage()); }
            }
            @Override public void onSuccess() {
                if (panel == null || current != generation) return;
                model.removeAllItems(); models.forEach(model::addItem); model.setSelectedItem(selection);
                status.setText("Loaded " + models.size() + " models. Select a text-generation model.");
            }
            @Override public void onThrowable(Throwable error) {
                if (panel != null && current == generation) status.setText(error.getMessage());
            }
            @Override public void onCancel() {
                if (panel != null && current == generation) status.setText("Model loading cancelled.");
            }
            @Override public void onFinished() {
                if (panel != null && current == generation) refresh.setEnabled(true);
            }
        }.queue();
    }
    @Override public void disposeUIResources() { generation++; panel = null; }
}
