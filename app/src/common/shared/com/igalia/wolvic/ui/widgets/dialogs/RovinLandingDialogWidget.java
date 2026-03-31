/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.ui.widgets.dialogs;

import android.content.Context;

import androidx.annotation.NonNull;

import com.igalia.wolvic.R;

public class RovinLandingDialogWidget extends PromptDialogWidget {

    public interface Delegate {
        void onPlayRequested();
        void onRecoveryBrowserRequested();
        void onRetryRequested();
    }

    private Delegate mDelegate;
    private boolean mErrorState = false;

    public RovinLandingDialogWidget(Context context) {
        super(context);
        setButtonsDelegate((index, isChecked) -> {
            if (mDelegate == null) {
                return;
            }

            if (mErrorState) {
                if (index == NEGATIVE) {
                    mDelegate.onRecoveryBrowserRequested();
                } else if (index == POSITIVE) {
                    mDelegate.onRetryRequested();
                }
            } else {
                if (index == NEGATIVE) {
                    mDelegate.onPlayRequested();
                }
            }
        });
        applyBrandingChrome();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        applyBrandingChrome();
    }

    public void setDelegate(Delegate delegate) {
        mDelegate = delegate;
    }

    public void bindReady(@NonNull String gameTitle, @NonNull String description) {
        mErrorState = false;
        setTitle(R.string.rovin_landing_title);
        setBody(getContext().getString(R.string.rovin_landing_body, gameTitle));
        setDescription(description);
        setButtons(new int[] { R.string.rovin_landing_play_resume });
    }

    public void bindError(@NonNull String gameTitle, @NonNull String message) {
        mErrorState = true;
        setTitle(R.string.rovin_landing_title);
        setBody(getContext().getString(R.string.rovin_landing_body, gameTitle));
        setDescription(message);
        setButtons(new int[] {
                R.string.rovin_landing_recovery_browser,
                R.string.rovin_landing_retry
        });
    }

    @Override
    public void onDismiss() {
        // Keep the shell flow explicit; dismissing the landing dialog should not auto-navigate.
    }

    private void applyBrandingChrome() {
        setIconVisible(true);
        setIcon(R.drawable.rovin_brand_logo);
        setCheckboxVisible(false);
        setDescriptionVisible(true);
    }
}
