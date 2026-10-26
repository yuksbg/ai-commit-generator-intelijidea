package dev.aicommit;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;

final class ApiKeyStore {
    private static final CredentialAttributes ATTRIBUTES = new CredentialAttributes("AI Commit Message: OpenAI");
    static String get() {
        Credentials credentials = PasswordSafe.getInstance().get(ATTRIBUTES);
        return credentials == null || credentials.getPasswordAsString() == null ? "" : credentials.getPasswordAsString();
    }
    static void set(String key) {
        PasswordSafe.getInstance().set(ATTRIBUTES, key.isBlank() ? null : new Credentials("OpenAI", key));
    }
}
