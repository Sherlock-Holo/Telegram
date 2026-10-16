package org.telegram.messenger;

import org.telegram.messenger.regular.BuildConfig;

public class ApplicationLoaderImpl extends ApplicationLoader {
    @Override
    protected String onGetApplicationId() {
        return BuildConfig.APPLICATION_ID;
    }

    @Override
    protected PushListenerController.IPushListenerServiceProvider onCreatePushProvider() {
        // Fork: UnifiedPush (off by default, then behaves like NoPushListenerServiceProvider).
        return UnifiedPushListenerServiceProvider.INSTANCE;
    }
}
