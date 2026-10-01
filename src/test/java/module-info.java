import com.guicedee.client.services.lifecycle.IGuiceModule;

open module activity.master.forum.tests {
    requires com.guicedee.activitymaster.forums;
    requires com.guicedee.activitymaster.notifications;
    requires com.guicedee.activitymaster.conversations;
    requires java.net.http;
    requires com.guicedee.activitymaster.fsdm;
    requires com.guicedee.activitymaster.fsdm.client;
    requires com.guicedee.guicedinjection;
    requires com.guicedee.persistence;
    requires com.google.guice;
    requires io.smallrye.mutiny;
    requires org.hibernate.reactive;
    requires org.junit.jupiter.api;
    requires org.testcontainers;
    requires io.vertx.sql.client.pg;
    requires jakarta.ws.rs;

    provides IGuiceModule with com.guicedee.activitymaster.forums.test.PostgreSQLTestDBModule,
        com.guicedee.activitymaster.forums.test.CommunicationsTestHost;
    provides com.guicedee.vertx.web.spi.VertxRouterConfigurator
        with com.guicedee.activitymaster.forums.test.CommunicationsTestHost.Authentication;
    provides com.guicedee.vertx.web.spi.VertxHttpServerConfigurator
        with com.guicedee.activitymaster.forums.test.CommunicationsTestHost.ServerCapture;
}
