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
import com.guicedee.activitymaster.fsdm.client.services.ISecurityTokenService;
import com.guicedee.activitymaster.fsdm.client.services.ISystemsService;
import com.guicedee.activitymaster.fsdm.client.services.IClassificationService;
import com.guicedee.activitymaster.fsdm.client.services.IActiveFlagService;
import com.guicedee.activitymaster.fsdm.client.services.IResourceItemService;
import com.guicedee.activitymaster.fsdm.client.services.IArrangementsService;
import com.guicedee.activitymaster.fsdm.client.services.IEventService;
import com.guicedee.activitymaster.fsdm.client.services.classifications.EnterpriseClassificationDataConcepts;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.base.IWarehouseCoreTable;
import com.guicedee.activitymaster.fsdm.plugins.PluginService;
import com.guicedee.activitymaster.fsdm.plugins.PluginModels;
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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
    private PluginService plugins;
    private UUID administratorCredential;
    private UUID userCredential;

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
        plugins = IGuiceContext.get(PluginService.class);
        run(c -> {
            ISecurityTokenService<?> security = IGuiceContext.get(ISecurityTokenService.class);
            ISystemsService<?> systems = IGuiceContext.get(ISystemsService.class);
            IClassificationService<?> classes = IGuiceContext.get(IClassificationService.class);
            IActiveFlagService<?> activeFlags = IGuiceContext.get(IActiveFlagService.class);
            IInvolvedPartyService<?> parties = IGuiceContext.get(IInvolvedPartyService.class);
            return systems.getActivityMaster(c.getItem1(), c.getItem2()).chain(core ->
                    security.create(c.getItem1(), "User", "PluginTestAdministrator", "Plugin fixture administrator", core)
                            .chain(admin -> security.getAdministratorsFolder(c.getItem1(), core)
                                    .chain(folder -> classes.find(c.getItem1(), "User", core)
                                            .chain(type -> security.link(c.getItem1(), folder, admin, type)))
                                    .invoke(() -> administratorCredential = UUID.fromString(admin.getSecurityToken())))
                            .chain(() -> security.create(c.getItem1(), "User", "PluginTestUser", "Plugin fixture user", core))
                            .chain(user -> {
                                userCredential = UUID.fromString(user.getSecurityToken());
                                return activeFlags.getActiveFlag(c.getItem1(), c.getItem2())
                                        .chain(flag -> parties.find(c.getItem1(), first)
                                                .chain(party -> ((IWarehouseCoreTable<?, ?, ?, ?>) party).createSecurityGrant(c.getItem1(), core,
                                                        c.getItem2(), flag, user, true, true, false, true, token))
                                                .chain(() -> parties.find(c.getItem1(), organization))
                                                .chain(party -> ((IWarehouseCoreTable<?, ?, ?, ?>) party).createSecurityGrant(c.getItem1(), core,
                                                        c.getItem2(), flag, user, false, false, false, true, token))
                                                .chain(() -> ((IWarehouseCoreTable<?, ?, ?, ?>) c.getItem3()).createSecurityGrant(c.getItem1(), core,
                                                        c.getItem2(), flag, user, false, false, false, true, token)))
                                        .replaceWithVoid();
                            }));
        });
        fixture.reader(administratorCredential);
        new NotificationScopedFixture(database, notificationSystem, enterpriseId, administratorCredential).actorRead(first);
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

    private PluginModels.Identity administrator() {
        return new PluginModels.Identity(owner, enterpriseId, administratorCredential);
    }
    private PluginModels.Identity pluginUser() {
        return new PluginModels.Identity(first, enterpriseId, userCredential);
    }
    private PluginModels.Plugin plugin(Set<UUID> requested) {
        return run(c -> plugins.register(c.getItem1(), c.getItem3(), administrator(),
                new PluginModels.Registration("PluginTest-" + UUID.randomUUID(), "Forum extension", "User-scoped forum extension",
                        "1.0.0", null, List.of(), requested)));
    }
    private UUID forumSystemId() { return run(c -> Uni.createFrom().item(c.getItem3().getId())); }
    private ForumIdentity delegated(PluginModels.Invocation invocation) {
        return new ForumIdentity(first, enterpriseId, new ActivityScope.Context(ActivityScope.Realm.WORK, enterpriseId),
                userCredential, invocation);
    }
    private void consent(PluginModels.Invocation invocation, UUID system, boolean enabled) {
        run(c -> plugins.consent(c.getItem1(), c.getItem3(), pluginUser(), invocation, system, enabled));
    }

    private static final class LegacyPlugin extends com.guicedee.activitymaster.fsdm.client.services.administration.MasterDefaultPlugin<LegacyPlugin> {
        private final String name;
        LegacyPlugin(String name) { this.name = name; }
        @Override public String getSystemName() { return name; }
        @Override public String getSystemDescription() { return "Forward migration fixture"; }
        @Override public int totalTasks() { return 0; }
        @Override public Uni<Void> createDefaults(Mutiny.StatelessSession session, IEnterprise<?, ?> enterprise) {
            return Uni.createFrom().voidItem();
        }
        @Override public Uni<ISystems<?, ?>> getSystem(Mutiny.StatelessSession session, String enterprise) {
            IEnterpriseService<?> enterprises = IGuiceContext.get(IEnterpriseService.class);
            ISystemsService<?> systems = IGuiceContext.get(ISystemsService.class);
            return enterprises.getEnterprise(session, enterprise).chain(row -> systems.findSystem(session, row, name));
        }
    }

    @Test void builtInMigrationRetiresTheOldCredentialAndPreservesRegistration() {
        ISystemsService<?> systems = IGuiceContext.get(ISystemsService.class);
        ISecurityTokenService<?> security = IGuiceContext.get(ISecurityTokenService.class);
        LegacyPlugin extension = new LegacyPlugin("LegacyPlugin-" + UUID.randomUUID());
        ISystems<?, ?> original = run(c -> systems.create(c.getItem1(), c.getItem2(), extension.getSystemName(), extension.getSystemDescription())
                .chain(row -> systems.registerNewSystem(c.getItem1(), c.getItem2(), row).replaceWith(row)));
        UUID oldCredential = run(c -> systems.getSecurityIdentityToken(c.getItem1(), original));
        run(c -> systems.getActivityMaster(c.getItem1(), c.getItem2()).chain(core ->
                systems.getSecurityIdentityToken(c.getItem1(), core).chain(bootstrap ->
                        plugins.registerBuiltIn(c.getItem1(), core, bootstrap, extension))));
        IResourceItemService<?> resources = IGuiceContext.get(IResourceItemService.class);
        UUID icon = run(c -> systems.getActivityMaster(c.getItem1(), c.getItem2()).chain(core ->
                resources.createType(c.getItem1(), "BuiltinPluginMedia", "Built-in catalogue media", core, token)
                        .chain(() -> resources.create(c.getItem1(), "BuiltinPluginMedia", UUID.randomUUID().toString(), new byte[]{1}, core, token))
                        .map(row -> row.getId())));
        var metadata = run(c -> plugins.find(c.getItem1(), c.getItem3(), pluginUser(), original.getId()));
        run(c -> plugins.register(c.getItem1(), c.getItem3(), administrator(), new PluginModels.Registration(
                metadata.name(), metadata.title(), metadata.description(), metadata.version(), icon, List.of(icon), metadata.systems())));
        run(c -> systems.getActivityMaster(c.getItem1(), c.getItem2()).chain(core ->
                systems.getSecurityIdentityToken(c.getItem1(), core).chain(bootstrap ->
                        plugins.registerBuiltIn(c.getItem1(), core, bootstrap, extension))));
        var catalog = run(c -> plugins.find(c.getItem1(), c.getItem3(), pluginUser(), original.getId()));
        assertEquals(original.getId(), catalog.id());
        assertEquals(extension.getSystemDescription(), catalog.description());
        assertEquals(icon, catalog.icon());
        assertEquals(List.of(icon), catalog.screenshots());
        UUID credential = run(c -> systems.getSecurityIdentityToken(c.getItem1(), original));
        assertNotEquals(oldCredential, credential);
        run(c -> security.getApplicableSecurityTokenIds(c.getItem1(), c.getItem3(), oldCredential)
                .invoke(tokens -> assertTrue(tokens.isEmpty())).replaceWithVoid());
        run(c -> c.getItem1().createNativeQuery("select count(*) from dbo.systems where systemid=:id", Long.class)
                .setParameter("id", original.getId()).getSingleResult().invoke(count -> assertEquals(1L, count)).replaceWithVoid());
        assertThrows(SecurityException.class, () -> run(c -> systems.getActivityMaster(c.getItem1(), c.getItem2())
                .chain(core -> plugins.registerBuiltIn(c.getItem1(), core, UUID.randomUUID(), extension))));
    }

    @Test void pluginCannotRegisterAsCoreOrConvertItsCredential() {
        ISystemsService<?> systems = IGuiceContext.get(ISystemsService.class);
        LegacyPlugin impersonator = new LegacyPlugin(ISystemsService.ActivityMasterSystemName);
        UUID bootstrap = run(c -> systems.getActivityMaster(c.getItem1(), c.getItem2())
                .chain(core -> systems.getSecurityIdentityToken(c.getItem1(), core)));
        assertThrows(SecurityException.class, () -> run(c -> impersonator.registerSystem(c.getItem1(), c.getItem2())));
        assertThrows(SecurityException.class, () -> run(c -> systems.getActivityMaster(c.getItem1(), c.getItem2())
                .chain(core -> plugins.registerBuiltIn(c.getItem1(), core, bootstrap, impersonator))));
        assertEquals(bootstrap, run(c -> systems.getActivityMaster(c.getItem1(), c.getItem2())
                .chain(core -> systems.getSecurityIdentityToken(c.getItem1(), core))));
        assertFalse(com.guicedee.activitymaster.fsdm.client.services.systems.IMasterSystem.class
                .isAssignableFrom(LegacyPlugin.class));
    }

    @Test void builtInProvisioningCanAddANewModuleToAnExistingEnterprise() {
        ISystemsService<?> systems = IGuiceContext.get(ISystemsService.class);
        LegacyPlugin extension = new LegacyPlugin("NewBuiltin-" + UUID.randomUUID());
        boolean exists = run(c -> systems.doesSystemExist(c.getItem1(), c.getItem2(), extension.getSystemName()));
        assertFalse(exists);
        run(c -> systems.getActivityMaster(c.getItem1(), c.getItem2()).chain(core ->
                systems.getSecurityIdentityToken(c.getItem1(), core).chain(bootstrap ->
                        plugins.registerBuiltIn(c.getItem1(), core, bootstrap, extension))));
        UUID id = run(c -> systems.findSystem(c.getItem1(), c.getItem2(), extension.getSystemName()).map(row -> row.getId()));
        var catalog = run(c -> plugins.find(c.getItem1(), c.getItem3(), pluginUser(), id));
        assertEquals(extension.getSystemDescription(), catalog.description());
        assertThrows(SecurityException.class, () -> run(c -> systems.findSystem(c.getItem1(), c.getItem2(), extension.getSystemName())
                .chain(row -> plugins.checkBuiltIn(c.getItem1(), row, pluginUser(), first))));
    }

    @Test void builtInMailRequiresUserConsentAndStoresPrivateContentAtomically() {
        ISystemsService<?> systems = IGuiceContext.get(ISystemsService.class);
        ISecurityTokenService<?> security = IGuiceContext.get(ISecurityTokenService.class);
        var mail = IGuiceContext.get(com.guicedee.activitymaster.mail.services.IMailFsdmService.class);
        var user = new com.guicedee.activitymaster.mail.MailIdentity(first, enterpriseId, userCredential);
        var message = new com.guicedee.activitymaster.mail.services.dto.MailMessage();
        message.setSubject("Private plugin mail"); message.setTextBody("Only this mailbox user may read this body");
        message.getAttachments().add(new com.guicedee.activitymaster.mail.services.dto.MailAttachment("private.txt", "text/plain", new byte[]{1, 2}));
        var direction = com.guicedee.activitymaster.mail.services.enumerations.MailDirection.Inbound;
        assertThrows(SecurityException.class, () -> mail.ingest(message, "label@example.test", direction, ENTERPRISE)
                .await().atMost(Duration.ofSeconds(30)));
        UUID mailId = run(c -> systems.findSystem(c.getItem1(), c.getItem2(), "Mail Master").map(row -> row.getId()));
        var invocation = new PluginModels.Invocation(mailId, first);
        assertThrows(SecurityException.class, () -> mail.ingest(message, "label@example.test", direction, ENTERPRISE, user)
                .await().atMost(Duration.ofSeconds(30)));
        run(c -> plugins.install(c.getItem1(), c.getItem3(), pluginUser(), mailId, first));
        var catalog = run(c -> plugins.find(c.getItem1(), c.getItem3(), pluginUser(), mailId));
        for (UUID dependency : catalog.systems()) consent(invocation, dependency, true);
        UUID event = (UUID) mail.ingest(message, "label@example.test", direction, ENTERPRISE, user)
                .await().atMost(Duration.ofSeconds(30));
        List<UUID> bodies = run(c -> c.getItem1().createNativeQuery("select resourceitemid from event.eventxresourceitem where eventid=:event", UUID.class)
                .setParameter("event", event).getResultList());
        assertEquals(2, bodies.size());
        UUID other = run(c -> systems.getActivityMaster(c.getItem1(), c.getItem2()).chain(core ->
                security.create(c.getItem1(), "User", "MailOther-" + UUID.randomUUID(), "Other user", core)
                        .map(row -> UUID.fromString(row.getSecurityToken()))));
        for (UUID body : bodies) {
            var resource = IGuiceContext.get(IResourceItemService.class).get();
            resource.setId(body);
            var secured = (IWarehouseCoreTable<?, ?, ?, ?>) resource;
            run(c -> secured.canRead(c.getItem1(), c.getItem3(), userCredential)
                    .invoke(allowed -> assertTrue(allowed)).replaceWithVoid());
            run(c -> secured.canRead(c.getItem1(), c.getItem3(), other)
                    .invoke(allowed -> assertFalse(allowed)).replaceWithVoid());
        }
        run(c -> plugins.remove(c.getItem1(), c.getItem3(), pluginUser(), mailId, first));
        assertThrows(SecurityException.class, () -> mail.ingest(message, "label@example.test", direction, ENTERPRISE, user)
                .await().atMost(Duration.ofSeconds(30)));
    }

    @Test void pluginRegistrationIsTypedAndCannotConvertExistingSystems() {
        UUID target = forumSystemId();
        var plugin = plugin(Set.of(target));
        assertEquals("User-scoped forum extension", run(c -> plugins.find(c.getItem1(), c.getItem3(), pluginUser(), plugin.id())).description());
        assertEquals("1.0.0", run(c -> plugins.find(c.getItem1(), c.getItem3(), pluginUser(), plugin.id())).version());
        run(c -> c.getItem1().createNativeQuery("select a.originalsourcesystemid=a.systemid from arrangement.arrangement a "
                        + "where a.arrangementid=:id", Boolean.class)
                .setParameter("id", plugin.id()).getSingleResult().invoke(correct -> assertTrue(correct)).replaceWithVoid());
        run(c -> c.getItem1().createNativeQuery("select ct.classificationname,parent.securitytokenfriendlyname "
                        + "from dbo.systemxclassification x join security.securitytoken child on child.securitytoken=x.value "
                        + "join classification.classification ct on ct.classificationid=child.securitytokenclassificationid "
                        + "join security.securitytokenxsecuritytoken h on h.childsecuritytokenid=child.securitytokenid "
                        + "join security.securitytoken parent on parent.securitytokenid=h.parentsecuritytokenid where x.systemid=:id", Object[].class)
                .setParameter("id", plugin.id()).getResultList().invoke(rows -> {
                    assertEquals(1, rows.size()); assertArrayEquals(new Object[]{"Plugin", "Plugins"}, rows.getFirst());
                }).replaceWithVoid());
        assertThrows(SecurityException.class, () -> run(c -> plugins.register(c.getItem1(), c.getItem3(), administrator(),
                new PluginModels.Registration(ForumSystem.NAME, "Conflict", "Must remain a system", "1.0", null, List.of(), Set.of()))));
        assertThrows(SecurityException.class, () -> run(c -> plugins.register(c.getItem1(), c.getItem3(), pluginUser(),
                new PluginModels.Registration("Forbidden", "Forbidden", "Non administrator", "1.0", null, List.of(), Set.of()))));
    }

    @Test void pluginMetadataUsesResourceItemsAndChangedDeclarationsNeedConsent() {
        UUID target = forumSystemId();
        IResourceItemService<?> resources = IGuiceContext.get(IResourceItemService.class);
        ISystemsService<?> systems = IGuiceContext.get(ISystemsService.class);
        List<UUID> media = run(c -> systems.getActivityMaster(c.getItem1(), c.getItem2())
                .chain(core -> resources.createType(c.getItem1(), "PluginTestMedia", "Catalogue media", core, token)
                        .chain(() -> resources.create(c.getItem1(), "PluginTestMedia", UUID.randomUUID().toString(), new byte[]{1}, core, token))
                        .chain(icon -> resources.create(c.getItem1(), "PluginTestMedia", UUID.randomUUID().toString(), new byte[]{2}, core, token)
                                .map(screenshot -> List.of(icon.getId(), screenshot.getId())))));
        String name = "MediaPlugin-" + UUID.randomUUID();
        var plugin = run(c -> plugins.register(c.getItem1(), c.getItem3(), administrator(),
                new PluginModels.Registration(name, "Catalogue title", "Catalogue description", "1.0", media.getFirst(),
                        List.of(media.getLast(), media.getFirst()), Set.of(target))));
        var catalog = run(c -> plugins.find(c.getItem1(), c.getItem3(), pluginUser(), plugin.id()));
        assertEquals(media.getFirst(), catalog.icon());
        assertEquals(List.of(media.getLast(), media.getFirst()), catalog.screenshots());
        var invocation = new PluginModels.Invocation(plugin.id(), first);
        run(c -> plugins.install(c.getItem1(), c.getItem3(), pluginUser(), plugin.id(), first));
        consent(invocation, target, true);
        run(c -> plugins.register(c.getItem1(), c.getItem3(), administrator(),
                new PluginModels.Registration(name, "Version two", "Updated description", "2.0", null, List.of(), Set.of())));
        assertThrows(SecurityException.class, () -> run(c -> plugins.check(c.getItem1(), c.getItem3(), pluginUser(), invocation)));
        run(c -> plugins.register(c.getItem1(), c.getItem3(), administrator(),
                new PluginModels.Registration(name, "Version three", "Updated description", "3.0", null, List.of(), Set.of(target))));
        assertThrows(SecurityException.class, () -> run(c -> plugins.check(c.getItem1(), c.getItem3(), pluginUser(), invocation)));
        consent(invocation, target, true);
        run(c -> plugins.check(c.getItem1(), c.getItem3(), pluginUser(), invocation));
        assertNull(run(c -> plugins.find(c.getItem1(), c.getItem3(), pluginUser(), plugin.id())).icon());
        assertThrows(SecurityException.class, () -> run(c -> plugins.register(c.getItem1(), c.getItem3(), administrator(),
                new PluginModels.Registration("InvalidResource-" + UUID.randomUUID(), "Invalid", "Invalid icon", "1.0",
                        UUID.randomUUID(), List.of(), Set.of(target)))));
    }

    @Test void changedWriterArchivesRelationshipEvenWhenValueIsUnchanged() {
        ISystemsService<?> systems = IGuiceContext.get(ISystemsService.class);
        IArrangementsService<?> arrangements = IGuiceContext.get(IArrangementsService.class);
        IEventService<?> events = IGuiceContext.get(IEventService.class);
        IClassificationService<?> classes = IGuiceContext.get(IClassificationService.class);
        String role = "PluginHistory-" + UUID.randomUUID();
        UUID[] previous = new UUID[1];
        run(c -> systems.getActivityMaster(c.getItem1(), c.getItem2()).chain(core ->
                arrangements.createArrangementType(c.getItem1(), role, core, token)
                        .chain(() -> classes.create(c.getItem1(), role, role,
                                EnterpriseClassificationDataConcepts.EventXArrangement, core, token))
                        .chain(() -> events.createEventType(c.getItem1(), role, core, token))
                        .chain(() -> events.createEvent(c.getItem1(), role, core, token))
                        .chain(primary -> arrangements.create(c.getItem1(), role, null, "NoClassification", "1", core, token)
                                .chain(secondary -> primary.addArrangement(c.getItem1(), secondary, role, "same", core, token)
                                        .chain(() -> c.getItem1().createNativeQuery("select eventxarrangementsid from event.eventxarrangement "
                                                        + "where eventid=:id", UUID.class)
                                                .setParameter("id", primary.getId()).getSingleResult())
                                        .invoke(id -> previous[0] = id)
                                        .chain(id -> c.getItem1().createNativeQuery("update event.eventxarrangement "
                                                        + "set originalsourcesystemid=:writer where eventxarrangementsid=:id")
                                                .setParameter("writer", c.getItem3().getId()).setParameter("id", id).executeUpdate())
                                        .chain(() -> primary.addOrUpdateArrangement(c.getItem1(), role, secondary, "same", "same", core, token))
                                        .chain(() -> primary.addOrUpdateArrangement(c.getItem1(), role, secondary, "same", "same", core, token))
                                        .chain(() -> c.getItem1().createNativeQuery("select originalsourcesystemid,originalsourcesystemuniqueid,"
                                                        + "effectivetodate>statement_timestamp() from event.eventxarrangement "
                                                        + "where eventid=:id", Object[].class)
                                                .setParameter("id", primary.getId()).getResultList())
                                        .invoke(rows -> {
                                            assertEquals(2, rows.size(), "The second update by the same system is idempotent");
                                            Object[] active = rows.stream().filter(r -> Boolean.TRUE.equals(r[2])).findFirst().orElseThrow();
                                            Object[] archived = rows.stream().filter(r -> Boolean.FALSE.equals(r[2])).findFirst().orElseThrow();
                                            assertEquals(core.getId(), active[0]); assertEquals(previous[0], active[1]);
                                            assertEquals(c.getItem3().getId(), archived[0]);
                                        }).replaceWithVoid()))));
    }

    @Test void revocationWaitsForAuthorizedTransactionThenBlocksNewCalls() throws Exception {
        UUID target = forumSystemId();
        var plugin = plugin(Set.of(target));
        var invocation = new PluginModels.Invocation(plugin.id(), first);
        run(c -> plugins.install(c.getItem1(), c.getItem3(), pluginUser(), plugin.id(), first));
        consent(invocation, target, true);
        CompletableFuture<Void> checked = new CompletableFuture<>();
        CompletableFuture<Void> release = new CompletableFuture<>();
        var active = SessionUtils.withActivityMaster(ENTERPRISE, ForumSystem.NAME, c ->
                plugins.check(c.getItem1(), c.getItem3(), pluginUser(), invocation)
                        .invoke(() -> checked.complete(null)).chain(() -> Uni.createFrom().completionStage(release)))
                .subscribeAsCompletionStage().toCompletableFuture();
        CompletableFuture<Void> revoke = null;
        try {
            checked.get(10, TimeUnit.SECONDS);
            revoke = SessionUtils.withActivityMaster(ENTERPRISE, ForumSystem.NAME,
                    c -> plugins.setSystemAccess(c.getItem1(), c.getItem3(), administrator(), plugin.id(), target, null, false))
                    .subscribeAsCompletionStage().toCompletableFuture();
            boolean waiting = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!waiting && System.nanoTime() < deadline) {
                var probe = PostgreSQLTestDBModule.DATABASE.execInContainer("psql", "-U", "postgres", "-d", "fsdm", "-Atc",
                        "select exists(select 1 from pg_stat_activity where wait_event_type='Lock' and query like '%select a.arrangementid%')");
                assertEquals(0, probe.getExitCode(), probe.getStderr());
                waiting = probe.getStdout().strip().equals("t");
                if (!waiting) Thread.sleep(10);
            }
            assertTrue(waiting, "PostgreSQL must serialize administrator revocation with the authorized transaction");
            assertFalse(revoke.isDone());
        } finally {
            release.complete(null);
            active.get(10, TimeUnit.SECONDS);
            if (revoke != null) revoke.get(10, TimeUnit.SECONDS);
        }
        assertThrows(SecurityException.class, () -> run(c -> plugins.check(c.getItem1(), c.getItem3(), pluginUser(), invocation)));
    }

    @Test void pluginNeedsInstallationConsentAndCurrentForumMembership() {
        UUID target = forumSystemId();
        var plugin = plugin(Set.of(target));
        var invocation = new PluginModels.Invocation(plugin.id(), first);
        Forum forum = run(c -> service.create(c.getItem1(), c.getItem3(), identity(owner),
                new Create(ActivityScope.Realm.WORK, enterpriseId, "Shared plugin forum", List.of(first))));
        assertThrows(SecurityException.class, () -> run(c -> service.find(c.getItem1(), c.getItem3(), delegated(invocation), forum.id())));
        run(c -> plugins.install(c.getItem1(), c.getItem3(), pluginUser(), plugin.id(), first));
        assertThrows(SecurityException.class, () -> run(c -> service.find(c.getItem1(), c.getItem3(), delegated(invocation), forum.id())));
        consent(invocation, target, true);
        assertEquals(forum.id(), run(c -> service.find(c.getItem1(), c.getItem3(), delegated(invocation), forum.id())).id());
        consent(invocation, target, false);
        assertThrows(SecurityException.class, () -> run(c -> service.find(c.getItem1(), c.getItem3(), delegated(invocation), forum.id())));
        consent(invocation, target, true);
        run(c -> service.removeSubscriber(c.getItem1(), c.getItem3(), identity(owner), forum.id(), first));
        assertThrows(NotFoundException.class, () -> run(c -> service.find(c.getItem1(), c.getItem3(), delegated(invocation), forum.id())));
    }

    @Test void enterpriseAndPartyRestrictionsOverrideUserConsent() {
        UUID target = forumSystemId();
        var plugin = plugin(Set.of(target));
        var invocation = new PluginModels.Invocation(plugin.id(), first);
        run(c -> plugins.install(c.getItem1(), c.getItem3(), pluginUser(), plugin.id(), first));
        consent(invocation, target, true);
        run(c -> plugins.check(c.getItem1(), c.getItem3(), pluginUser(), invocation));
        run(c -> plugins.setSystemAccess(c.getItem1(), c.getItem3(), administrator(), plugin.id(), target, first, false));
        assertThrows(SecurityException.class, () -> run(c -> plugins.check(c.getItem1(), c.getItem3(), pluginUser(), invocation)));
        run(c -> plugins.setSystemAccess(c.getItem1(), c.getItem3(), administrator(), plugin.id(), target, first, true));
        run(c -> plugins.check(c.getItem1(), c.getItem3(), pluginUser(), invocation));
        run(c -> plugins.setSystemAccess(c.getItem1(), c.getItem3(), administrator(), plugin.id(), target, null, false));
        assertThrows(SecurityException.class, () -> run(c -> plugins.check(c.getItem1(), c.getItem3(), pluginUser(), invocation)));
        assertThrows(SecurityException.class, () -> run(c -> plugins.setSystemAccess(c.getItem1(), c.getItem3(), pluginUser(), plugin.id(), target, null, true)));
    }

    @Test void reinstallDoesNotReviveConsentAndOrganisationConsentIsPerUser() {
        UUID target = forumSystemId();
        var plugin = plugin(Set.of(target));
        var invocation = new PluginModels.Invocation(plugin.id(), organization);
        run(c -> plugins.install(c.getItem1(), c.getItem3(), administrator(), plugin.id(), organization));
        consent(invocation, target, true);
        run(c -> plugins.check(c.getItem1(), c.getItem3(), pluginUser(), invocation));
        assertThrows(SecurityException.class, () -> run(c -> plugins.check(c.getItem1(), c.getItem3(),
                new PluginModels.Identity(owner, enterpriseId, administratorCredential), invocation)));
        run(c -> plugins.remove(c.getItem1(), c.getItem3(), administrator(), plugin.id(), organization));
        assertThrows(SecurityException.class, () -> run(c -> plugins.check(c.getItem1(), c.getItem3(), pluginUser(), invocation)));
        run(c -> plugins.install(c.getItem1(), c.getItem3(), administrator(), plugin.id(), organization));
        assertThrows(SecurityException.class, () -> run(c -> plugins.check(c.getItem1(), c.getItem3(), pluginUser(), invocation)));
        consent(invocation, target, true);
        run(c -> plugins.check(c.getItem1(), c.getItem3(), pluginUser(), invocation));
    }

    @Test void undeclaredSystemsAndPluginCredentialsAreDenied() {
        var plugin = plugin(Set.of());
        var invocation = new PluginModels.Invocation(plugin.id(), first);
        run(c -> plugins.install(c.getItem1(), c.getItem3(), pluginUser(), plugin.id(), first));
        assertThrows(SecurityException.class, () -> consent(invocation, forumSystemId(), true));
        ISystemsService<?> systems = IGuiceContext.get(ISystemsService.class);
        ISecurityTokenService<?> security = IGuiceContext.get(ISecurityTokenService.class);
        UUID credential = run(c -> systems.findSystem(c.getItem1(), c.getItem2(), plugin.name())
                .chain(extension -> systems.getSecurityIdentityToken(c.getItem1(), extension)));
        run(c -> security.getApplicableSecurityTokenIds(c.getItem1(), c.getItem3(), credential)
                .invoke(tokens -> assertTrue(tokens.isEmpty())).replaceWithVoid());
        assertThrows(SecurityException.class, () -> run(c -> plugins.check(c.getItem1(), c.getItem3(),
                new PluginModels.Identity(first, enterpriseId, credential), invocation)));
        // A User child cannot inherit the extension's Plugin identity or Plugins folder.
        IClassificationService<?> classes = IGuiceContext.get(IClassificationService.class);
        run(c -> systems.getActivityMaster(c.getItem1(), c.getItem2()).chain(core ->
                security.getSecurityToken(c.getItem1(), credential, core)
                        .chain(parent -> security.create(c.getItem1(), "User", "PluginChild-" + UUID.randomUUID(), "Child", core)
                                .chain(child -> classes.find(c.getItem1(), "User", core)
                                        .chain(type -> security.link(c.getItem1(), parent, child, type))
                                        .chain(() -> security.getApplicableSecurityTokenIds(c.getItem1(), core,
                                                UUID.fromString(child.getSecurityToken())))
                                        .invoke(tokens -> assertEquals(Set.of(child.getId()), Set.copyOf(tokens)))))).replaceWithVoid());
        assertThrows(SecurityException.class, () -> run(c -> plugins.register(c.getItem1(), c.getItem3(), administrator(),
                new PluginModels.Registration("PluginTarget-" + UUID.randomUUID(), "Invalid target", "Unregistered capability", "1.0",
                        null, List.of(), Set.of(UUID.randomUUID())))));
    }

    @Test void pluginForumPostRequiresSeparateNotificationConsentAndKeepsSystemProvenance() {
        UUID forumSystem = forumSystemId();
        UUID noticeSystem = SessionUtils.withActivityMaster(ENTERPRISE, NotificationSystem.NAME,
                c -> Uni.createFrom().item(c.getItem3().getId())).await().atMost(Duration.ofMinutes(2));
        // Admin user credential supplies row permissions; behavior grants remain actor-specific.
        var plugin = plugin(Set.of(forumSystem, noticeSystem));
        var invocation = new PluginModels.Invocation(plugin.id(), first);
        var actor = new PluginModels.Identity(first, enterpriseId, administratorCredential);
        run(c -> plugins.install(c.getItem1(), c.getItem3(), administrator(), plugin.id(), first));
        run(c -> plugins.consent(c.getItem1(), c.getItem3(), actor, invocation, forumSystem, true));
        Forum forum = run(c -> service.create(c.getItem1(), c.getItem3(), identity(owner),
                new Create(ActivityScope.Realm.WORK, enterpriseId, "Plugin notification consent", List.of(first))));
        ForumIdentity delegated = new ForumIdentity(first, enterpriseId, new ActivityScope.Context(ActivityScope.Realm.WORK, enterpriseId),
                administratorCredential, invocation);
        NotificationIdentity noticeIdentity = new NotificationIdentity(first, enterpriseId, delegated.context(), administratorCredential, invocation);
        ForumApi api = new ForumApi(() -> Uni.createFrom().item(delegated), service, IGuiceContext.get(INotificationService.class),
                IGuiceContext.get(NotificationDispatcher.class), () -> Uni.createFrom().item(noticeIdentity));
        assertThrows(SecurityException.class, () -> api.post(ENTERPRISE, forum.id(), new PublishPost("Requires both systems"))
                .await().atMost(Duration.ofMinutes(2)));
        assertTrue(run(c -> service.posts(c.getItem1(), c.getItem3(), identity(owner), forum.id(), 0, 10)).items().isEmpty());
        run(c -> plugins.consent(c.getItem1(), c.getItem3(), actor, invocation, noticeSystem, true));
        var post = api.post(ENTERPRISE, forum.id(), new PublishPost("Both systems authorized")).await().atMost(Duration.ofMinutes(2));
        assertEquals(forumSystem, run(c -> c.getItem1().createNativeQuery("select originalsourcesystemid from event.event where eventid=:id", UUID.class)
                .setParameter("id", post.id()).getSingleResult()));
        run(c -> c.getItem1().createNativeQuery("select e.originalsourcesystemid from event.event e "
                        + "join event.eventxarrangement a on a.eventid=e.eventid "
                        + "join event.eventxeventtype x on x.eventid=e.eventid join event.eventtype t on t.eventtypeid=x.eventtypeid "
                        + "where a.arrangementid=:plugin and t.eventtypename='Plugin Invocation'", UUID.class)
                .setParameter("plugin", plugin.id()).getResultList().invoke(rows -> assertEquals(List.of(forumSystem), rows)).replaceWithVoid());
        ForumApi downgraded = new ForumApi(() -> Uni.createFrom().item(delegated), service, IGuiceContext.get(INotificationService.class),
                IGuiceContext.get(NotificationDispatcher.class), () -> Uni.createFrom().item(new NotificationIdentity(first, enterpriseId,
                delegated.context(), administratorCredential)));
        assertThrows(SecurityException.class, () -> downgraded.post(ENTERPRISE, forum.id(), new PublishPost("Must not drop plugin identity"))
                .await().atMost(Duration.ofMinutes(2)));
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
