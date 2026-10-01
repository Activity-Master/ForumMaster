package com.guicedee.activitymaster.forums.test;

import com.google.inject.Key;
import com.google.inject.name.Names;
import com.guicedee.activitymaster.forums.ForumIdentity;
import com.guicedee.activitymaster.forums.ForumApi;
import com.guicedee.activitymaster.forums.ForumModels.Create;
import com.guicedee.activitymaster.forums.ForumModels.Forum;
import com.guicedee.activitymaster.forums.ForumModels.PublishPost;
import com.guicedee.activitymaster.forums.ForumModels.UpdateSubscribers;
import com.guicedee.activitymaster.forums.ForumSystem;
import com.guicedee.activitymaster.forums.IForumService;
import com.guicedee.activitymaster.fsdm.client.services.IEnterpriseService;
import com.guicedee.activitymaster.fsdm.client.services.IInvolvedPartyService;
import com.guicedee.activitymaster.fsdm.client.services.SessionUtils;
import com.guicedee.activitymaster.fsdm.client.services.administration.ActivityMasterConfiguration;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.enterprise.IEnterprise;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.party.IInvolvedParty;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.systems.ISystems;
import com.guicedee.activitymaster.fsdm.transactions.ActivityScope;
import com.guicedee.activitymaster.notifications.INotificationService;
import com.guicedee.activitymaster.notifications.NotificationIdentity;
import com.guicedee.activitymaster.notifications.NotificationSystem;
import com.guicedee.activitymaster.notifications.channels.NotificationDispatcher;
import com.guicedee.client.IGuiceContext;
import com.guicedee.client.utils.Pair;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.tuples.Tuple4;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import org.hibernate.reactive.mutiny.Mutiny;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ForumStorageTest {
    private static final String ENTERPRISE = "ForumTest";
    private IForumService service;
    private UUID enterpriseId;
    private UUID owner;
    private UUID first;
    private UUID second;
    private UUID organization;
    private UUID token;

    @BeforeAll void setup() throws Exception {
        var database = PostgreSQLTestDBModule.DATABASE;
        System.setProperty("ENVIRONMENT", "test");
        System.setProperty("FSDM_DBSERVER", "127.0.0.1");
        System.setProperty("FSDM_DBPORT", database.getFirstMappedPort().toString());
        System.setProperty("FSDM_DBNAME", database.getDatabaseName());
        System.setProperty("FSDM_USER", database.getUsername());
        System.setProperty("FSDM_PASSWORD", database.getPassword());
        System.setProperty("FSDM_SSL_MODE", "disable");
        ActivityMasterConfiguration.get().setApplicationEnterpriseName(ENTERPRISE);
        IGuiceContext.instance();
        Mutiny.SessionFactory factory = IGuiceContext.get(
                Key.get(Mutiny.SessionFactory.class, Names.named("ActivityMaster-Test")));
        service = IGuiceContext.get(IForumService.class);
        IEnterpriseService<?> enterprises = IGuiceContext.get(IEnterpriseService.class);
        factory.withStatelessSession(session -> enterprises.startNewEnterprise(session, ENTERPRISE, "admin", "adminadmin!@"))
                .await().atMost(Duration.ofMinutes(5));
        factory.withStatelessTransaction(session -> enterprises.getEnterprise(session, ENTERPRISE)
                .chain(enterprise -> enterprises.loadUpdates(session, enterprise)))
                .await().atMost(Duration.ofMinutes(5));
        run(c -> {
            enterpriseId = c.getItem2().getId();
            token = c.getItem4()[0];
            IInvolvedPartyService<?> parties = IGuiceContext.get(IInvolvedPartyService.class);
            return parties.createIdentificationType(c.getItem1(), c.getItem3(), "ForumTestId", "Forum test party", token)
                    .chain(() -> party(parties, c, true)).invoke(p -> owner = p.getId())
                    .chain(() -> party(parties, c, true)).invoke(p -> first = p.getId())
                    .chain(() -> party(parties, c, true)).invoke(p -> second = p.getId())
                    .chain(() -> party(parties, c, false)).invoke(p -> organization = p.getId())
                    .replaceWithVoid();
        });
        UUID notificationSystem = SessionUtils.withActivityMaster(ENTERPRISE, NotificationSystem.NAME,
                c -> Uni.createFrom().item(c.getItem3().getId())).await().atMost(Duration.ofMinutes(2));
        var fixture = new NotificationScopedFixture(database, notificationSystem, enterpriseId, token);
        fixture.install();
        fixture.grant(first, INotificationService.PUBLISH_BEHAVIOR);
        fixture.actorRead(first);
        fixture.actorRead(owner);
        fixture.actorRead(second);
    }

    private static Uni<IInvolvedParty<?, ?>> party(IInvolvedPartyService<?> parties,
            Tuple4<Mutiny.StatelessSession, IEnterprise<?, ?>, ISystems<?, ?>, UUID[]> c, boolean organic) {
        return parties.create(c.getItem1(), c.getItem3(),
                new Pair<>("ForumTestId", UUID.randomUUID().toString()), organic, c.getItem4()[0]);
    }

    private <T> T run(Function<Tuple4<Mutiny.StatelessSession, IEnterprise<?, ?>, ISystems<?, ?>, UUID[]>, Uni<T>> work) {
        return SessionUtils.withActivityMaster(ENTERPRISE, ForumSystem.NAME, work)
                .await().atMost(Duration.ofMinutes(2));
    }

    private ForumIdentity identity(UUID party) {
        return new ForumIdentity(party, enterpriseId,
                new ActivityScope.Context(ActivityScope.Realm.WORK, enterpriseId), token);
    }

    @Test void subscriberChangesControlAccessAndPostAudience() {
        Forum created = run(c -> service.create(c.getItem1(), c.getItem3(), identity(owner),
                new Create(ActivityScope.Realm.WORK, enterpriseId, "Harvest planning", List.of(first))));
        assertEquals(List.of(owner, first).stream().sorted().toList(),
                created.subscribers().stream().sorted().toList());
        assertEquals("Harvest planning", run(c -> service.find(c.getItem1(), c.getItem3(), identity(first), created.id())).title());

        assertThrows(BadRequestException.class, () -> run(c -> service.addSubscriber(
                c.getItem1(), c.getItem3(), identity(owner), created.id(), organization)));
        assertThrows(NotFoundException.class, () -> run(c -> service.addSubscriber(
                c.getItem1(), c.getItem3(), identity(first), created.id(), second)));

        Forum changed = run(c -> service.addSubscriber(c.getItem1(), c.getItem3(), identity(owner), created.id(), second));
        assertTrue(changed.subscribers().contains(second));
        assertTrue(run(c -> service.posts(c.getItem1(), c.getItem3(), identity(second), created.id(), 0, 10))
                .items().isEmpty());

        changed = run(c -> service.removeSubscriber(c.getItem1(), c.getItem3(), identity(owner), created.id(), first));
        assertFalse(changed.subscribers().contains(first));
        assertThrows(NotFoundException.class, () -> run(c -> service.find(c.getItem1(), c.getItem3(), identity(first), created.id())));

        changed = run(c -> service.updateSubscribers(c.getItem1(), c.getItem3(), identity(owner), created.id(),
                new UpdateSubscribers(List.of(owner, first))));
        assertTrue(changed.subscribers().contains(first));
        assertFalse(changed.subscribers().contains(second));
        assertThrows(BadRequestException.class, () -> run(c -> service.updateSubscribers(
                c.getItem1(), c.getItem3(), identity(owner), created.id(), new UpdateSubscribers(List.of(first)))));

        var post = run(c -> service.post(c.getItem1(), c.getItem3(), identity(first), created.id(),
                new PublishPost("Seed the north field.")));
        assertEquals(first, post.posterId());
        assertEquals("Seed the north field.", run(c -> service.posts(
                c.getItem1(), c.getItem3(), identity(owner), created.id(), 0, 10)).items().getFirst().text());
        assertThrows(NotFoundException.class, () -> run(c -> service.posts(
                c.getItem1(), c.getItem3(), identity(second), created.id(), 0, 10)));
        assertThrows(BadRequestException.class, () -> run(c -> service.leave(
                c.getItem1(), c.getItem3(), identity(owner), created.id())));
        run(c -> service.leave(c.getItem1(), c.getItem3(), identity(first), created.id()));
        assertThrows(NotFoundException.class, () -> run(c -> service.find(
                c.getItem1(), c.getItem3(), identity(first), created.id())));
    }

    @Test void postingPublishesToCurrentSubscribers() {
        Forum forum = run(c -> service.create(c.getItem1(), c.getItem3(), identity(owner),
                new Create(ActivityScope.Realm.WORK, enterpriseId, "Water allocation", List.of(first))));
        INotificationService notificationService = IGuiceContext.get(INotificationService.class);
        NotificationDispatcher dispatcher = IGuiceContext.get(NotificationDispatcher.class);
        ForumApi forumApi = new ForumApi(() -> Uni.createFrom().item(identity(first)), service,
                notificationService, dispatcher, () -> Uni.createFrom().item(new NotificationIdentity(first, enterpriseId,
                        new ActivityScope.Context(ActivityScope.Realm.WORK, enterpriseId), token)));
        run(c -> service.addSubscriber(c.getItem1(), c.getItem3(), identity(owner), forum.id(), second));
        var post = forumApi.post(ENTERPRISE, forum.id(), new PublishPost("Irrigate on Thursday."))
                .await().atMost(Duration.ofMinutes(2));
        assertEquals(first, post.posterId());
        run(c -> service.removeSubscriber(c.getItem1(), c.getItem3(), identity(owner), forum.id(), second));
        forumApi.post(ENTERPRISE, forum.id(), new PublishPost("Irrigate on Friday."))
                .await().atMost(Duration.ofMinutes(2));
        var inbox = SessionUtils.withActivityMaster(ENTERPRISE, NotificationSystem.NAME,
                c -> notificationService.list(c.getItem1(), c.getItem3(),
                        new NotificationIdentity(owner, enterpriseId,
                                new ActivityScope.Context(ActivityScope.Realm.WORK, enterpriseId), token),
                        null, "forum.post", 0, 10)).await().atMost(Duration.ofMinutes(2));
        assertEquals(2, inbox.items().stream().filter(item -> item.subject().equals("Water allocation")).count());
        var formerSubscriberInbox = SessionUtils.withActivityMaster(ENTERPRISE, NotificationSystem.NAME,
                c -> notificationService.list(c.getItem1(), c.getItem3(),
                        new NotificationIdentity(second, enterpriseId,
                                new ActivityScope.Context(ActivityScope.Realm.WORK, enterpriseId), token),
                        null, "forum.post", 0, 10)).await().atMost(Duration.ofMinutes(2));
        assertEquals(1, formerSubscriberInbox.items().stream()
                .filter(item -> item.subject().equals("Water allocation")).count());
    }

    @Test void deniedNotificationPublicationRollsBackThePost() {
        Forum forum = run(c -> service.create(c.getItem1(), c.getItem3(), identity(owner),
                new Create(ActivityScope.Realm.WORK, enterpriseId, "Atomic publication", List.of(first))));
        ForumApi denied = new ForumApi(() -> Uni.createFrom().item(identity(owner)), service,
                IGuiceContext.get(INotificationService.class), IGuiceContext.get(NotificationDispatcher.class),
                () -> Uni.createFrom().item(new NotificationIdentity(owner, enterpriseId,
                        new ActivityScope.Context(ActivityScope.Realm.WORK, enterpriseId), token)));
        // Owner has row read access but has no notifications.publish behaviour grant.
        assertThrows(SecurityException.class, () -> denied.post(ENTERPRISE, forum.id(),
                new PublishPost("Must roll back.")).await().atMost(Duration.ofMinutes(2)));
        assertTrue(run(c -> service.posts(c.getItem1(), c.getItem3(), identity(owner), forum.id(), 0, 10))
                .items().isEmpty());

        ForumApi allowed = new ForumApi(() -> Uni.createFrom().item(identity(first)), service,
                IGuiceContext.get(INotificationService.class), IGuiceContext.get(NotificationDispatcher.class),
                () -> Uni.createFrom().item(new NotificationIdentity(first, enterpriseId,
                        new ActivityScope.Context(ActivityScope.Realm.WORK, enterpriseId), token)));
        allowed.post(ENTERPRISE, forum.id(), new PublishPost("Saved together."))
                .await().atMost(Duration.ofMinutes(2));
        assertEquals(1, run(c -> service.posts(c.getItem1(), c.getItem3(), identity(owner), forum.id(), 0, 10))
                .items().size());
    }
}
