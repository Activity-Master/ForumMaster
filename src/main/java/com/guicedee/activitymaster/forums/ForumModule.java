package com.guicedee.activitymaster.forums;

import com.google.inject.PrivateModule;
import com.google.inject.Singleton;
import com.guicedee.client.services.lifecycle.IGuiceModule;

public final class ForumModule extends PrivateModule implements IGuiceModule<ForumModule> {
    @Override protected void configure() {
        bind(IForumService.class).to(ForumService.class).in(Singleton.class);
        expose(IForumService.class);
        bind(ForumApi.class).in(Singleton.class);
        expose(ForumApi.class);
    }
}
