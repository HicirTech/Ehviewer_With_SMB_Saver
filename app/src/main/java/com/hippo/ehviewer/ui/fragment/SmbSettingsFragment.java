package com.hippo.ehviewer.ui.fragment;

import android.os.Bundle;
import android.text.InputType;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.TwoStatePreference;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.smb.SmbStorage;
import com.hippo.util.IoThreadPoolExecutor;

import java.util.HashMap;
import java.util.Map;

public class SmbSettingsFragment extends PreferenceFragmentCompat implements Preference.OnPreferenceChangeListener {

    @Nullable
    private TwoStatePreference mMasterSwitch;
    @Nullable
    private TwoStatePreference mAutoDownloadSwitch;
    @Nullable
    private EditTextPreference mHost;
    @Nullable
    private EditTextPreference mPort;
    @Nullable
    private EditTextPreference mShareName;
    @Nullable
    private EditTextPreference mSharePath;
    @Nullable
    private EditTextPreference mUsername;
    @Nullable
    private EditTextPreference mPassword;
    @Nullable
    private Preference mTestConnection;

    /**
     * Snapshot of each EditText preference's XML-defined {@code android:summary} taken before
     * we overwrite it with the entered value. When the user clears a field we restore this
     * original hint (e.g. "Example: 192.168.1.10") instead of showing a generic placeholder.
     */
    private final Map<Preference, CharSequence> mHintSummaries = new HashMap<>();

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        addPreferencesFromResource(R.xml.smb_settings);

        mMasterSwitch = findPreference(Settings.KEY_SMB_SAVE_ENABLED);
        mAutoDownloadSwitch = findPreference(Settings.KEY_SMB_AUTO_DOWNLOAD_ENABLED);
        mHost = findPreference(Settings.KEY_SMB_HOST);
        mPort = findPreference(Settings.KEY_SMB_PORT);
        mShareName = findPreference(Settings.KEY_SMB_SHARE_NAME);
        mSharePath = findPreference(Settings.KEY_SMB_SHARE_PATH);
        mUsername = findPreference(Settings.KEY_SMB_USERNAME);
        mPassword = findPreference(Settings.KEY_SMB_PASSWORD);
        mTestConnection = findPreference("smb_test_connection");

        if (mPassword != null) {
            mPassword.setOnBindEditTextListener(editText -> editText.setInputType(
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));
        }

        if (mMasterSwitch != null) {
            mMasterSwitch.setOnPreferenceChangeListener(this);
        }
        if (mAutoDownloadSwitch != null) {
            mAutoDownloadSwitch.setOnPreferenceChangeListener(this);
        }
        if (mHost != null) {
            cacheHint(mHost, null);
            mHost.setOnPreferenceChangeListener(this);
            updateTextSummary(mHost, Settings.getSmbHost());
        }
        if (mPort != null) {
            cacheHint(mPort, null);
            mPort.setOnPreferenceChangeListener(this);
            updateTextSummary(mPort, Settings.getSmbPort());
        }
        if (mShareName != null) {
            cacheHint(mShareName, null);
            mShareName.setOnPreferenceChangeListener(this);
            updateTextSummary(mShareName, Settings.getSmbShareName());
        }
        if (mSharePath != null) {
            cacheHint(mSharePath, null);
            mSharePath.setOnPreferenceChangeListener(this);
            updateTextSummary(mSharePath, Settings.getSmbSharePath());
        }
        if (mUsername != null) {
            // Username has no XML summary — fall back to a generic "tap to set" hint.
            cacheHint(mUsername, getString(R.string.settings_smb_field_unset));
            mUsername.setOnPreferenceChangeListener(this);
            updateTextSummary(mUsername, Settings.getSmbUsername());
        }
        if (mPassword != null) {
            cacheHint(mPassword, getString(R.string.settings_smb_field_unset_password));
            mPassword.setOnPreferenceChangeListener(this);
            updatePasswordSummary(Settings.getSmbPassword());
        }

