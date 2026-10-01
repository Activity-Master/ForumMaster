package com.guicedee.activitymaster.forums;

import com.google.inject.Inject;
import com.guicedee.activitymaster.forums.ForumModels.*;
import com.guicedee.activitymaster.fsdm.client.services.SessionUtils;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.systems.ISystems;
import com.guicedee.activitymaster.fsdm.plugins.PluginModels;
import com.guicedee.activitymaster.fsdm.plugins.PluginService;
import com.guicedee.activitymaster.notifications.INotificationService;
import com.guicedee.activitymaster.notifications.NotificationSystem;
import com.guicedee.activitymaster.notifications.NotificationModels.Published;
import com.guicedee.activitymaster.notifications.channels.NotificationDispatch;
import com.guicedee.activitymaster.notifications.channels.NotificationDispatcher;
import com.guicedee.activitymaster.notifications.NotificationIdentity;
import com.guicedee.activitymaster.notifications.NotificationIdentityProvider;
import com.guicedee.activitymaster.notifications.NotificationModels.Channel;
import com.guicedee.activitymaster.notifications.NotificationModels.Publish;
import com.guicedee.activitymaster.notifications.NotificationModels.Severity;
import io.smallrye.mutiny.Uni;
import org.hibernate.reactive.mutiny.Mutiny;
import java.util.UUID;
import java.util.List;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import static com.guicedee.activitymaster.fsdm.client.services.IActivityMasterService.getISystem;

/** Captures trusted host identity and keeps it bound to the entire FSDM operation. */
public final class ForumApi {
    private final ForumIdentityProvider identities;
    private final IForumService service;
    private final INotificationService notifications;
    private final NotificationDispatcher dispatcher;
    private final NotificationIdentityProvider notificationIdentities;
    @Inject private PluginService plugins;
    private PluginService plugins() { return plugins == null ? com.guicedee.client.IGuiceContext.get(PluginService.class) : plugins; }
    private record CommittedPost(Post post, NotificationIdentity identity, Publish request, Published published) { }
    private record PostingNotificationIdentity(NotificationIdentity identity, Throwable failure) { }

    @Inject public ForumApi(ForumIdentityProvider identities, IForumService service,
                            INotificationService notifications, NotificationDispatcher dispatcher,
                            NotificationIdentityProvider notificationIdentities) {
        this.identities = identities;
        this.service = service;
        this.notifications = notifications;
        this.dispatcher = dispatcher;
        this.notificationIdentities = notificationIdentities;
    }

    @FunctionalInterface private interface Work<T> {
        Uni<T> run(Mutiny.StatelessSession session, ISystems<?, ?> system, ForumIdentity identity);
    }

    private <T> Uni<T> execute(String enterprise, String operation, Work<T> work) {
        if (enterprise == null || enterprise.isBlank())
            return Uni.createFrom().failure(new IllegalArgumentException("Enterprise required"));
        return Uni.createFrom().deferred(identities::current)
                .onItem().ifNull().failWith(() -> new SecurityException("Authenticated forum identity required"))
                .chain(identity -> execute(enterprise, identity, operation, work));
    }

    private <T> Uni<T> execute(String enterprise, ForumIdentity identity, String operation, Work<T> work) {
        return SessionUtils.withActivityMaster(enterprise, ForumSystem.NAME, tuple -> {
            if (!identity.enterpriseId().equals(tuple.getItem2().getId()))
                return Uni.createFrom().failure(new SecurityException("Forum enterprise scope mismatch"));
            return work.run(tuple.getItem1(), tuple.getItem3(), identity)
                    .call(_ -> identity.plugin() == null || operation == null ? Uni.createFrom().voidItem()
                            : plugins().audit(tuple.getItem1(), tuple.getItem3(), new PluginModels.Identity(identity.partyId(),
                                    identity.enterpriseId(), identity.identityToken()), identity.plugin(), operation));
        });
    }

