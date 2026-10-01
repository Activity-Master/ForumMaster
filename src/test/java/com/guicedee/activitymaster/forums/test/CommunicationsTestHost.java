package com.guicedee.activitymaster.forums.test;

import com.google.inject.AbstractModule;
import com.guicedee.activitymaster.forums.ForumIdentity;
import com.guicedee.activitymaster.forums.ForumIdentityProvider;
import com.guicedee.activitymaster.conversations.ConversationIdentity;
import com.guicedee.activitymaster.conversations.ConversationIdentityProvider;
import com.guicedee.activitymaster.notifications.NotificationIdentity;
import com.guicedee.activitymaster.notifications.NotificationIdentityProvider;
import com.guicedee.client.IGuiceContext;
import com.guicedee.client.scopes.CallScopeProperties;
import com.guicedee.client.services.lifecycle.IGuiceModule;
import com.guicedee.vertx.web.spi.VertxHttpServerConfigurator;
import com.guicedee.vertx.web.spi.VertxRouterConfigurator;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Loopback test host; credentials refer to server-owned verified identities. */
public final class CommunicationsTestHost extends AbstractModule implements IGuiceModule<CommunicationsTestHost> {
    private static final Map<String, NotificationIdentity> CREDENTIALS = new ConcurrentHashMap<>();
    static volatile HttpServer server;
    static String issue(NotificationIdentity identity) {
        String credential = UUID.randomUUID().toString();
        CREDENTIALS.put(credential, identity);
        return credential;
    }
    @Override protected void configure() {
        bind(ForumIdentityProvider.class).to(ForumProvider.class);
        bind(ConversationIdentityProvider.class).to(ConversationProvider.class);
        bind(NotificationIdentityProvider.class).to(NotificationProvider.class);
    }
    private static NotificationIdentity identity() {
        RoutingContext context = (RoutingContext) IGuiceContext.get(CallScopeProperties.class).getProperties().get("RoutingContext");
        return context == null ? null : context.get("verifiedCommunicationsIdentity");
    }
    public static final class ForumProvider implements ForumIdentityProvider {
        @Override public Uni<ForumIdentity> current() {
            NotificationIdentity i = identity();
            return i == null ? new ForumIdentityProvider.Deny().current()
                    : Uni.createFrom().item(new ForumIdentity(i.partyId(), i.enterpriseId(), i.context(), i.identityToken(), i.plugin()));
        }
    }
    public static final class ConversationProvider implements ConversationIdentityProvider {
        @Override public Uni<ConversationIdentity> current() {
            NotificationIdentity i = identity();
            return i == null ? new ConversationIdentityProvider.Deny().current()
                    : Uni.createFrom().item(new ConversationIdentity(i.partyId(), i.enterpriseId(), i.context(), i.identityToken()));
        }
    }
    public static final class NotificationProvider implements NotificationIdentityProvider {
        @Override public Uni<NotificationIdentity> current() {
            NotificationIdentity i = identity();
            return i == null ? new NotificationIdentityProvider.Deny().current() : Uni.createFrom().item(i);
        }
    }
    public static final class Authentication implements VertxRouterConfigurator<Authentication> {
        @Override public Integer sortOrder() { return Integer.MIN_VALUE + 75; }
        @Override public Router builder(Router router) {
            router.route().handler(context -> {
                String credential = context.request().getHeader("X-Communications-Test-Credential");
                NotificationIdentity i = credential == null ? null : CREDENTIALS.get(credential);
                if (i != null) context.put("verifiedCommunicationsIdentity", i);
                context.next();
            });
            return router;
        }
    }
    public static final class ServerCapture implements VertxHttpServerConfigurator {
        @Override public HttpServer builder(HttpServer builder) { server = builder; return builder; }
    }
}