        if (mTestConnection != null) {
            mTestConnection.setOnPreferenceClickListener(preference -> {
                testConnection();
                return true;
            });
        }

        applyMasterState(Settings.getSmbSaveEnabled());
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        final String value = newValue == null ? "" : String.valueOf(newValue);
        if (preference == mMasterSwitch) {
            boolean enabled = Boolean.TRUE.equals(newValue);
            // When the master switch turns off, also force auto-download off (writing to
            // SharedPreferences directly so the saved state survives a restart) and grey
            // out the dependent UI immediately.
            if (!enabled && mAutoDownloadSwitch != null && mAutoDownloadSwitch.isChecked()) {
                mAutoDownloadSwitch.setChecked(false);
            }
            applyMasterState(enabled);
            return true;
        }
        if (preference == mAutoDownloadSwitch) {
            return true;
        }
        if (preference == mHost) {
            updateTextSummary(mHost, value);
        } else if (preference == mPort) {
            updateTextSummary(mPort, value);
        } else if (preference == mShareName) {
            updateTextSummary(mShareName, value);
        } else if (preference == mSharePath) {
            updateTextSummary(mSharePath, value);
        } else if (preference == mUsername) {
            updateTextSummary(mUsername, value);
        } else if (preference == mPassword) {
            updatePasswordSummary(value);
        }
        return true;
    }

    private void applyMasterState(boolean enabled) {
        if (mAutoDownloadSwitch != null) mAutoDownloadSwitch.setEnabled(enabled);
        if (mHost != null) mHost.setEnabled(enabled);
        if (mPort != null) mPort.setEnabled(enabled);
        if (mShareName != null) mShareName.setEnabled(enabled);
        if (mSharePath != null) mSharePath.setEnabled(enabled);
        if (mUsername != null) mUsername.setEnabled(enabled);
        if (mPassword != null) mPassword.setEnabled(enabled);
        if (mTestConnection != null) mTestConnection.setEnabled(enabled);
    }

    private void updateTextSummary(@Nullable EditTextPreference preference, @Nullable String value) {
        if (preference == null) {
            return;
        }
        if (value == null || value.trim().isEmpty()) {
            // Empty — restore the original XML-defined hint (e.g. "Example: 192.168.1.10",
            // "Default: 445", "Path on share like: /ehviewer/") so the user sees the actual
            // help text instead of a generic placeholder.
            preference.setSummary(mHintSummaries.get(preference));
        } else {
            preference.setSummary(value);
        }
    }

    private void updatePasswordSummary(@Nullable String password) {
        if (mPassword == null) {
            return;
        }
        if (password == null || password.isEmpty()) {
            mPassword.setSummary(mHintSummaries.get(mPassword));
        } else {
            mPassword.setSummary("******");
        }
    }

    /**
     * Snapshot the current {@code android:summary} so future "empty value" rebinds can restore
     * it. If the preference has no XML summary (e.g. username/password), use the supplied
     * generic fallback. Stored once per preference; subsequent calls are no-ops.
     */
    private void cacheHint(@NonNull Preference pref, @Nullable CharSequence fallback) {
        if (mHintSummaries.containsKey(pref)) {
            return;
        }
        CharSequence current = pref.getSummary();
        if (current == null || current.length() == 0) {
            current = fallback;
        }
        mHintSummaries.put(pref, current);
    }

    private void testConnection() {
        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            try {
                SmbStorage.testConnection();
                getActivity().runOnUiThread(() ->
                    Toast.makeText(getContext(), R.string.settings_smb_test_success, Toast.LENGTH_SHORT).show()
                );
            } catch (Exception e) {
                getActivity().runOnUiThread(() ->
                    Toast.makeText(getContext(),
                        getString(R.string.settings_smb_test_failed, e.getMessage()),
                        Toast.LENGTH_LONG).show()
                );
            }
        });
    }
}