    public Uni<Forum> create(String enterprise, Create request) {
        return execute(enterprise, "forum.create", (session, system, identity) -> service.create(session, system, identity, request));
    }
    public Uni<Forum> find(String enterprise, UUID id) {
        return execute(enterprise, null, (session, system, identity) -> service.find(session, system, identity, id));
    }
    public Uni<Page<Forum>> list(String enterprise, int offset, int limit) {
        return execute(enterprise, null, (session, system, identity) -> service.list(session, system, identity, offset, limit));
    }
    public Uni<Post> post(String enterprise, UUID id, PublishPost request) {
        if (enterprise == null || enterprise.isBlank())
            return Uni.createFrom().failure(new IllegalArgumentException("Enterprise required"));
        // Subscribe to both host providers inside the initial call scope. Notification
        // authority is checked only when the committed post needs an audience.
        Uni<ForumIdentity> forumIdentity = Uni.createFrom().deferred(identities::current)
                .onItem().ifNull().failWith(() -> new SecurityException("Authenticated forum identity required"));
        Uni<PostingNotificationIdentity> noticeIdentity = Uni.createFrom().deferred(notificationIdentities::current)
                .map(identity -> new PostingNotificationIdentity(identity, null))
                .onFailure().recoverWithItem(failure -> new PostingNotificationIdentity(null, failure));
        return Uni.combine().all().unis(forumIdentity, noticeIdentity).asTuple().chain(verified ->
                execute(enterprise, verified.getItem1(), "forum.post", (session, system, identity) -> service.post(session, system, identity, id, request)
                        .chain(post -> service.find(session, system, identity, id)
                                .chain(forum -> {
                                    List<UUID> audience = forum.subscribers().stream()
                                            .filter(party -> !party.equals(post.posterId())).toList();
                                    if (audience.isEmpty())
                                        return Uni.createFrom().item(new CommittedPost(post, null, null, null));
                                    String data = "{\"forumId\":\"" + id + "\",\"postId\":\"" + post.id() + "\"}";
                                    Publish notice = new Publish("forum.post", Severity.INFO, forum.title(), post.text(), data,
                                            audience, List.of(Channel.EVENT_BUS));
                                    PostingNotificationIdentity captured = verified.getItem2();
                                    if (captured.failure() != null) return Uni.createFrom().failure(captured.failure());
                                    return Uni.createFrom().item(captured.identity())
                                            .onItem().ifNull().failWith(() -> new SecurityException("Notification identity required"))
                                            .chain(notificationIdentity -> {
                                                sameActor(identity, notificationIdentity);
                                                return getISystem(session, NotificationSystem.NAME, system.getEnterprise())
                                                        .chain(notificationSystem -> notifications.publish(session,
                                                                notificationSystem, notificationIdentity, notice))
                                                        .map(published -> new CommittedPost(post, notificationIdentity, notice, published));
                                            });
                                }))))
                .invoke(committed -> dispatch(enterprise, committed))
                .map(CommittedPost::post);
    }

    /** Called only once the shared post/notification transaction has committed. */
    private void dispatch(String enterprise, CommittedPost committed) {
        if (committed.published() == null || committed.published().recipients().isEmpty()) return;
        Publish notice = committed.request();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<NotificationDispatch> deliveries = committed.published().recipients().stream()
                .map(recipient -> new NotificationDispatch(committed.identity().enterpriseId(), enterprise,
                        committed.published().id(), recipient, notice.category(), notice.severity(), notice.subject(),
                        notice.body(), notice.data(), now)).toList();
        SessionUtils.fireAndForget(Uni.createFrom().deferred(() -> dispatcher.dispatch(enterprise,
                committed.identity(), notice.channels(), deliveries)), "forum post " + committed.post().id() + " dispatch");
    }

    private static void sameActor(ForumIdentity forum, NotificationIdentity notification) {
        if (!forum.partyId().equals(notification.partyId())
                || !forum.enterpriseId().equals(notification.enterpriseId())
                || !forum.context().equals(notification.context())
                || !forum.identityToken().equals(notification.identityToken())
                || !java.util.Objects.equals(forum.plugin(), notification.plugin()))
            throw new SecurityException("Forum and notification identity mismatch");
    }
    public Uni<Page<Post>> posts(String enterprise, UUID id, int offset, int limit) {
        return execute(enterprise, null, (session, system, identity) -> service.posts(session, system, identity, id, offset, limit));
    }
    public Uni<Forum> addSubscriber(String enterprise, UUID id, UUID partyId) {
        return execute(enterprise, "forum.addSubscriber", (session, system, identity) -> service.addSubscriber(session, system, identity, id, partyId));
    }
    public Uni<Forum> removeSubscriber(String enterprise, UUID id, UUID partyId) {
        return execute(enterprise, "forum.removeSubscriber", (session, system, identity) -> service.removeSubscriber(session, system, identity, id, partyId));
    }
    public Uni<Forum> updateSubscribers(String enterprise, UUID id, UpdateSubscribers request) {
        return execute(enterprise, "forum.updateSubscribers", (session, system, identity) -> service.updateSubscribers(session, system, identity, id, request));
    }
    public Uni<Void> leave(String enterprise, UUID id) {
        return execute(enterprise, "forum.leave", (session, system, identity) -> service.leave(session, system, identity, id));
    }
}
